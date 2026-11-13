package ru.trafficsignalstudio.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.trafficsignalstudio.model.MovementKind

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

    @Test(expected = IllegalArgumentException::class)
    fun laneCountOutsideSupportedRangeIsRejected() {
        val intersection = IntersectionTemplates.fourLeg()
        IntersectionGeometryEditor.setLaneCount(intersection, intersection.approaches.first().id, 9)
    }
}
