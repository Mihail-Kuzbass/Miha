package ru.trafficsignalstudio.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.trafficsignalstudio.model.MovementKind
import ru.trafficsignalstudio.model.LaneFlowDirection
import ru.trafficsignalstudio.model.LaneConnection

class IntersectionGeometryEditorTest {
    @Test
    fun setLaneCountAddsAndRemovesLanes() {
        val intersection = IntersectionTemplates.fourLeg(lanesPerApproach = 2)
        val approach = intersection.approaches.first()

        val expanded = IntersectionGeometryEditor.setLaneCount(intersection, approach.id, 4)
        assertEquals(4, expanded.approaches.first().lanes.size)
        assertEquals(listOf(1, 2, 3, 4), expanded.approaches.first().lanes.map { it.index })

        val reduced = IntersectionGeometryEditor.setLaneCount(expanded, approach.id, 1)
        assertEquals(1, reduced.approaches.first().lanes.size)
        assertEquals(2, reduced.approaches[1].lanes.size)
    }

    @Test
    fun laneWidthIsClampedToEngineeringLimits() {
        val intersection = IntersectionTemplates.fourLeg()
        val approach = intersection.approaches.first()
        val lane = approach.lanes.first()

        val narrowed = IntersectionGeometryEditor.changeLaneWidth(
            intersection, approach.id, lane.id, -10.0
        )
        assertEquals(2.5, narrowed.approaches.first().lanes.first().widthMeters, 0.0001)

        val widened = IntersectionGeometryEditor.changeLaneWidth(
            intersection, approach.id, lane.id, 10.0
        )
        assertEquals(5.0, widened.approaches.first().lanes.first().widthMeters, 0.0001)
    }

    @Test
    fun standardTemplatePassesGeometryValidation() {
        val errors = IntersectionGeometryEditor.validate(IntersectionTemplates.fourLeg())
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test
    fun laneMovementsCanBeConfiguredIndependently() {
        val intersection = IntersectionTemplates.fourLeg(lanesPerApproach = 2)
        val approach = intersection.approaches.first()
        val firstLane = approach.lanes[0]
        val secondLane = approach.lanes[1]

        val configured = IntersectionGeometryEditor.setLaneMovements(
            intersection, approach.id, firstLane.id,
            setOf(MovementKind.THROUGH, MovementKind.RIGHT)
        )

        assertEquals(setOf(MovementKind.THROUGH, MovementKind.RIGHT),
            configured.approaches.first().lanes[0].allowedMovements)
        assertEquals(secondLane.allowedMovements, configured.approaches.first().lanes[1].allowedMovements)
        assertEquals(intersection.approaches[1], configured.approaches[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun laneCannotHaveNoAllowedMovements() {
        val intersection = IntersectionTemplates.fourLeg()
        val approach = intersection.approaches.first()
        val lane = approach.lanes.first()
        IntersectionGeometryEditor.setLaneMovements(intersection, approach.id, lane.id, emptySet())
    }

    @Test
    fun laneFlowDirectionCanBeChangedIndependently() {
        val intersection = IntersectionTemplates.fourLeg(lanesPerApproach = 2)
        val approach = intersection.approaches.first()
        val lane = approach.lanes.first()

        val changed = IntersectionGeometryEditor.setLaneFlowDirection(
            intersection, approach.id, lane.id, LaneFlowDirection.OUT_OF_INTERSECTION
        )

        assertEquals(LaneFlowDirection.OUT_OF_INTERSECTION,
            changed.approaches.first().lanes.first().flowDirection)
        assertEquals(approach.lanes[1], changed.approaches.first().lanes[1])
        assertEquals(intersection.approaches[1], changed.approaches[1])
    }

    @Test
    fun newLanesInheritFlowDirectionFromExistingLane() {
        val intersection = IntersectionTemplates.fourLeg(lanesPerApproach = 1)
        val approach = intersection.approaches.first()
        val outbound = IntersectionGeometryEditor.setLaneFlowDirection(
            intersection, approach.id, approach.lanes.first().id, LaneFlowDirection.OUT_OF_INTERSECTION
        )
        val expanded = IntersectionGeometryEditor.setLaneCount(outbound, approach.id, 2)
        assertEquals(LaneFlowDirection.OUT_OF_INTERSECTION, expanded.approaches.first().lanes[1].flowDirection)
    }

    @Test
    fun laneConnectionsRequireInboundSourceAndOutboundTarget() {
        var intersection = IntersectionTemplates.fourLeg(lanesPerApproach = 1)
        val sourceApproach = intersection.approaches[0]
        val targetApproach = intersection.approaches[1]
        val sourceLane = sourceApproach.lanes.first()
        val targetLane = targetApproach.lanes.first()
        intersection = IntersectionGeometryEditor.setLaneFlowDirection(
            intersection, targetApproach.id, targetLane.id, LaneFlowDirection.OUT_OF_INTERSECTION
        )

        val connection = LaneConnection(
            fromApproachId = sourceApproach.id,
            fromLaneId = sourceLane.id,
            toApproachId = targetApproach.id,
            toLaneId = targetLane.id
        )
        val connected = IntersectionGeometryEditor.addLaneConnection(intersection, connection)
        assertEquals(1, connected.laneConnections.size)
        assertTrue(IntersectionGeometryEditor.validate(connected).isEmpty())

        val removed = IntersectionGeometryEditor.removeLaneConnection(connected, connection.id)
        assertTrue(removed.laneConnections.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun laneConnectionRejectsOutboundSource() {
        var intersection = IntersectionTemplates.fourLeg(lanesPerApproach = 1)
        val source = intersection.approaches[0]
        val target = intersection.approaches[1]
        intersection = IntersectionGeometryEditor.setLaneFlowDirection(
            intersection, source.id, source.lanes.first().id, LaneFlowDirection.OUT_OF_INTERSECTION
        )
        intersection = IntersectionGeometryEditor.setLaneFlowDirection(
            intersection, target.id, target.lanes.first().id, LaneFlowDirection.OUT_OF_INTERSECTION
        )
        IntersectionGeometryEditor.addLaneConnection(intersection, LaneConnection(
            fromApproachId = source.id, fromLaneId = source.lanes.first().id,
            toApproachId = target.id, toLaneId = target.lanes.first().id
        ))
    }

    @Test
    fun freeformRoadLanesCanBeResizedAndConfiguredIndependently() {
        val road = ru.trafficsignalstudio.model.RoadSegment(
            name = "Свободная дорога",
            centerline = listOf(ru.trafficsignalstudio.model.Point2D(0.0, 0.0), ru.trafficsignalstudio.model.Point2D(20.0, 0.0)),
            widthMeters = 7.0,
            laneCount = 2
        )
        val intersection = ru.trafficsignalstudio.model.Intersection(
            name = "Тест",
            type = ru.trafficsignalstudio.model.IntersectionType.CUSTOM,
            approaches = emptyList(),
            roadSegments = listOf(road)
        )
        val expanded = IntersectionGeometryEditor.setRoadLaneCount(intersection, road.id, 3)
        val lanes = expanded.roadSegments.single().lanes
        assertEquals(3, lanes.size)
        assertEquals(10.5, expanded.roadSegments.single().widthMeters, 0.0001)

        val configured = IntersectionGeometryEditor.setRoadLaneDirection(
            expanded, road.id, lanes[1].id, ru.trafficsignalstudio.model.LaneTravelDirection.AGAINST_CENTERLINE
        )
        val configuredLane = configured.roadSegments.single().lanes[1]
        assertEquals(ru.trafficsignalstudio.model.LaneTravelDirection.AGAINST_CENTERLINE, configuredLane.travelDirection)

        val narrowed = IntersectionGeometryEditor.changeRoadLaneWidth(
            configured, road.id, configuredLane.id, -0.25
        )
        assertEquals(10.25, narrowed.roadSegments.single().widthMeters, 0.0001)
        assertEquals(3, narrowed.roadSegments.single().laneCount)
    }

    @Test
    fun legacyRoadSegmentsReceiveStableSynthesizedLaneIds() {
        val road = ru.trafficsignalstudio.model.RoadSegment(
            name = "Legacy",
            centerline = listOf(ru.trafficsignalstudio.model.Point2D(0.0, 0.0), ru.trafficsignalstudio.model.Point2D(10.0, 0.0)),
            widthMeters = 7.0,
            laneCount = 2
        )
        val first = IntersectionGeometryEditor.effectiveRoadLanes(road)
        val second = IntersectionGeometryEditor.effectiveRoadLanes(road)
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(listOf(3.5, 3.5), first.map { it.widthMeters })
        assertEquals(ru.trafficsignalstudio.model.LaneTravelDirection.ALONG_CENTERLINE, first[0].travelDirection)
        assertEquals(ru.trafficsignalstudio.model.LaneTravelDirection.AGAINST_CENTERLINE, first[1].travelDirection)
    }

    @Test(expected = IllegalArgumentException::class)
    fun laneCountOutsideSupportedRangeIsRejected() {
        val intersection = IntersectionTemplates.fourLeg()
        IntersectionGeometryEditor.setLaneCount(intersection, intersection.approaches.first().id, 9)
    }
}
