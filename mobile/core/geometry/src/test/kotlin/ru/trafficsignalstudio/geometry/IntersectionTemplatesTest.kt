package ru.trafficsignalstudio.geometry

import org.junit.Assert.assertEquals
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
}
