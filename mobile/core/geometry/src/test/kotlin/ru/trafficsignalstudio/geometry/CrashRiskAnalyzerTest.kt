package ru.trafficsignalstudio.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.trafficsignalstudio.model.*

class CrashRiskAnalyzerTest {
    private fun simpleIntersection(): Intersection {
        val incoming = Lane(id = "in", index = 0, allowedMovements = setOf(MovementKind.THROUGH))
        val outgoing = Lane(id = "out", index = 0, flowDirection = LaneFlowDirection.OUT_OF_INTERSECTION)
        return Intersection(
            name = "Риск", type = IntersectionType.CUSTOM,
            approaches = listOf(
                Approach(id = "a", name = "A", headingDegrees = 0.0, lanes = listOf(incoming)),
                Approach(id = "b", name = "B", headingDegrees = 180.0, lanes = listOf(outgoing))
            ),
            laneConnections = listOf(LaneConnection(
                id = "m1", fromApproachId = "a", fromLaneId = "in",
                toApproachId = "b", toLaneId = "out"
            )),
            signalPlan = SignalPlan(phases = listOf(
                SignalPhase(id = "p1", name = "Основная", greenConnectionIds = setOf("m1"))
            ))
        )
    }

    @Test fun lowFlowWithoutGeometricConflictsStaysInFollowingDistanceBand() {
        val assessment = CrashRiskAnalyzer.assess(simpleIntersection(), TrafficSimulationDemand(
            movements = listOf(MovementTrafficDemand("m1", 600.0))
        ))
        assertEquals(CrashRiskBand.FOLLOWING_DISTANCE, assessment.band)
        assertTrue(assessment.riskPercent in 0.0..9.9)
    }

    @Test fun riskGrowsWithDemand() {
        val intersection = simpleIntersection()
        val low = CrashRiskAnalyzer.assess(intersection, TrafficSimulationDemand(
            movements = listOf(MovementTrafficDemand("m1", 300.0))
        ))
        val high = CrashRiskAnalyzer.assess(intersection, TrafficSimulationDemand(
            movements = listOf(MovementTrafficDemand("m1", 3600.0))
        ))
        assertTrue(high.riskPercent > low.riskPercent)
    }
}
