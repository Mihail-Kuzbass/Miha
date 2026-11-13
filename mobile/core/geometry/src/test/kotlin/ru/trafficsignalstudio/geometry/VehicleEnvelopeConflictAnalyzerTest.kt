package ru.trafficsignalstudio.geometry

import org.junit.Assert.*
import org.junit.Test
import ru.trafficsignalstudio.model.MovementKind
import ru.trafficsignalstudio.model.Point2D

class VehicleEnvelopeConflictAnalyzerTest {
    private fun path(id: String, points: List<Point2D>, width: Double = 2.5) =
        MovementTrajectory(id, "from-" + id, "lane-" + id, "to-" + id, "out-" + id,
            MovementKind.THROUGH, points, vehicleWidthMeters = width)

    @Test fun detectsEnvelopeOverlapWithoutCenterlineIntersection() {
        val a = path("a", listOf(Point2D(-5.0, 0.0), Point2D(5.0, 0.0)))
        val b = path("b", listOf(Point2D(0.0, 2.0), Point2D(5.0, 2.0)))
        val result = VehicleEnvelopeConflictAnalyzer.analyze(listOf(a, b))
        assertEquals(1, result.size)
        assertTrue(result.single().minimumCenterlineDistanceMeters < 2.5)
        assertFalse(result.single().centerlinesIntersect)
    }

    @Test fun doesNotReportSeparatedEnvelopes() {
        val a = path("a", listOf(Point2D(-5.0, 0.0), Point2D(5.0, 0.0)))
        val b = path("b", listOf(Point2D(-5.0, 3.0), Point2D(5.0, 3.0)))
        assertTrue(VehicleEnvelopeConflictAnalyzer.analyze(listOf(a, b)).isEmpty())
    }
}
