package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.CoordinationDirection
import ru.trafficsignalstudio.model.NetworkCoordinationSettings
import ru.trafficsignalstudio.model.NetworkTrafficLink
import ru.trafficsignalstudio.model.RoadNetwork
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.hypot
import kotlin.math.min

enum class CoordinationIssueCode {
    NO_REFERENCE, MISSING_SIGNAL_PLAN, INVALID_SIGNAL_PLAN, CYCLE_MISMATCH,
    SOURCE_MOVEMENT_NOT_SIGNALLED, TARGET_MOVEMENT_NOT_SIGNALLED, UNREACHABLE_INTERSECTION,
    NO_COORDINATED_LINKS, LONG_COORDINATION_SECTION
}

data class CoordinationDiagnostic(
    val code: CoordinationIssueCode,
    val message: String,
    val intersectionId: String? = null,
    val linkId: String? = null,
    val isWarning: Boolean = false
)

data class CoordinationLinkResult(
    val linkId: String,
    val fromIntersectionId: String,
    val toIntersectionId: String,
    val travelTimeSeconds: Double,
    val sourceGreenStartSeconds: Int,
    val sourceGreenEndSeconds: Int,
    val targetGreenStartSeconds: Int,
    val targetGreenEndSeconds: Int,
    val progressionBandwidthSeconds: Double,
    val flowSharePercent: Double
)

data class NetworkCoordinationReport(
    val cycleLengthSeconds: Int,
    val offsetsSeconds: Map<String, Int>,
    val links: List<CoordinationLinkResult>,
    val diagnostics: List<CoordinationDiagnostic>,
    val objectiveBandwidthSeconds: Double,
    val isValid: Boolean
)

/**
 * Engineering coordination based on actual signal-plan green windows and
 * measured/configured link travel times. Yellow and all-red are deliberately
 * excluded from progression bandwidth: they are clearance intervals, not green.
 *
 * The selected common cycle is not used to stretch or compress local plans.
 * Every coordinated node must have a valid plan whose real cycle equals it.
 */
object NetworkCoordinationCalculator {
    private data class GreenWindow(val start: Int, val end: Int) {
        val midpoint: Double get() = (start + end) / 2.0
    }

    private data class DirectedLink(
        val source: String,
        val target: String,
        val sourceMovement: String,
        val targetMovement: String,
        val id: String,
        val distanceMeters: Double,
        val speedKmh: Double,
        val flowSharePercent: Double
    ) {
        val travelTimeSeconds: Double get() = distanceMeters / (speedKmh / 3.6)
    }

    fun calculateOffsets(network: RoadNetwork, settings: NetworkCoordinationSettings): Map<String, Int> =
        calculateReport(network, settings).offsetsSeconds

    fun calculateReport(
        network: RoadNetwork,
        settings: NetworkCoordinationSettings
    ): NetworkCoordinationReport {
        val nodes = network.intersections.map { it.id }.toSet()
        val cycle = settings.cycleLengthSeconds
        if (nodes.isEmpty()) return NetworkCoordinationReport(cycle, emptyMap(), emptyList(), emptyList(), 0.0, false)

        val diagnostics = mutableListOf<CoordinationDiagnostic>()
        val root = settings.referenceIntersectionId?.takeIf { it in nodes }
        if (root == null) {
            diagnostics += CoordinationDiagnostic(
                CoordinationIssueCode.NO_REFERENCE,
                "Не выбран существующий опорный светофорный объект."
            )
            return NetworkCoordinationReport(cycle, emptyMap(), emptyList(), diagnostics, 0.0, false)
        }

        val directedLinks = buildList {
            network.trafficLinks.filter { it.enabled && it.fromIntersectionId in nodes && it.toIntersectionId in nodes }
                .forEach { link ->
                    if (settings.direction != CoordinationDirection.REVERSE) add(link.toDirected(settings))
                    if (settings.direction != CoordinationDirection.FORWARD) add(link.toDirected(settings, reverse = true))
                }
        }
        if (directedLinks.isEmpty()) {
            diagnostics += CoordinationDiagnostic(
                CoordinationIssueCode.NO_COORDINATED_LINKS,
                "Нет активных межузловых связей в выбранном направлении координации."
            )
        }

        val windowsByNode = mutableMapOf<String, Map<String, GreenWindow>>()
        network.intersections.forEach { node ->
            val plan = node.signalPlan
            if (plan == null || !plan.enabled) {
                if (directedLinks.any { it.source == node.id || it.target == node.id }) {
                    diagnostics += CoordinationDiagnostic(
                        CoordinationIssueCode.MISSING_SIGNAL_PLAN,
                        "Для объекта «${node.name}» отсутствует включённый светофорный план.",
                        intersectionId = node.id
                    )
                }
                return@forEach
            }
            val nonStandardYellow = plan.phases.firstOrNull { it.yellowSeconds != 3 }
            if (nonStandardYellow != null) {
                diagnostics += CoordinationDiagnostic(
                    CoordinationIssueCode.INVALID_SIGNAL_PLAN,
                    "Фаза «${nonStandardYellow.name}» объекта «${node.name}»: жёлтый сигнал должен длиться 3 с согласно п. 7.5.2 ГОСТ Р 52289-2019.",
                    intersectionId = node.id
                )
                return@forEach
            }
            val timing = SignalPlanTimingAnalyzer.analyze(node)
            if (!timing.isValid || timing.cycleLengthSeconds <= 0) {
                diagnostics += CoordinationDiagnostic(
                    CoordinationIssueCode.INVALID_SIGNAL_PLAN,
                    "План объекта «${node.name}» содержит ошибки и не может участвовать в координации.",
                    intersectionId = node.id
                )
                return@forEach
            }
            if (timing.cycleLengthSeconds != cycle) {
                diagnostics += CoordinationDiagnostic(
                    CoordinationIssueCode.CYCLE_MISMATCH,
                    "Фактический цикл объекта «${node.name}» равен ${timing.cycleLengthSeconds} с, общий цикл координации — ${cycle} с. Автоматическое масштабирование фаз запрещено.",
                    intersectionId = node.id
                )
                return@forEach
            }
            val greenByMovement = mutableMapOf<String, GreenWindow>()
            timing.phaseWindows.forEach { phaseWindow ->
                val phase = plan.phases.firstOrNull { it.id == phaseWindow.phaseId } ?: return@forEach
                phase.greenConnectionIds.forEach { movementId ->
                    greenByMovement[movementId] = GreenWindow(phaseWindow.startSeconds, phaseWindow.greenEndSeconds)
                }
            }
            windowsByNode[node.id] = greenByMovement
        }

        val usableLinks = directedLinks.filter { link ->
            val sourceWindow = windowsByNode[link.source]?.get(link.sourceMovement)
            val targetWindow = windowsByNode[link.target]?.get(link.targetMovement)
            var usable = true
            if (sourceWindow == null) {
                diagnostics += CoordinationDiagnostic(
                    CoordinationIssueCode.SOURCE_MOVEMENT_NOT_SIGNALLED,
                    "Выходное движение связи «${link.id}» не имеет действующего зелёного интервала.",
                    intersectionId = link.source, linkId = link.id
                )
                usable = false
            }
            if (targetWindow == null) {
                diagnostics += CoordinationDiagnostic(
                    CoordinationIssueCode.TARGET_MOVEMENT_NOT_SIGNALLED,
                    "Входное движение связи «${link.id}» не имеет действующего зелёного интервала на принимающем объекте.",
                    intersectionId = link.target, linkId = link.id
                )
                usable = false
            }
            usable
        }

        val offsets = nodes.associateWith { 0 }.toMutableMap()
        if (usableLinks.isNotEmpty()) {
            // First obtain a physically meaningful travel-time progression seed.
            val outgoing = usableLinks.groupBy { it.source }
            val travelTimes = mutableMapOf(root to 0.0)
            val queue = PriorityQueue<Pair<String, Double>>(compareBy { it.second })
            queue.add(root to 0.0)
            while (queue.isNotEmpty()) {
                val (current, known) = queue.remove()
                if (known > (travelTimes[current] ?: Double.POSITIVE_INFINITY) + 1e-9) continue
                outgoing[current].orEmpty().forEach { link ->
                    val sourceWindow = windowsByNode.getValue(link.source).getValue(link.sourceMovement)
                    val targetWindow = windowsByNode.getValue(link.target).getValue(link.targetMovement)
                    val phaseCorrection = sourceWindow.midpoint - targetWindow.midpoint
                    val candidate = known + link.travelTimeSeconds
                    if (candidate + 1e-9 < (travelTimes[link.target] ?: Double.POSITIVE_INFINITY)) {
                        travelTimes[link.target] = candidate
                        offsets[link.target] = (offsets.getValue(link.source) +
                            link.travelTimeSeconds.roundToInt() + phaseCorrection.roundToInt()).mod(cycle)
                        queue.add(link.target to candidate)
                    }
                }
            }

            // Deterministic coordinate descent maximizes total weighted green-band
            // overlap on all links while keeping the reference offset fixed at zero.
            val nodeOrder = network.intersections.map { it.id }.filter { it != root }
            repeat(12) {
                var changed = false
                nodeOrder.forEach { nodeId ->
                    if (windowsByNode[nodeId] == null) return@forEach
                    val incident = usableLinks.filter { it.source == nodeId || it.target == nodeId }
                    if (incident.isEmpty()) return@forEach
                    val current = offsets.getValue(nodeId)
                    var best = current
                    var bestScore = localScore(nodeId, current, incident, offsets, windowsByNode, cycle)
                    for (candidate in 0 until cycle) {
                        val score = localScore(nodeId, candidate, incident, offsets, windowsByNode, cycle)
                        if (score > bestScore + 1e-8 || (abs(score - bestScore) <= 1e-8 && candidate < best)) {
                            bestScore = score
                            best = candidate
                        }
                    }
                    if (best != current) {
                        offsets[nodeId] = best
                        changed = true
                    }
                }
                if (!changed) return@repeat
            }
        }

        val reached = reachableFrom(root, usableLinks)
        network.intersections.filter { it.id !in reached }.forEach { node ->
            diagnostics += CoordinationDiagnostic(
                CoordinationIssueCode.UNREACHABLE_INTERSECTION,
                "Объект «${node.name}» не связан с опорным объектом в выбранном направлении; сдвиг не рассчитывался.",
                intersectionId = node.id,
                isWarning = true
            )
            offsets[node.id] = 0
        }

        usableLinks.filter { it.distanceMeters > 1000.0 }.forEach { link ->
            diagnostics += CoordinationDiagnostic(
                CoordinationIssueCode.LONG_COORDINATION_SECTION,
                "Расстояние по связи «${link.id}» превышает 1 км. Проверьте применимость рекомендации ГОСТ Р 58653-2019, п. 8.3, для данного типа дороги.",
                linkId = link.id,
                isWarning = true
            )
        }

        val linkResults = usableLinks.map { link ->
            val sourceWindow = windowsByNode.getValue(link.source).getValue(link.sourceMovement)
            val targetWindow = windowsByNode.getValue(link.target).getValue(link.targetMovement)
            val bandwidth = overlapSeconds(
                sourceOffset = offsets.getValue(link.source),
                sourceWindow = sourceWindow,
                travelTime = link.travelTimeSeconds,
                targetOffset = offsets.getValue(link.target),
                targetWindow = targetWindow,
                cycle = cycle
            )
            CoordinationLinkResult(
                linkId = link.id,
                fromIntersectionId = link.source,
                toIntersectionId = link.target,
                travelTimeSeconds = link.travelTimeSeconds,
                sourceGreenStartSeconds = sourceWindow.start,
                sourceGreenEndSeconds = sourceWindow.end,
                targetGreenStartSeconds = targetWindow.start,
                targetGreenEndSeconds = targetWindow.end,
                progressionBandwidthSeconds = bandwidth,
                flowSharePercent = link.flowSharePercent
            )
        }
        val objective = linkResults.sumOf { it.progressionBandwidthSeconds * it.flowSharePercent / 100.0 }
        val hasBlockingErrors = diagnostics.any {
            it.code != CoordinationIssueCode.UNREACHABLE_INTERSECTION &&
                it.code != CoordinationIssueCode.NO_COORDINATED_LINKS
        }
        return NetworkCoordinationReport(
            cycleLengthSeconds = cycle,
            offsetsSeconds = offsets.mapValues { it.value.mod(cycle) },
            links = linkResults,
            diagnostics = diagnostics.distinct(),
            objectiveBandwidthSeconds = objective,
            isValid = !hasBlockingErrors && linkResults.isNotEmpty()
        )
    }

    private fun NetworkTrafficLink.toDirected(
        settings: NetworkCoordinationSettings,
        reverse: Boolean = false
    ): DirectedLink {
        val effectiveDistance = if (centerline.size >= 2) {
            centerline.zipWithNext().sumOf { (a, b) ->
                hypot(b.xMeters - a.xMeters, b.yMeters - a.yMeters)
            }
        } else distanceMeters
        val effectiveSpeed = min(settings.progressionSpeedKmh, travelSpeedKmh)
        return if (!reverse) DirectedLink(
            fromIntersectionId, toIntersectionId, fromMovementConnectionId, toMovementConnectionId,
            id, effectiveDistance, effectiveSpeed, flowSharePercent
        ) else DirectedLink(
            toIntersectionId, fromIntersectionId, toMovementConnectionId, fromMovementConnectionId,
            id, effectiveDistance, effectiveSpeed, flowSharePercent
        )
    }

    private fun localScore(
        nodeId: String,
        candidateOffset: Int,
        links: List<DirectedLink>,
        offsets: Map<String, Int>,
        windows: Map<String, Map<String, GreenWindow>>,
        cycle: Int
    ): Double = links.sumOf { link ->
        val sourceOffset = if (link.source == nodeId) candidateOffset else offsets.getValue(link.source)
        val targetOffset = if (link.target == nodeId) candidateOffset else offsets.getValue(link.target)
        overlapSeconds(
            sourceOffset,
            windows.getValue(link.source).getValue(link.sourceMovement),
            link.travelTimeSeconds,
            targetOffset,
            windows.getValue(link.target).getValue(link.targetMovement),
            cycle
        ) * link.flowSharePercent / 100.0
    }

    private fun overlapSeconds(
        sourceOffset: Int,
        sourceWindow: GreenWindow,
        travelTime: Double,
        targetOffset: Int,
        targetWindow: GreenWindow,
        cycle: Int
    ): Double {
        val sourceStart = sourceOffset + sourceWindow.start + travelTime
        val sourceEnd = sourceOffset + sourceWindow.end + travelTime
        val targetStart = targetOffset + targetWindow.start
        val targetEnd = targetOffset + targetWindow.end
        var overlap = 0.0
        for (shift in -2..2) {
            val shiftedStart = targetStart + shift * cycle
            val shiftedEnd = targetEnd + shift * cycle
            overlap += max(0.0, min(sourceEnd, shiftedEnd) - max(sourceStart, shiftedStart))
        }
        return overlap.coerceIn(0.0, min(sourceWindow.end - sourceWindow.start, targetWindow.end - targetWindow.start).toDouble())
    }

    private fun reachableFrom(root: String, links: List<DirectedLink>): Set<String> {
        val reached = mutableSetOf(root)
        val queue = ArrayDeque<String>()
        queue.add(root)
        val outgoing = links.groupBy { it.source }
        while (queue.isNotEmpty()) {
            outgoing[queue.removeFirst()].orEmpty().forEach { if (reached.add(it.target)) queue.add(it.target) }
        }
        return reached
    }
}
