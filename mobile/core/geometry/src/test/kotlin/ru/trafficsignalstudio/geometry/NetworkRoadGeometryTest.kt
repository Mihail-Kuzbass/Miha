package ru.trafficsignalstudio.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.trafficsignalstudio.model.Point2D

class NetworkRoadGeometryTest {
    @Test fun interpolatesPositionAlongStraightRoad() {
        val pose = NetworkRoadGeometry.poseAt(
            listOf(Point2D(0.0, 0.0), Point2D(100.0, 0.0)),
            distanceMeters = 25.0,
            laneIndex = 0,
            laneCount = 1,
            laneWidthMeters = 3.5
        )
        assertEquals(25.0, pose.point.xMeters, 1e-6)
        assertEquals(0.0, pose.point.yMeters, 1e-6)
        assertEquals(0.0, pose.headingRadians, 1e-6)
    }

    @Test fun followsPolylineBendAndUpdatesHeading() {
        val path = listOf(Point2D(0.0, 0.0), Point2D(10.0, 0.0), Point2D(10.0, 10.0))
        val pose = NetworkRoadGeometry.poseAt(path, 15.0, 0, 1, 3.5)
        assertEquals(10.0, pose.point.xMeters, 1e-6)
        assertEquals(5.0, pose.point.yMeters, 1e-6)
        assertEquals(Math.PI / 2.0, pose.headingRadians, 1e-6)
    }

    @Test fun offsetsParallelLanesWithoutChangingLongitudinalPosition() {
        val path = listOf(Point2D(0.0, 0.0), Point2D(100.0, 0.0))
        val right = NetworkRoadGeometry.poseAt(path, 20.0, 0, 3, 3.5)
        val middle = NetworkRoadGeometry.poseAt(path, 20.0, 1, 3, 3.5)
        val left = NetworkRoadGeometry.poseAt(path, 20.0, 2, 3, 3.5)
        assertEquals(20.0, right.point.xMeters, 1e-6)
        assertEquals(-3.5, right.point.yMeters, 1e-6)
        assertEquals(0.0, middle.point.yMeters, 1e-6)
        assertEquals(3.5, left.point.yMeters, 1e-6)
        assertTrue(NetworkRoadGeometry.length(path) == 100.0)
    }
}
