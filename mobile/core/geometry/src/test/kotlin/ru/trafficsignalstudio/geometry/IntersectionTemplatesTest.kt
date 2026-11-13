package ru.trafficsignalstudio.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows
import ru.trafficsignalstudio.model.Point2D
import ru.trafficsignalstudio.model.RoadSegment
import ru.trafficsignalstudio.model.RoadSegmentKind

class IntersectionTemplatesTest {
    @Test
    fun fourLegTemplateHasFourApproaches() {
        val intersection = IntersectionTemplates.fourLeg(lanesPerApproach = 3)
        assertEquals(4, intersection.approaches.size)
        assertEquals(3, intersection.approaches.first().lanes.size)
    }

    @Test
    fun laneCountOutsideSupportedRangeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            IntersectionTemplates.fourLeg(lanesPerApproach = 9)
        }
    }

    @Test
    fun nonStandardAtGradeTemplatesHaveExpectedApproachCounts() {
        assertEquals(3, IntersectionTemplates.yJunction().approaches.size)
        assertEquals(5, IntersectionTemplates.fiveLeg().approaches.size)
        assertEquals(6, IntersectionTemplates.sixLeg().approaches.size)
        assertEquals(4, IntersectionTemplates.roundabout().approaches.size)
    }

    @Test
    fun freehandRoadSegmentKindsAreAcceptedAndValidated() {
        val base = IntersectionTemplates.fourLeg()
        val arc = RoadSegment(
            name = "Дуговой съезд",
            centerline = listOf(Point2D(-20.0, 0.0), Point2D(0.0, 20.0), Point2D(20.0, 0.0)),
            kind = RoadSegmentKind.ARC
        )
        val bridge = RoadSegment(
            name = "Путепровод",
            centerline = listOf(Point2D(-30.0, 10.0), Point2D(30.0, 10.0)),
            elevationLevel = 1,
            kind = RoadSegmentKind.BRIDGE
        )
        val edited = base.copy(roadSegments = listOf(arc, bridge))
        assertTrue(IntersectionGeometryEditor.validate(edited).isEmpty())
        assertEquals(RoadSegmentKind.ARC, edited.roadSegments[0].kind)
        assertEquals(1, edited.roadSegments[1].elevationLevel)
    }

    @Test
    fun interchangeTemplatesContainIndependentRoadSegments() {
        val diamond = IntersectionTemplates.diamondInterchange()
        val cloverleaf = IntersectionTemplates.cloverleafInterchange()

        assertTrue(diamond.approaches.isEmpty())
        assertTrue(diamond.roadSegments.any { it.elevationLevel > 0 })
        assertTrue(diamond.roadSegments.any { it.isRamp })
        assertTrue(cloverleaf.roadSegments.size > diamond.roadSegments.size)
        assertTrue(IntersectionGeometryEditor.validate(diamond).isEmpty())
    }
}
