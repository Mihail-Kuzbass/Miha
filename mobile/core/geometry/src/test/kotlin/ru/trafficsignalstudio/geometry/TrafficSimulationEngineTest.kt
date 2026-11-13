package ru.trafficsignalstudio.geometry

import org.junit.Assert.*
import org.junit.Test
import ru.trafficsignalstudio.model.*

class TrafficSimulationEngineTest {
    private fun signalizedIntersection(): Intersection {
        val incoming = Lane(id = "in", index = 0, allowedMovements = setOf(MovementKind.THROUGH))
        val outgoing = Lane(id = "out", index = 0, flowDirection = LaneFlowDirection.OUT_OF_INTERSECTION)
        val connection = LaneConnection(id = "m1", fromApproachId = "a", fromLaneId = "in",
            toApproachId = "b", toLaneId = "out")
        return Intersection(
            name = "Тест", type = IntersectionType.CUSTOM,
            approaches = listOf(
                Approach(id = "a", name = "A", headingDegrees = 0.0, lanes = listOf(incoming)),
                Approach(id = "b", name = "B", headingDegrees = 180.0, lanes = listOf(outgoing))
            ),
            laneConnections = listOf(connection),
            signalPlan = SignalPlan(phases = listOf(
                SignalPhase(id = "p1", name = "Основная", greenConnectionIds = setOf("m1"),
                    greenSeconds = 25, yellowSeconds = 3, allRedSeconds = 2)
            ))
        )
    }

    @Test fun classSpecificFlowsAreIncludedInTotalArrivals() {
        val result = TrafficSimulationEngine.run(signalizedIntersection(), TrafficSimulationDemand(
            movements = listOf(MovementTrafficDemand("m1", 360.0,
                mapOf(VehicleClass.CITY_BUS to 36.0, VehicleClass.RIGID_TRUCK to 24.0))),
            durationSeconds = 120
        ))
        assertTrue(result.totalArrivals in 10..14)
        assertEquals(result.totalArrivals, result.categoryMetrics.sumOf { it.arrivals })
        assertTrue(result.categoryMetrics.any { it.vehicleClass == VehicleClass.CITY_BUS && it.arrivals > 0 })
    }

    @Test fun redSignalBuildsQueueAndGreenSignalReleasesVehicles() {
        val result = TrafficSimulationEngine.run(signalizedIntersection(), TrafficSimulationDemand(
            movements = listOf(MovementTrafficDemand("m1", 1800.0)),
            durationSeconds = 300
        ))
        assertTrue(result.totalArrivals > result.totalDepartures)
        assertTrue(result.maximumQueueVehicles > 0)
        assertTrue(result.snapshots.isNotEmpty())
        assertTrue(result.totalDepartures > 0)
    }

    @Test fun vehiclesRespectPhysicalLengthAndSafeFollowingGap() {
        val result = TrafficSimulationEngine.run(signalizedIntersection(), TrafficSimulationDemand(
            movements = listOf(MovementTrafficDemand("m1", 7200.0)),
            durationSeconds = 120,
            approachStorageLengthMeters = 120.0
        ))
        assertTrue(result.maximumQueueLengthMeters > 0.0)
        result.snapshots.forEach { snapshot ->
            val approachVehicles = snapshot.activeVehicles
                .filter { it.connectionId == "m1" && it.positionMeters <= 0.0 }
                .sortedByDescending { it.positionMeters }
            approachVehicles.zipWithNext().forEach { (leader, follower) ->
                val gap = leader.positionMeters - leader.lengthMeters - follower.positionMeters
                assertTrue("Unsafe bumper gap $gap m", gap >= 1.9)
            }
        }
    }

    @Test fun finiteApproachStorageReportsUpstreamSpillback() {
        val result = TrafficSimulationEngine.run(signalizedIntersection(), TrafficSimulationDemand(
            movements = listOf(MovementTrafficDemand("m1", 7200.0)),
            durationSeconds = 120,
            approachStorageLengthMeters = 20.0
        ))
        assertTrue(result.spillbackVehiclesAtEnd > 0)
        assertTrue(result.maximumSpillbackVehicles > 0)
        assertTrue(result.maximumQueueLengthMeters <= 20.0 + 1e-6)
    }

    @Test fun vehicleCategoryUsesItsPhysicalVehicleLengthInSnapshots() {
        val result = TrafficSimulationEngine.run(signalizedIntersection(), TrafficSimulationDemand(
            movements = listOf(MovementTrafficDemand("m1", 3600.0,
                mapOf(VehicleClass.CITY_BUS to 3600.0))),
            durationSeconds = 120
        ))
        assertTrue(result.snapshots.flatMap { it.activeVehicles }.any {
            it.vehicleClass == VehicleClass.CITY_BUS && kotlin.math.abs(it.lengthMeters - 12.0) < 0.01
        })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsClassFlowsAboveTotalFlow() {
        MovementTrafficDemand("m1", 100.0, mapOf(VehicleClass.CITY_BUS to 120.0))
    }
}
