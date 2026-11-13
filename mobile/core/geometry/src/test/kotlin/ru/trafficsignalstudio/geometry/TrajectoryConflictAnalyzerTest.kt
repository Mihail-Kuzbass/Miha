package ru.trafficsignalstudio.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.IntersectionType
import ru.trafficsignalstudio.model.Point2D
import ru.trafficsignalstudio.model.RoadSegment

class TrajectoryConflictAnalyzerTest {
    @Test
    fun crossingTwoLaneRoadsProduceLaneLevelConflicts() {
        val intersection = Intersection(
            name = "Crossing",
            type = IntersectionType.CUSTOM,
            approaches = emptyList(),
            roadSegments = listOf(
                RoadSegment(name = "East-West", centerline = listOf(Point2D(-10.0, 0.0), Point2D(10.0, 0.0)), widthMeters = 7.0, laneCount = 2),
                RoadSegment(name = "North-South", centerline = listOf(Point2D(0.0, -10.0), Point2D(0.0, 10.0)), widthMeters = 7.0, laneCount = 2)
            )
        )
        val conflicts = TrajectoryConflictAnalyzer.analyze(intersection)
        assertEquals(4, conflicts.size)
        assertTrue(conflicts.all { it.kind == TrajectoryConflictKind.CROSSING })
    }

    @Test
    fun gradeSeparatedRoadsDoNotConflict() {
        val intersection = Intersection(
            name = "Bridge",
            type = IntersectionType.CUSTOM,
            approaches = emptyList(),
            roadSegments = listOf(
                RoadSegment(name = "Ground", centerline = listOf(Point2D(-10.0, 0.0), Point2D(10.0, 0.0)), widthMeters = 7.0, laneCount = 2),
                RoadSegment(name = "Bridge", centerline = listOf(Point2D(0.0, -10.0), Point2D(0.0, 10.0)), widthMeters = 7.0, laneCount = 2, elevationLevel = 1)
            )
        )
        assertTrue(TrajectoryConflictAnalyzer.analyze(intersection).isEmpty())
    }

    @Test
    fun nonIntersectingRoadSegmentsProduceNoConflicts() {
        val intersection = Intersection(
            name = "Separate",
            type = IntersectionType.CUSTOM,
            approaches = emptyList(),
            roadSegments = listOf(
                RoadSegment(name = "A", centerline = listOf(Point2D(0.0, 0.0), Point2D(10.0, 0.0))),
                RoadSegment(name = "B", centerline = listOf(Point2D(0.0, 5.0), Point2D(10.0, 5.0)))
            )
        )
        assertTrue(TrajectoryConflictAnalyzer.analyze(intersection).isEmpty())
    }
}
