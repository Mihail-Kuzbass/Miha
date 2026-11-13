package ru.trafficsignalstudio.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

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
    fun interchangeTemplatesContainIndependentRoadSegments() {
        val diamond = IntersectionTemplates.diamondInterchange()
        val cloverleaf = IntersectionTemplates.cloverleafInterchange()

        assertEquals(4, diamond.approaches.size)
        assertTrue(diamond.roadSegments.any { it.elevationLevel > 0 })
        assertTrue(diamond.roadSegments.any { it.isRamp })
        assertTrue(cloverleaf.roadSegments.size > diamond.roadSegments.size)
        assertTrue(IntersectionGeometryEditor.validate(diamond).isEmpty())
    }
}
