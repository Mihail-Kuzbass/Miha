package ru.trafficsignalstudio.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.trafficsignalstudio.model.CoordinationDirection
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.IntersectionType
import ru.trafficsignalstudio.model.LaneConnection
import ru.trafficsignalstudio.model.NetworkCoordinationSettings
import ru.trafficsignalstudio.model.NetworkTrafficLink
import ru.trafficsignalstudio.model.RoadNetwork
import ru.trafficsignalstudio.model.SignalPhase
import ru.trafficsignalstudio.model.SignalPlan

class NetworkCoordinationCalculatorTest {
    private fun node(id: String, vararg movements: String) = Intersection(
        id = id,
        name = id,
        type = IntersectionType.FOUR_LEG,
        approaches = emptyList(),
        laneConnections = (movements.toList() + "other-$id").mapIndexed { index, movement ->
            LaneConnection(
                id = movement,
                fromApproachId = "from-$index",
                fromLaneId = "from-lane-$index",
                toApproachId = "to-$index",
                toLaneId = "to-lane-$index"
            )
        },
        signalPlan = SignalPlan(
            phases = listOf(
                SignalPhase(id = "$id-p1", name = "Координируемая", greenConnectionIds = movements.toSet(),
                    greenSeconds = 20, flashingGreenSeconds = 0, yellowSeconds = 3, allRedSeconds = 2),
                SignalPhase(id = "$id-p2", name = "Другие направления", greenConnectionIds = setOf("other-$id"),
                    greenSeconds = 20, flashingGreenSeconds = 0, yellowSeconds = 3, allRedSeconds = 2)
            )
        )
    )

    private fun settings(
        direction: CoordinationDirection = CoordinationDirection.FORWARD,
        reference: String = "A"
    ) = NetworkCoordinationSettings(
        enabled = true,
        cycleLengthSeconds = 54,
        progressionSpeedKmh = 50.0,
        referenceIntersectionId = reference,
        direction = direction
    )

    @Test fun alignsDownstreamGreenWithActualLinkTravelTime() {
        val network = RoadNetwork(
            intersections = listOf(node("A", "out-A"), node("B", "in-B")),
            trafficLinks = listOf(
                NetworkTrafficLink("AB", "A", "out-A", "B", "in-B", distanceMeters = 100.0, travelSpeedKmh = 36.0)
            )
        )
        val report = NetworkCoordinationCalculator.calculateReport(network, settings())
        assertTrue(report.isValid)
        assertEquals(0, report.offsetsSeconds["A"])
        assertEquals(10, report.offsetsSeconds["B"])
        assertEquals(20.0, report.links.single().progressionBandwidthSeconds, 1e-6)
    }

    @Test fun coordinationUsesPolylineDistanceAndLinkSpeedLimit() {
        val network = RoadNetwork(
            intersections = listOf(node("A", "out-A"), node("B", "in-B")),
            trafficLinks = listOf(
                NetworkTrafficLink(
                    "AB", "A", "out-A", "B", "in-B",
                    distanceMeters = 100.0,
                    travelSpeedKmh = 36.0,
                    centerline = listOf(
                        ru.trafficsignalstudio.model.Point2D(0.0, 0.0),
                        ru.trafficsignalstudio.model.Point2D(100.0, 0.0),
                        ru.trafficsignalstudio.model.Point2D(100.0, 100.0)
                    )
                )
            )
        )
        val report = NetworkCoordinationCalculator.calculateReport(network, settings())
        assertTrue(report.isValid)
        assertEquals(20.0, report.links.single().travelTimeSeconds, 1e-6)
        assertEquals(20, report.offsetsSeconds["B"])
    }

    @Test fun reverseCoordinationSwapsTheMovementPair() {
        val network = RoadNetwork(
            intersections = listOf(node("A", "out-A"), node("B", "in-B")),
            trafficLinks = listOf(
                NetworkTrafficLink("AB", "A", "out-A", "B", "in-B", distanceMeters = 100.0, travelSpeedKmh = 36.0)
            )
        )
        val report = NetworkCoordinationCalculator.calculateReport(network, settings(
            direction = CoordinationDirection.REVERSE, reference = "B"
        ))
        assertTrue(report.isValid)
        assertEquals(0, report.offsetsSeconds["B"])
        assertEquals(10, report.offsetsSeconds["A"])
        assertEquals("B", report.links.single().fromIntersectionId)
        assertEquals("A", report.links.single().toIntersectionId)
    }

    @Test fun rejectsDifferentLocalCyclesInsteadOfStretchingPhaseTimings() {
        val wrongCycle = node("B", "in-B").copy(
            signalPlan = SignalPlan(phases = listOf(
                SignalPhase(id = "b-p1", name = "P1", greenConnectionIds = setOf("in-B"),
                    greenSeconds = 25, flashingGreenSeconds = 0, yellowSeconds = 3, allRedSeconds = 2),
                SignalPhase(id = "b-p2", name = "P2", greenConnectionIds = setOf("other-B"),
                    greenSeconds = 25, flashingGreenSeconds = 0, yellowSeconds = 3, allRedSeconds = 2)
            ))
        )
        val network = RoadNetwork(
            intersections = listOf(node("A", "out-A"), wrongCycle),
            trafficLinks = listOf(NetworkTrafficLink("AB", "A", "out-A", "B", "in-B", distanceMeters = 100.0))
        )
        val report = NetworkCoordinationCalculator.calculateReport(network, settings())
        assertFalse(report.isValid)
        assertTrue(report.diagnostics.any { it.code == CoordinationIssueCode.CYCLE_MISMATCH && it.intersectionId == "B" })
        assertTrue(report.links.isEmpty())
    }

    @Test fun reportsUnreachableNodesAndKeepsReferenceOffsetAtZero() {
        val network = RoadNetwork(
            intersections = listOf(node("A", "out-A"), node("B", "in-B"), node("C", "unused-C")),
            trafficLinks = listOf(NetworkTrafficLink("AB", "A", "out-A", "B", "in-B", distanceMeters = 100.0))
        )
        val report = NetworkCoordinationCalculator.calculateReport(network, settings())
        assertEquals(0, report.offsetsSeconds["A"])
        assertTrue(report.diagnostics.any { it.code == CoordinationIssueCode.UNREACHABLE_INTERSECTION && it.intersectionId == "C" })
    }

    @Test fun longSectionProducesRecommendationWithoutBlockingCalculation() {
        val network = RoadNetwork(
            intersections = listOf(node("A", "out-A"), node("B", "in-B")),
            trafficLinks = listOf(NetworkTrafficLink(
                "AB", "A", "out-A", "B", "in-B", distanceMeters = 1500.0, travelSpeedKmh = 36.0
            ))
        )
        val report = NetworkCoordinationCalculator.calculateReport(network, settings())
        assertTrue(report.isValid)
        assertTrue(report.diagnostics.any {
            it.code == CoordinationIssueCode.LONG_COORDINATION_SECTION && it.isWarning
        })
    }

    @Test fun targetProgressionSpeedIsCappedByTheRoadSpeedLimit() {
        val network = RoadNetwork(
            intersections = listOf(node("A", "out-A"), node("B", "in-B")),
            trafficLinks = listOf(NetworkTrafficLink(
                "AB", "A", "out-A", "B", "in-B", distanceMeters = 100.0, travelSpeedKmh = 18.0
            ))
        )
        val report = NetworkCoordinationCalculator.calculateReport(network, settings())
        assertTrue(report.isValid)
        assertEquals(20.0, report.links.single().travelTimeSeconds, 1e-6)
    }

    @Test fun rejectsNonStandardYellowIntervalInCoordinatedPlan() {
        val nonStandard = node("A", "out-A").copy(signalPlan = SignalPlan(phases = listOf(
            SignalPhase(id = "a-p1", name = "P1", greenConnectionIds = setOf("out-A"),
                greenSeconds = 20, flashingGreenSeconds = 0, yellowSeconds = 2, allRedSeconds = 3),
            SignalPhase(id = "a-p2", name = "P2", greenConnectionIds = setOf("other-A"),
                greenSeconds = 20, flashingGreenSeconds = 0, yellowSeconds = 3, allRedSeconds = 2)
        )))
        val network = RoadNetwork(
            intersections = listOf(nonStandard, node("B", "in-B")),
            trafficLinks = listOf(NetworkTrafficLink("AB", "A", "out-A", "B", "in-B", distanceMeters = 100.0))
        )
        val report = NetworkCoordinationCalculator.calculateReport(network, settings())
        assertFalse(report.isValid)
        assertTrue(report.diagnostics.any { it.code == CoordinationIssueCode.INVALID_SIGNAL_PLAN && it.intersectionId == "A" })
    }
    @Test fun maximizesBandwidthAcrossMultipleLinksAndAccountsForFlowShare() {
        val network = RoadNetwork(
            intersections = listOf(
                node("A", "out-A", "out-A2"),
                node("B", "in-B", "in-B2"),
                node("C", "in-C", "out-C")
            ),
            trafficLinks = listOf(
                NetworkTrafficLink("AB", "A", "out-A", "B", "in-B", flowSharePercent = 20.0,
                    distanceMeters = 100.0, travelSpeedKmh = 36.0),
                NetworkTrafficLink("AC", "A", "out-A2", "C", "in-C", flowSharePercent = 100.0,
                    distanceMeters = 200.0, travelSpeedKmh = 36.0),
                NetworkTrafficLink("CB", "C", "out-C", "B", "in-B2", flowSharePercent = 100.0,
                    distanceMeters = 200.0, travelSpeedKmh = 36.0)
            )
        )
        val report = NetworkCoordinationCalculator.calculateReport(network, settings())
        assertTrue(report.isValid)
        assertEquals(3, report.links.size)
        assertTrue(report.links.all { it.progressionBandwidthSeconds in 0.0..20.0 })
        assertTrue(report.objectiveBandwidthSeconds > 0.0)
    }
}
