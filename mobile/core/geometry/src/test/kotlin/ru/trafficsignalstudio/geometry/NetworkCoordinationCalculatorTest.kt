package ru.trafficsignalstudio.geometry

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.trafficsignalstudio.model.CoordinationDirection
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.IntersectionType
import ru.trafficsignalstudio.model.NetworkCoordinationSettings
import ru.trafficsignalstudio.model.NetworkTrafficLink
import ru.trafficsignalstudio.model.RoadNetwork

class NetworkCoordinationCalculatorTest {
    private fun node(id: String) = Intersection(
        id = id,
        name = id,
        type = IntersectionType.FOUR_LEG,
        approaches = emptyList()
    )

    private fun network() = RoadNetwork(
        intersections = listOf(node("A"), node("B"), node("C")),
        trafficLinks = listOf(
            NetworkTrafficLink(
                fromIntersectionId = "A",
                fromMovementConnectionId = "out-A",
                toIntersectionId = "B",
                toMovementConnectionId = "in-B",
                distanceMeters = 1000.0
            )
        )
    )

    @Test fun forwardOffsetsUseDistanceAndProgressionSpeed() {
        val offsets = NetworkCoordinationCalculator.calculateOffsets(
            network(),
            NetworkCoordinationSettings(
                enabled = true,
                cycleLengthSeconds = 90,
                progressionSpeedKmh = 36.0,
                referenceIntersectionId = "A",
                direction = CoordinationDirection.FORWARD
            )
        )
        assertEquals(0, offsets["A"])
        assertEquals(10, offsets["B"])
        assertEquals(0, offsets["C"])
    }

    @Test fun reverseCoordinationTraversesLinksBackwards() {
        val offsets = NetworkCoordinationCalculator.calculateOffsets(
            network(),
            NetworkCoordinationSettings(
                enabled = true,
                cycleLengthSeconds = 90,
                progressionSpeedKmh = 36.0,
                referenceIntersectionId = "B",
                direction = CoordinationDirection.REVERSE
            )
        )
        assertEquals(0, offsets["B"])
        assertEquals(10, offsets["A"])
    }


    @Test fun shortestTravelTimePathWinsWhenSeveralRoutesExist() {
        val multiPath = RoadNetwork(
            intersections = listOf(node("A"), node("B"), node("C")),
            trafficLinks = listOf(
                NetworkTrafficLink("direct", "A", "out-A", "B", "in-B", distanceMeters = 900.0),
                NetworkTrafficLink("first-leg", "A", "out-A2", "C", "in-C", distanceMeters = 180.0),
                NetworkTrafficLink("second-leg", "C", "out-C", "B", "in-B2", distanceMeters = 180.0)
            )
        )
        val offsets = NetworkCoordinationCalculator.calculateOffsets(
            multiPath,
            NetworkCoordinationSettings(
                cycleLengthSeconds = 90,
                progressionSpeedKmh = 36.0,
                referenceIntersectionId = "A"
            )
        )
        assertEquals(36, offsets["B"])
        assertEquals(18, offsets["C"])
    }

    @Test fun offsetsWrapAtTheCommonCycleLength() {
        val offsets = NetworkCoordinationCalculator.calculateOffsets(
            network(),
            NetworkCoordinationSettings(
                enabled = true,
                cycleLengthSeconds = 60,
                progressionSpeedKmh = 36.0,
                referenceIntersectionId = "A"
            )
        )
        assertEquals(40, offsets["B"])
    }
}
