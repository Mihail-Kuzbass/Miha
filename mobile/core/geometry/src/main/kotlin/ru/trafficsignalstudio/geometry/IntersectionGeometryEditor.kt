package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.Approach
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.Lane

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
        return intersection.copy(
            approaches = intersection.approaches.map { approach ->
                if (approach.id != approachId) approach
                else approach.copy(lanes = resizeLanes(approach.lanes, count))
            }
        )
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
                    allowedMovements = template?.allowedMovements ?: emptySet()
                )
            }
            else -> existing.mapIndexed { index, lane -> lane.copy(index = index + 1) }
        }
}
