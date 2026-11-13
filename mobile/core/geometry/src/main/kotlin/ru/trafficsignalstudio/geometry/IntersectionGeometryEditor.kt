package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.Approach
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.Lane
import ru.trafficsignalstudio.model.LaneFlowDirection
import ru.trafficsignalstudio.model.LaneConnection
import ru.trafficsignalstudio.model.MovementKind
import ru.trafficsignalstudio.model.RoadSegment
import ru.trafficsignalstudio.model.RoadSegmentLane
import ru.trafficsignalstudio.model.LaneTravelDirection
import ru.trafficsignalstudio.model.SignalPlan

/**
 * Pure geometry-editing operations. All dimensions are stored in metres;
 * screen pixels are deliberately kept out of the domain model.
 */
object IntersectionGeometryEditor {
    const val MIN_LANE_COUNT = 1
    const val MAX_LANE_COUNT = 8
    const val MIN_LANE_WIDTH_METERS = 2.5
    const val MAX_LANE_WIDTH_METERS = 5.0
    const val LANE_WIDTH_STEP_METERS = 0.25

    fun setLaneCount(intersection: Intersection, approachId: String, count: Int): Intersection {
        require(count in MIN_LANE_COUNT..MAX_LANE_COUNT) {
            "Количество полос должно быть от $MIN_LANE_COUNT до $MAX_LANE_COUNT"
        }
        val updatedApproaches = intersection.approaches.map { approach ->
            if (approach.id != approachId) approach
            else approach.copy(lanes = resizeLanes(approach.lanes, count))
        }
        return pruneInvalidConnections(intersection, updatedApproaches)
    }

    fun changeLaneWidth(
        intersection: Intersection,
        approachId: String,
        laneId: String,
        deltaMeters: Double
    ): Intersection {
        require(deltaMeters.isFinite())
        return intersection.copy(
            approaches = intersection.approaches.map { approach ->
                if (approach.id != approachId) approach
                else approach.copy(lanes = approach.lanes.map { lane ->
                    if (lane.id != laneId) lane
                    else lane.copy(
                        widthMeters = (lane.widthMeters + deltaMeters)
                            .coerceIn(MIN_LANE_WIDTH_METERS, MAX_LANE_WIDTH_METERS)
                    )
                })
            }
        )
    }

    fun setLaneMovements(
        intersection: Intersection,
        approachId: String,
        laneId: String,
        movements: Set<MovementKind>
    ): Intersection {
        require(movements.isNotEmpty()) { "Для полосы должно быть задано хотя бы одно направление" }
        val updatedApproaches = intersection.approaches.map { approach ->
            if (approach.id != approachId) approach
            else approach.copy(lanes = approach.lanes.map { lane ->
                if (lane.id == laneId) lane.copy(allowedMovements = movements) else lane
            })
        }
        return pruneInvalidConnections(intersection, updatedApproaches)
    }

    fun setLaneFlowDirection(
        intersection: Intersection,
        approachId: String,
        laneId: String,
        direction: LaneFlowDirection
    ): Intersection {
        val updatedApproaches = intersection.approaches.map { approach ->
            if (approach.id != approachId) approach
            else approach.copy(lanes = approach.lanes.map { lane ->
                if (lane.id == laneId) lane.copy(flowDirection = direction) else lane
            })
        }
        return pruneInvalidConnections(intersection, updatedApproaches)
    }

    fun addLaneConnection(intersection: Intersection, connection: LaneConnection): Intersection {
        val source = intersection.approaches.firstOrNull { it.id == connection.fromApproachId }
            ?.lanes?.firstOrNull { it.id == connection.fromLaneId }
        val target = intersection.approaches.firstOrNull { it.id == connection.toApproachId }
            ?.lanes?.firstOrNull { it.id == connection.toLaneId }
        require(source != null) { "Въездная полоса не найдена" }
        require(target != null) { "Выездная полоса не найдена" }
        require(source.flowDirection == LaneFlowDirection.INTO_INTERSECTION) {
            "Исходная полоса должна быть направлена к перекрёстку"
        }
        require(target.flowDirection == LaneFlowDirection.OUT_OF_INTERSECTION) {
            "Целевая полоса должна быть направлена от перекрёстка"
        }
        val sourceApproach = intersection.approaches.first { it.id == connection.fromApproachId }
        val targetApproach = intersection.approaches.first { it.id == connection.toApproachId }
        val movement = classifyMovement(sourceApproach, targetApproach)
        require(movement in source.allowedMovements) {
            "Манёвр «${movementLabel(movement)}» запрещён для исходной полосы"
        }
        require(connection.fromApproachId != connection.toApproachId || connection.fromLaneId != connection.toLaneId) {
            "Нельзя соединить полосу саму с собой"
        }
        require(intersection.laneConnections.none {
            it.fromApproachId == connection.fromApproachId && it.fromLaneId == connection.fromLaneId &&
                it.toApproachId == connection.toApproachId && it.toLaneId == connection.toLaneId
        }) { "Такая связь полос уже существует" }
        return intersection.copy(laneConnections = intersection.laneConnections + connection)
    }

    fun removeLaneConnection(intersection: Intersection, connectionId: String): Intersection =
        intersection.copy(laneConnections = intersection.laneConnections.filterNot { it.id == connectionId })

    /** Returns explicit lane definitions, synthesizing legacy lanes without mutating the project. */
    fun effectiveRoadLanes(segment: RoadSegment): List<RoadSegmentLane> {
        if (segment.lanes.isNotEmpty()) return segment.lanes.sortedBy { it.index }
        val count = segment.laneCount.coerceIn(1, 16)
        val laneWidth = segment.widthMeters / count
        return (0 until count).map { index ->
            RoadSegmentLane(
                id = "${segment.id}-lane-${index + 1}",
                index = index + 1,
                widthMeters = laneWidth,
                travelDirection = if (index < (count + 1) / 2)
                    LaneTravelDirection.ALONG_CENTERLINE else LaneTravelDirection.AGAINST_CENTERLINE
            )
        }
    }

    fun setRoadLaneCount(intersection: Intersection, segmentId: String, count: Int): Intersection {
        require(count in 1..16) { "Количество полос должно быть от 1 до 16" }
        return intersection.copy(roadSegments = intersection.roadSegments.map { segment ->
            if (segment.id != segmentId) segment else {
                val old = effectiveRoadLanes(segment)
                val resized = when {
                    count <= old.size -> old.take(count).mapIndexed { i, lane -> lane.copy(index = i + 1) }
                    else -> old + (old.size until count).map { i ->
                        val template = old.lastOrNull()
                        RoadSegmentLane(index = i + 1,
                            widthMeters = template?.widthMeters ?: (segment.widthMeters / count),
                            travelDirection = template?.travelDirection ?: LaneTravelDirection.ALONG_CENTERLINE,
                            allowedMovements = template?.allowedMovements ?: setOf(MovementKind.THROUGH))
                    }
                }
                segment.copy(laneCount = count, lanes = resized, widthMeters = resized.sumOf { it.widthMeters })
            }
        })
    }

    fun changeRoadLaneWidth(intersection: Intersection, segmentId: String, laneId: String, deltaMeters: Double): Intersection {
        require(deltaMeters.isFinite())
        return intersection.copy(roadSegments = intersection.roadSegments.map { segment ->
            if (segment.id != segmentId) segment else {
                val lanes = effectiveRoadLanes(segment).map { lane ->
                    if (lane.id == laneId) lane.copy(widthMeters = (lane.widthMeters + deltaMeters).coerceIn(2.5, 5.0)) else lane
                }
                segment.copy(lanes = lanes, laneCount = lanes.size, widthMeters = lanes.sumOf { it.widthMeters })
            }
        })
    }

    fun changeRoadSegmentWidth(intersection: Intersection, segmentId: String, deltaMeters: Double): Intersection {
        require(deltaMeters.isFinite())
        return intersection.copy(roadSegments = intersection.roadSegments.map { segment ->
            if (segment.id != segmentId) segment else {
                val lanes = effectiveRoadLanes(segment)
                val perLaneDelta = deltaMeters / lanes.size
                val changed = lanes.map { lane ->
                    lane.copy(widthMeters = (lane.widthMeters + perLaneDelta).coerceIn(2.5, 5.0))
                }
                segment.copy(lanes = changed, laneCount = changed.size, widthMeters = changed.sumOf { it.widthMeters })
            }
        })
    }

    fun setRoadLaneDirection(intersection: Intersection, segmentId: String, laneId: String, direction: LaneTravelDirection): Intersection =
        intersection.copy(roadSegments = intersection.roadSegments.map { segment ->
            if (segment.id != segmentId) segment else segment.copy(lanes = effectiveRoadLanes(segment).map { lane ->
                if (lane.id == laneId) lane.copy(travelDirection = direction) else lane
            })
        })

    fun setRoadLaneMovements(intersection: Intersection, segmentId: String, laneId: String, movements: Set<MovementKind>): Intersection {
        require(movements.isNotEmpty()) { "Для полосы должно быть задано хотя бы одно направление" }
        return intersection.copy(roadSegments = intersection.roadSegments.map { segment ->
            if (segment.id != segmentId) segment else segment.copy(lanes = effectiveRoadLanes(segment).map { lane ->
                if (lane.id == laneId) lane.copy(allowedMovements = movements) else lane
            })
        })
    }

    fun validate(intersection: Intersection): List<String> = buildList {
        if (intersection.name.isBlank()) add("Не задано название перекрёстка")
        if (intersection.approaches.size < 2 && intersection.roadSegments.isEmpty()) {
            add("Недостаточно дорожных подходов или дорожных сегментов")
        }
        if (intersection.approaches.map { it.id }.distinct().size != intersection.approaches.size) {
            add("Идентификаторы подходов должны быть уникальными")
        }
        if (intersection.roadSegments.map { it.id }.distinct().size != intersection.roadSegments.size) {
            add("Идентификаторы дорожных сегментов должны быть уникальными")
        }
        if (intersection.junctionNodes.map { it.id }.distinct().size != intersection.junctionNodes.size) {
            add("Идентификаторы узлов соединения должны быть уникальными")
        }
        intersection.junctionNodes.forEach { node ->
            if (!node.point.xMeters.isFinite() || !node.point.yMeters.isFinite()) add("Узел соединения содержит некорректные координаты")
            if (node.connectedSegmentIds.any { id -> intersection.roadSegments.none { it.id == id } }) add("Узел ссылается на отсутствующий дорожный сегмент")
        }
        if (intersection.laneConnections.map { it.id }.distinct().size != intersection.laneConnections.size) {
            add("Идентификаторы связей полос должны быть уникальными")
        }
        intersection.laneConnections.forEach { connection ->
            val source = intersection.approaches.firstOrNull { it.id == connection.fromApproachId }
                ?.lanes?.firstOrNull { it.id == connection.fromLaneId }
            val target = intersection.approaches.firstOrNull { it.id == connection.toApproachId }
                ?.lanes?.firstOrNull { it.id == connection.toLaneId }
            if (source == null) add("Связь полосы ссылается на отсутствующую въездную полосу")
            else if (source.flowDirection != LaneFlowDirection.INTO_INTERSECTION) add("Исходная полоса связи не направлена к перекрёстку")
            if (target == null) add("Связь полосы ссылается на отсутствующую выездную полосу")
            else if (target.flowDirection != LaneFlowDirection.OUT_OF_INTERSECTION) add("Целевая полоса связи не направлена от перекрёстка")
            if (source != null) {
                val from = intersection.approaches.firstOrNull { it.id == connection.fromApproachId }
                val to = intersection.approaches.firstOrNull { it.id == connection.toApproachId }
                if (from != null && to != null) {
                    val movement = classifyMovement(from, to)
                    if (movement !in source.allowedMovements) {
                        add("Связь полосы использует запрещённый манёвр «${movementLabel(movement)}»")
                    }
                }
            }
        }
        intersection.signalPlan?.let { plan ->
            val connectionIds = intersection.laneConnections.map { it.id }.toSet()
            val assignedIds = plan.phases.flatMap { it.greenConnectionIds }
            if (plan.phases.map { it.id }.distinct().size != plan.phases.size) add("Идентификаторы светофорных фаз должны быть уникальными")
            plan.phases.forEach { phase ->
                if (phase.name.isBlank()) add("У светофорной фазы отсутствует название")
                if (phase.greenSeconds <= 0) add("Длительность зелёного сигнала должна быть положительной")
                if (phase.yellowSeconds < 0 || phase.allRedSeconds < 0) add("Интервалы жёлтого и общего красного не могут быть отрицательными")
                if (phase.greenConnectionIds.isEmpty()) add("Фаза «${phase.name}» не содержит разрешённых движений")
                if (phase.greenConnectionIds.any { it !in connectionIds }) add("Фаза «${phase.name}» ссылается на несуществующую связь полос")
            }
            if (assignedIds.size != assignedIds.distinct().size) add("Одно движение назначено зелёным сразу в нескольких фазах")
        }
        if (intersection.safetyIslands.map { it.id }.distinct().size != intersection.safetyIslands.size) {
            add("Идентификаторы островков безопасности должны быть уникальными")
        }
        intersection.safetyIslands.forEach { island ->
            if (island.name.isBlank()) add("У островка безопасности отсутствует название")
            if (island.boundary.size < 3) add("Контур островка «${island.name}» должен содержать минимум три точки")
            if (island.boundary.any { !it.xMeters.isFinite() || !it.yMeters.isFinite() }) add("Контур островка «${island.name}» содержит некорректные координаты")
        }
        intersection.roadSegments.forEach { segment ->
            if (segment.name.isBlank()) add("У дорожного сегмента отсутствует название")
            if (segment.centerline.size < 2) add("Сегмент «${segment.name}» должен содержать минимум две точки")
            if (!segment.widthMeters.isFinite() || segment.widthMeters !in 2.5..60.0) {
                add("Некорректная ширина сегмента «${segment.name}»")
            }
            if (segment.laneCount !in 1..16) add("Некорректное количество полос сегмента «${segment.name}»")
            if (segment.lanes.isNotEmpty()) {
                if (segment.lanes.size != segment.laneCount) add("Количество индивидуальных полос не совпадает с параметром сегмента «${segment.name}»")
                if (segment.lanes.map { it.id }.distinct().size != segment.lanes.size) add("Повторяются идентификаторы полос сегмента «${segment.name}»")
                if (segment.lanes.map { it.index }.distinct().size != segment.lanes.size) add("Повторяются номера полос сегмента «${segment.name}»")
                segment.lanes.forEach { lane ->
                    if (!lane.widthMeters.isFinite() || lane.widthMeters !in 2.5..5.0) add("Некорректная ширина полосы ${lane.index} сегмента «${segment.name}»")
                    if (lane.allowedMovements.isEmpty()) add("Для полосы ${lane.index} сегмента «${segment.name}» не заданы разрешённые манёвры")
                }
                if (kotlin.math.abs(segment.lanes.sumOf { it.widthMeters } - segment.widthMeters) > 0.05) add("Суммарная ширина полос не совпадает с шириной сегмента «${segment.name}»")
            }
            val arcRadiusMeters = segment.arcRadiusMeters
            if (arcRadiusMeters != null && (!arcRadiusMeters.isFinite() || arcRadiusMeters <= 0.0)) {
                add("Некорректный радиус дуги сегмента «${segment.name}»")
            }
            if (segment.centerline.any { !it.xMeters.isFinite() || !it.yMeters.isFinite() }) {
                add("Сегмент «${segment.name}» содержит некорректные координаты")
            }
        }
        intersection.approaches.forEach { approach ->
            if (approach.name.isBlank()) add("У одного из подходов отсутствует название")
            if (!approach.headingDegrees.isFinite()) add("Некорректный азимут подхода «${approach.name}»")
            if (approach.lanes.isEmpty()) add("На подходе «${approach.name}» нет полос")
            if (approach.lanes.map { it.id }.distinct().size != approach.lanes.size) {
                add("На подходе «${approach.name}» повторяются идентификаторы полос")
            }
            approach.lanes.forEach { lane ->
                if (lane.widthMeters !in MIN_LANE_WIDTH_METERS..MAX_LANE_WIDTH_METERS) {
                    add("Некорректная ширина полосы ${lane.index} на подходе «${approach.name}»")
                }
                if (lane.allowedMovements.isEmpty()) {
                    add("Для полосы ${lane.index} на подходе «${approach.name}» не задано направление")
                }
            }
        }
    }

    /**
     * Keep lane-to-lane movements and their signal assignments consistent when
     * a lane is resized, reversed, or its permitted turns are edited.
     */
    private fun pruneInvalidConnections(
        intersection: Intersection,
        approaches: List<Approach>
    ): Intersection {
        val validConnections = intersection.laneConnections.filter { connection ->
            val from = approaches.firstOrNull { it.id == connection.fromApproachId }
            val to = approaches.firstOrNull { it.id == connection.toApproachId }
            val source = from?.lanes?.firstOrNull { it.id == connection.fromLaneId }
            val target = to?.lanes?.firstOrNull { it.id == connection.toLaneId }
            source != null && target != null && from != null && to != null &&
                source.flowDirection == LaneFlowDirection.INTO_INTERSECTION &&
                target.flowDirection == LaneFlowDirection.OUT_OF_INTERSECTION &&
                classifyMovement(from, to) in source.allowedMovements
        }
        val validIds = validConnections.map { it.id }.toSet()
        val updatedPlan = intersection.signalPlan?.copy(
            phases = intersection.signalPlan.phases.map { phase ->
                phase.copy(greenConnectionIds = phase.greenConnectionIds.intersect(validIds))
            }
        )
        return intersection.copy(
            approaches = approaches,
            laneConnections = validConnections,
            signalPlan = updatedPlan
        )
    }

    private fun classifyMovement(from: Approach, to: Approach): MovementKind {
        val incomingHeading = normalizeDegrees(from.headingDegrees + 180.0)
        val delta = signedAngle(incomingHeading, normalizeDegrees(to.headingDegrees))
        return when {
            kotlin.math.abs(delta) <= 30.0 -> MovementKind.THROUGH
            kotlin.math.abs(delta) >= 150.0 -> MovementKind.U_TURN
            delta > 0.0 -> MovementKind.RIGHT
            else -> MovementKind.LEFT
        }
    }

    private fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

    private fun signedAngle(from: Double, to: Double): Double =
        ((to - from + 540.0) % 360.0) - 180.0

    private fun movementLabel(movement: MovementKind): String = when (movement) {
        MovementKind.THROUGH -> "прямо"
        MovementKind.LEFT -> "налево"
        MovementKind.RIGHT -> "направо"
        MovementKind.U_TURN -> "разворот"
        MovementKind.TRANSIT_ONLY -> "общественный транспорт"
    }

    private fun resizeLanes(existing: List<Lane>, count: Int): List<Lane> =
        when {
            count < existing.size -> existing.take(count).mapIndexed { index, lane ->
                lane.copy(index = index + 1)
            }
            count > existing.size -> existing + (existing.size until count).map { index ->
                val template = existing.lastOrNull()
                Lane(
                    index = index + 1,
                    widthMeters = template?.widthMeters ?: 3.5,
                    allowedMovements = template?.allowedMovements ?: setOf(MovementKind.THROUGH),
                    flowDirection = template?.flowDirection ?: LaneFlowDirection.INTO_INTERSECTION
                )
            }
            else -> existing.mapIndexed { index, lane -> lane.copy(index = index + 1) }
        }
}
