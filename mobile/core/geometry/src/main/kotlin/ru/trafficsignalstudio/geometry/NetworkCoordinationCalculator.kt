package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.CoordinationDirection
import ru.trafficsignalstudio.model.NetworkCoordinationSettings
import ru.trafficsignalstudio.model.NetworkTrafficLink
import ru.trafficsignalstudio.model.RoadNetwork
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

enum class CoordinationIssueCode {
    EMPTY_NETWORK,
    INVALID_SIGNAL_PLAN,
    MISSING_SIGNAL_PLAN,
    CYCLE_LENGTH_MISMATCH,
    MISSING_UPSTREAM_GREEN,
    MISSING_DOWNSTREAM_GREEN,
    LINK_SPEED_LIMITED,
    UNREACHABLE_INTERSECTION,
    CONFLICTING_CORRIDOR_CONSTRAINTS
}

data class CoordinationDiagnostic(
    val code: CoordinationIssueCode,
    val message: String,
    val intersectionId: String? = null,
    val linkId: String? = null,
    val isError: Boolean = false
)

data class LinkProgressionResult(
    val linkId: String,
    val fromIntersectionId: String,
    val toIntersectionId: String,
    val travelTimeSeconds: Double,
    val upstreamGreenStartSeconds: Int,
    val downstreamGreenStartSeconds: Int,
    val requiredOffsetDifferenceSeconds: Int,
    val actualOffsetDifferenceSeconds: Int,
    val coordinationErrorSeconds: Int,
    val weight: Double
)

data class NetworkCoordinationReport(
    val cycleLengthSeconds: Int,
    val referenceIntersectionId: String?,
    val offsetsSeconds: Map<String, Int>,
    val linkProgressions: List<LinkProgressionResult>,
    val diagnostics: List<CoordinationDiagnostic>
) {
    val isValid: Boolean get() = diagnostics.none { it.isError }
    val maximumAbsoluteErrorSeconds: Int
        get() = linkProgressions.maxOfOrNull { kotlin.math.abs(it.coordinationErrorSeconds) } ?: 0
}

/**
 * Engineering progression coordination.
 *
 * The offset convention matches TrafficSimulationEngine: a phase whose local
 * start is p becomes active at absolute model time (offset + p).
 *
 * For every directed link:
 *   offset[to] - offset[from] =
 *       upstreamGreenStart + linkTravelTime - downstreamGreenStart (mod cycle).
 *
 * Multiple paths and closed network loops are solved as a weighted circular
 * least-squares problem. Link flow share and usable green-window duration
 * determine the constraint weight.
 */
object NetworkCoordinationCalculator {
    private data class DirectedConstraint(
        val link: NetworkTrafficLink,
        val from: String,
        val to: String,
        val upstreamGreenStart: Int,
        val downstreamGreenStart: Int,
        val travelTime: Double,
        val weight: Double
    ) {
        fun requiredDelta(cycle: Int): Double =
            wrap(upstreamGreenStart + travelTime - downstreamGreenStart, cycle)
    }

    fun calculate(
        network: RoadNetwork,
        settings: NetworkCoordinationSettings
    ): NetworkCoordinationReport {
        val nodes = network.intersections.map { it.id }.distinct()
        val cycle = settings.cycleLengthSeconds.coerceIn(30, 240)
        if (nodes.isEmpty()) {
            return NetworkCoordinationReport(cycle, null, emptyMap(), emptyList(), listOf(
                CoordinationDiagnostic(CoordinationIssueCode.EMPTY_NETWORK, "В сети нет перекрёстков", isError = true)
            ))
        }
        val diagnostics = mutableListOf<CoordinationDiagnostic>()
        val root = settings.referenceIntersectionId?.takeIf { it in nodes } ?: nodes.first()
        val intersectionById = network.intersections.associateBy { it.id }
        val phaseStarts = mutableMapOf<Pair<String, String>, Pair<Int, Int>>()

        network.intersections.forEach { intersection ->
            val plan = intersection.signalPlan
            if (plan == null || !plan.enabled) {
                diagnostics += CoordinationDiagnostic(
                    CoordinationIssueCode.MISSING_SIGNAL_PLAN,
                    "Для перекрёстка «${intersection.name}» не задан включённый светофорный план; для движений без плана используется нулевое начало зелёного окна.",
                    intersection.id
                )
                intersection.laneConnections.forEach { connection ->
                    phaseStarts[intersection.id to connection.id] = 0 to 0
                }
                return@forEach
            }
            val timing = SignalPlanTimingAnalyzer.analyze(intersection)
            if (!timing.isValid) {
                diagnostics += CoordinationDiagnostic(
                    CoordinationIssueCode.INVALID_SIGNAL_PLAN,
                    "Светофорный план «${intersection.name}» содержит ошибки и не может считаться нормативно проверенным.",
                    intersection.id,
                    isError = true
                )
            }
            if (timing.cycleLengthSeconds != cycle) {
                diagnostics += CoordinationDiagnostic(
                    CoordinationIssueCode.CYCLE_LENGTH_MISMATCH,
                    "Цикл перекрёстка «${intersection.name}» равен ${timing.cycleLengthSeconds} с, а общий цикл координации — ${cycle} с. Автоматическое растягивание фаз не применяется.",
                    intersection.id,
                    isError = true
                )
            }
            timing.phaseWindows.forEach { window ->
                val phase = plan.phases.firstOrNull { it.id == window.phaseId } ?: return@forEach
                phase.greenConnectionIds.forEach { connectionId ->
                    val greenStart = window.startSeconds
                    val usableGreenEnd = window.flashingGreenEndSeconds
                    phaseStarts[intersection.id to connectionId] = greenStart to (usableGreenEnd - greenStart).coerceAtLeast(0)
                }
            }
            // ГОСТ Р 52289-2019, 7.5.2: yellow interval is 3 s.
            plan.phases.filter { it.yellowSeconds != 3 }.forEach { phase ->
                diagnostics += CoordinationDiagnostic(
                    CoordinationIssueCode.INVALID_SIGNAL_PLAN,
                    "Фаза «${phase.name}»: жёлтый интервал должен составлять 3 с по п. 7.5.2 ГОСТ Р 52289-2019.",
                    intersection.id,
                    isError = true
                )
            }
        }

        val directed = mutableListOf<DirectedConstraint>()
        network.trafficLinks.filter { it.enabled && it.fromIntersectionId in nodes && it.toIntersectionId in nodes }
            .forEach { link ->
                if (settings.direction != CoordinationDirection.REVERSE) {
                    addConstraint(link, link.fromIntersectionId, link.toIntersectionId,
                        link.fromMovementConnectionId, link.toMovementConnectionId,
                        phaseStarts, settings, directed, diagnostics)
                }
                if (settings.direction != CoordinationDirection.FORWARD) {
                    addConstraint(link, link.toIntersectionId, link.fromIntersectionId,
                        link.toMovementConnectionId, link.fromMovementConnectionId,
                        phaseStarts, settings, directed, diagnostics)
                }
            }

        val offsets = solveOffsets(nodes, root, directed, cycle)
        val progressionResults = directed.map { edge ->
            val required = edge.requiredDelta(cycle).roundToInt().mod(cycle)
            val actual = wrap(offsets[edge.to].orZero() - offsets[edge.from].orZero(), cycle).roundToInt().mod(cycle)
            val error = circularDifference(actual.toDouble(), required.toDouble(), cycle).roundToInt()
            LinkProgressionResult(
                linkId = edge.link.id,
                fromIntersectionId = edge.from,
                toIntersectionId = edge.to,
                travelTimeSeconds = edge.travelTime,
                upstreamGreenStartSeconds = edge.upstreamGreenStart,
                downstreamGreenStartSeconds = edge.downstreamGreenStart,
                requiredOffsetDifferenceSeconds = required,
                actualOffsetDifferenceSeconds = actual,
                coordinationErrorSeconds = error,
                weight = edge.weight
            )
        }
        progressionResults.filter { kotlin.math.abs(it.coordinationErrorSeconds) > 2 }.forEach { result ->
            diagnostics += CoordinationDiagnostic(
                CoordinationIssueCode.CONFLICTING_CORRIDOR_CONSTRAINTS,
                "Связь ${result.fromIntersectionId} → ${result.toIntersectionId}: остаточная ошибка координации ${result.coordinationErrorSeconds} с. Проверьте циклическую структуру сети, циклы и интенсивности.",
                linkId = result.linkId
            )
        }
        nodes.filter { it != root && directed.none { edge -> edge.to == it || edge.from == it } }.forEach { id ->
            diagnostics += CoordinationDiagnostic(
                CoordinationIssueCode.UNREACHABLE_INTERSECTION,
                "Перекрёсток «${intersectionById[id]?.name ?: id}» не связан с координируемым коридором.",
                id
            )
        }
        return NetworkCoordinationReport(cycle, root, offsets, progressionResults, diagnostics.distinct())
    }

    /** Compatibility API retained for callers that only need cycle offsets. */
    fun calculateOffsets(
        network: RoadNetwork,
        settings: NetworkCoordinationSettings
    ): Map<String, Int> = calculate(network, settings).offsetsSeconds

    private fun addConstraint(
        link: NetworkTrafficLink,
        from: String,
        to: String,
        fromMovement: String,
        toMovement: String,
        phaseStarts: Map<Pair<String, String>, Pair<Int, Int>>,
        settings: NetworkCoordinationSettings,
        output: MutableList<DirectedConstraint>,
        diagnostics: MutableList<CoordinationDiagnostic>
    ) {
        val upstream = phaseStarts[from to fromMovement]
        val downstream = phaseStarts[to to toMovement]
        if (upstream == null) {
            diagnostics += CoordinationDiagnostic(
                CoordinationIssueCode.MISSING_UPSTREAM_GREEN,
                "Для выходного движения связи не найдено разрешающее зелёное окно.",
                from, link.id, isError = true
            )
            return
        }
        if (downstream == null) {
            diagnostics += CoordinationDiagnostic(
                CoordinationIssueCode.MISSING_DOWNSTREAM_GREEN,
                "Для входного движения связи не найдено разрешающее зелёное окно.",
                to, link.id, isError = true
            )
            return
        }
        val targetSpeed = settings.progressionSpeedKmh
        val speed = minOf(targetSpeed, link.travelSpeedKmh)
        if (speed + 1e-6 < targetSpeed) {
            diagnostics += CoordinationDiagnostic(
                CoordinationIssueCode.LINK_SPEED_LIMITED,
                "На связи ${link.id} скорость сообщения ограничена скоростью участка ${link.travelSpeedKmh} км/ч.",
                linkId = link.id
            )
        }
        val distance = if (link.centerline.size >= 2) {
            link.centerline.zipWithNext().sumOf { (a, b) ->
                hypot(b.xMeters - a.xMeters, b.yMeters - a.yMeters)
            }
        } else link.distanceMeters
        val travelTime = distance / (speed / 3.6)
        val usableGreen = minOf(upstream.second, downstream.second).coerceAtLeast(1)
        val weight = link.flowSharePercent.coerceAtLeast(0.1) * usableGreen
        output += DirectedConstraint(link, from, to, upstream.first, downstream.first, travelTime, weight)
    }

    private fun solveOffsets(
        nodes: List<String>,
        root: String,
        edges: List<DirectedConstraint>,
        cycle: Int
    ): Map<String, Int> {
        if (edges.isEmpty()) return nodes.associateWith { 0 }
        val offsets = nodes.associateWith { 0.0 }.toMutableMap()
        // Seed the solution with a shortest-travel-time tree from the reference node.
        val visited = mutableSetOf(root)
        repeat(nodes.size) {
            val candidates = edges.filter { it.from in visited && it.to !in visited }
            val next = candidates.minByOrNull { it.travelTime } ?: return@repeat
            offsets[next.to] = wrap(offsets[next.from].orZero() + next.requiredDelta(cycle), cycle)
            visited += next.to
        }
        // Relax all network constraints. Circular averaging avoids discontinuities at cycle zero.
        repeat(250) {
            var maximumChange = 0.0
            nodes.filter { it != root }.forEach { node ->
                var x = 0.0
                var y = 0.0
                edges.forEach { edge ->
                    val target = when (node) {
                        edge.to -> wrap(offsets[edge.from].orZero() + edge.requiredDelta(cycle), cycle)
                        edge.from -> wrap(offsets[edge.to].orZero() - edge.requiredDelta(cycle), cycle)
                        else -> null
                    } ?: return@forEach
                    val angle = target / cycle * 2.0 * PI
                    x += cos(angle) * edge.weight
                    y += sin(angle) * edge.weight
                }
                if (kotlin.math.hypot(x, y) > 1e-9) {
                    val angle = atan2(y, x)
                    val target = wrap(angle / (2.0 * PI) * cycle, cycle)
                    val previous = offsets[node].orZero()
                    val change = circularDifference(target, previous, cycle)
                    offsets[node] = wrap(previous + change * 0.65, cycle)
                    maximumChange = maxOf(maximumChange, kotlin.math.abs(change))
                }
            }
            if (maximumChange < 0.01) return@repeat
        }
        offsets[root] = 0.0
        return offsets.mapValues { (_, value) -> wrap(value, cycle).roundToInt().mod(cycle) }
    }

    private fun wrap(value: Double, cycle: Int): Double =
        ((value % cycle) + cycle) % cycle

    private fun circularDifference(target: Double, current: Double, cycle: Int): Double {
        var difference = wrap(target - current, cycle)
        if (difference > cycle / 2.0) difference -= cycle
        return difference
    }

    private fun Map<String, Double>.orZero(key: String): Double = this[key] ?: 0.0
    private fun Double?.orZero(): Double = this ?: 0.0
}
