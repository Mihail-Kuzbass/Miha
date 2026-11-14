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

    @Test fun upstreamVehicleArrivalsAreInjectedAtTheirScheduledTimesAndCanDepart() {
        val result = TrafficSimulationEngine.run(signalizedIntersection(), TrafficSimulationDemand(
            movements = listOf(MovementTrafficDemand("m1", 0.0)),
            externalArrivals = listOf(
                ExternalVehicleArrival(30.0, "m1", VehicleClass.CITY_BUS, 101L),
                ExternalVehicleArrival(45.0, "m1", VehicleClass.RIGID_TRUCK, 102L)
            ),
            durationSeconds = 180
        ))
        assertEquals(2, result.totalArrivals)
        assertTrue(result.departureEvents.isNotEmpty())
        assertTrue(result.departureEvents.all { it.connectionId == "m1" && it.timeSeconds >= 30.0 })
        assertTrue(result.departureEvents.any { it.vehicleClass == VehicleClass.CITY_BUS })
        assertTrue(result.departureEvents.any { it.vehicleClass == VehicleClass.RIGID_TRUCK })
    }

    @Test fun networkVehicleIdentityIsPreservedWhenItArrivesAtAnotherIntersection() {
        val networkVehicleId = NETWORK_VEHICLE_ID_BASE + 12345L
        val result = TrafficSimulationEngine.run(signalizedIntersection(), TrafficSimulationDemand(
            movements = listOf(MovementTrafficDemand("m1", 0.0)),
            externalArrivals = listOf(
                ExternalVehicleArrival(30.0, "m1", VehicleClass.CITY_BUS, networkVehicleId)
            ),
            durationSeconds = 180
        ))
        assertTrue(result.departureEvents.any { it.vehicleId == networkVehicleId })
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
            val sameMovementVehicles = snapshot.activeVehicles
                .filter { it.connectionId == "m1" }
                .sortedByDescending { it.positionMeters }
            sameMovementVehicles.zipWithNext().forEach { (leader, follower) ->
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
        val finalSnapshot = result.snapshots.last()
        val queuedSprites = finalSnapshot.activeVehicles
            .filter { it.positionMeters <= 0.0 }
            .sortedByDescending { it.positionMeters }
        assertTrue(queuedSprites.any { it.positionMeters < -20.0 })
        queuedSprites.zipWithNext().forEach { (leader, follower) ->
            val gap = leader.positionMeters - leader.lengthMeters - follower.positionMeters
            assertTrue("Spillback vehicles overlap: $gap m", gap >= 1.9)
        }
    }

    @Test fun congestedLaneAutomaticallyMovesVehiclesToCompatibleAdjacentLane() {
        val incomingA = Lane(id = "in-a", index = 0, allowedMovements = setOf(MovementKind.THROUGH))
        val incomingB = Lane(id = "in-b", index = 1, allowedMovements = setOf(MovementKind.THROUGH))
        val outgoing = Lane(id = "out", index = 0, flowDirection = LaneFlowDirection.OUT_OF_INTERSECTION)
        val intersection = Intersection(
            name = "Две полосы", type = IntersectionType.CUSTOM,
            approaches = listOf(
                Approach(id = "a", name = "A", headingDegrees = 0.0, lanes = listOf(incomingA, incomingB)),
                Approach(id = "b", name = "B", headingDegrees = 180.0, lanes = listOf(outgoing))
            ),
            laneConnections = listOf(
                LaneConnection(id = "through-a", fromApproachId = "a", fromLaneId = "in-a", toApproachId = "b", toLaneId = "out"),
                LaneConnection(id = "through-b", fromApproachId = "a", fromLaneId = "in-b", toApproachId = "b", toLaneId = "out")
            ),
            signalPlan = SignalPlan(phases = listOf(
                SignalPhase(id = "p", name = "Основная", greenConnectionIds = setOf("through-a", "through-b"),
                    greenSeconds = 25, yellowSeconds = 3, allRedSeconds = 2)
            ))
        )
        val result = TrafficSimulationEngine.run(intersection, TrafficSimulationDemand(
            movements = listOf(MovementTrafficDemand("through-a", 3600.0)),
            durationSeconds = 180,
            approachStorageLengthMeters = 120.0
        ))
        assertTrue("No vehicle was assigned to the less-loaded compatible lane",
            result.snapshots.flatMap { it.activeVehicles }.any { it.connectionId == "through-b" })
        assertTrue("Lane changes should be represented during the lateral transition",
            result.snapshots.flatMap { it.activeVehicles }.any { it.laneChangeFromConnectionId == "through-a" && it.laneChangeProgress < 1.0 })
        assertEquals(result.totalArrivals, result.categoryMetrics.sumOf { it.arrivals })
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

    @Test fun conflictingVehiclesBrakeBeforeTheirVisualEnvelopesTouch() {
        fun lane(id: String, flow: LaneFlowDirection) = Lane(
            id = id,
            index = 0,
            flowDirection = flow,
            allowedMovements = if (flow == LaneFlowDirection.INTO_INTERSECTION)
                setOf(MovementKind.THROUGH) else emptySet()
        )
        val approaches = listOf(
            Approach(id = "north", name = "Север", headingDegrees = 0.0,
                lanes = listOf(lane("north-in", LaneFlowDirection.INTO_INTERSECTION),
                    lane("north-out", LaneFlowDirection.OUT_OF_INTERSECTION))),
            Approach(id = "south", name = "Юг", headingDegrees = 180.0,
                lanes = listOf(lane("south-in", LaneFlowDirection.INTO_INTERSECTION),
                    lane("south-out", LaneFlowDirection.OUT_OF_INTERSECTION))),
            Approach(id = "east", name = "Восток", headingDegrees = 90.0,
                lanes = listOf(lane("east-in", LaneFlowDirection.INTO_INTERSECTION),
                    lane("east-out", LaneFlowDirection.OUT_OF_INTERSECTION))),
            Approach(id = "west", name = "Запад", headingDegrees = 270.0,
                lanes = listOf(lane("west-in", LaneFlowDirection.INTO_INTERSECTION),
                    lane("west-out", LaneFlowDirection.OUT_OF_INTERSECTION)))
        )
        val connections = listOf(
            LaneConnection("north-south", "north", "north-in", "south", "south-out"),
            LaneConnection("south-north", "south", "south-in", "north", "north-out"),
            LaneConnection("east-west", "east", "east-in", "west", "west-out"),
            LaneConnection("west-east", "west", "west-in", "east", "east-out")
        )
        val intersection = Intersection(
            name = "Перекрёсток с пересекающимися потоками",
            type = IntersectionType.CUSTOM,
            approaches = approaches,
            laneConnections = connections,
            signalPlan = SignalPlan(phases = listOf(
                SignalPhase(
                    id = "all-green",
                    name = "Тестовый конфликт",
                    greenConnectionIds = connections.map { it.id }.toSet(),
                    greenSeconds = 55,
                    yellowSeconds = 3,
                    allRedSeconds = 2
                )
            ))
        )
        val result = TrafficSimulationEngine.run(intersection, TrafficSimulationDemand(
            movements = connections.map { MovementTrafficDemand(it.id, 60.0) },
            durationSeconds = 180,
            approachStorageLengthMeters = 40.0,
            approachSpeedKmh = 50.0,
            // Isolate collision avoidance from the separate random crash-scenario generator.
            // EXPERIMENTAL with no manual events still detects and records physical contact.
            crashMode = CrashSimulationMode.EXPERIMENTAL
        ))
        assertFalse(
            "Predictive braking should prevent physical contact between conflicting moving streams",
            result.crashIncidents.any { it.cause == CrashIncidentCause.PHYSICAL_CONTACT }
        )
        val observedSpeeds = result.snapshots.flatMap { it.activeVehicles }
            .map { it.speedMetersPerSecond }
        assertTrue(
            "Simulation should retain moving traffic while conflicting vehicles yield",
            observedSpeeds.any { it > 0.5 }
        )
        assertTrue(
            "At least one vehicle should reduce speed while yielding to a predicted conflict",
            observedSpeeds.any { it in 0.1..10.0 }
        )
    }
    
    @Test fun conflictZonePriorityDoesNotDependOnVehicleCreationOrder() {
        fun lane(id: String, flow: LaneFlowDirection) = Lane(
            id = id,
            index = 0,
            flowDirection = flow,
            allowedMovements = if (flow == LaneFlowDirection.INTO_INTERSECTION)
                setOf(MovementKind.THROUGH) else emptySet()
        )
        val approaches = listOf(
            Approach(id = "north", name = "Север", headingDegrees = 0.0,
                lanes = listOf(lane("north-in", LaneFlowDirection.INTO_INTERSECTION),
                    lane("north-out", LaneFlowDirection.OUT_OF_INTERSECTION))),
            Approach(id = "south", name = "Юг", headingDegrees = 180.0,
                lanes = listOf(lane("south-in", LaneFlowDirection.INTO_INTERSECTION),
                    lane("south-out", LaneFlowDirection.OUT_OF_INTERSECTION))),
            Approach(id = "east", name = "Восток", headingDegrees = 90.0,
                lanes = listOf(lane("east-in", LaneFlowDirection.INTO_INTERSECTION),
                    lane("east-out", LaneFlowDirection.OUT_OF_INTERSECTION))),
            Approach(id = "west", name = "Запад", headingDegrees = 270.0,
                lanes = listOf(lane("west-in", LaneFlowDirection.INTO_INTERSECTION),
                    lane("west-out", LaneFlowDirection.OUT_OF_INTERSECTION)))
        )
        val connections = listOf(
            LaneConnection("west-east", "west", "west-in", "east", "east-out"),
            LaneConnection("east-west", "east", "east-in", "west", "west-out"),
            LaneConnection("south-north", "south", "south-in", "north", "north-out"),
            LaneConnection("north-south", "north", "north-in", "south", "south-out")
        )
        val intersection = Intersection(
            name = "Перекрёсток с обратным порядком потоков",
            type = IntersectionType.CUSTOM,
            approaches = approaches,
            laneConnections = connections,
            signalPlan = SignalPlan(phases = listOf(
                SignalPhase(
                    id = "all-green",
                    name = "Одновременный зелёный для проверки приоритета",
                    greenConnectionIds = connections.map { it.id }.toSet(),
                    greenSeconds = 55,
                    yellowSeconds = 3,
                    allRedSeconds = 2
                )
            ))
        )
        val result = TrafficSimulationEngine.run(intersection, TrafficSimulationDemand(
            movements = connections.map { MovementTrafficDemand(it.id, 60.0) },
            durationSeconds = 180,
            approachStorageLengthMeters = 40.0,
            approachSpeedKmh = 50.0
        ))
        assertFalse(
            "Conflict-zone reservations must use arrival time, not creation order",
            result.crashIncidents.any { it.cause == CrashIncidentCause.PHYSICAL_CONTACT }
        )
        assertTrue("Priority rotation must not deadlock all movements", result.totalDepartures > 0)
    }


    @Test fun vehiclesAccelerateFromRestAccordingToTheirVehicleModel() {
        val result = TrafficSimulationEngine.run(signalizedIntersection(), TrafficSimulationDemand(
            movements = listOf(MovementTrafficDemand("m1", 360.0)),
            durationSeconds = 60,
            approachStorageLengthMeters = 120.0,
            approachSpeedKmh = 50.0
        ))
        val samples = result.snapshots.mapNotNull { snapshot ->
            snapshot.activeVehicles.firstOrNull { it.id == 1L }?.let { snapshot.timeSeconds to it }
        }.filter { it.first <= 20.0 }
        assertTrue("The first vehicle should appear in snapshots", samples.isNotEmpty())
        assertTrue(
            "The first sampled speed must be consistent with starting from rest during the preceding time step",
            samples.first().second.speedMetersPerSecond <=
                VehicleModelCatalog.defaultPassengerCar.accelerationMetersPerSecondSquared + 1e-6
        )
        assertTrue(
            "Speed should increase gradually rather than start at free-flow speed",
            samples.any { it.second.speedMetersPerSecond in 0.1..5.0 } &&
                samples.last().second.speedMetersPerSecond > samples.first().second.speedMetersPerSecond
        )
        assertTrue("Acceleration must respect the selected vehicle model", samples.all {
            it.second.accelerationMetersPerSecondSquared <=
                VehicleModelCatalog.defaultPassengerCar.accelerationMetersPerSecondSquared + 1e-6
        })
    }

    @Test fun disabledCrashModeSuppressesManualAndPhysicalIncidentRegistration() {
        val intersection = signalizedIntersection()
        val result = TrafficSimulationEngine.run(intersection, TrafficSimulationDemand(
            movements = listOf(MovementTrafficDemand("m1", 7200.0)),
            durationSeconds = 120,
            approachStorageLengthMeters = 120.0,
            crashMode = CrashSimulationMode.DISABLED,
            manualCrashEvents = listOf(
                ManualCrashEvent(10.0, CrashRiskBand.FOLLOWING_DISTANCE, intersection.id)
            )
        ))
        assertTrue("Disabled mode must not register scheduled or physical crashes",
            result.crashIncidents.isEmpty())
        assertTrue("Disabling crash registration must not stop normal traffic simulation",
            result.totalArrivals > 0)
    }

    @Test fun experimentalCrashModeSchedulesTheConfiguredIncident() {
        val intersection = signalizedIntersection()
        val result = TrafficSimulationEngine.run(intersection, TrafficSimulationDemand(
            movements = listOf(MovementTrafficDemand("m1", 7200.0)),
            durationSeconds = 120,
            approachStorageLengthMeters = 120.0,
            crashMode = CrashSimulationMode.EXPERIMENTAL,
            manualCrashEvents = listOf(
                ManualCrashEvent(20.0, CrashRiskBand.FOLLOWING_DISTANCE, intersection.id)
            )
        ))
        assertTrue("The configured incident should be registered at or after its scheduled time",
            result.crashIncidents.any {
                it.cause == CrashIncidentCause.RISK_SCENARIO &&
                    it.type == CrashRiskBand.FOLLOWING_DISTANCE &&
                    it.timeSeconds >= 20.0
            })
    }

    @Test fun vehiclesExposeNegativeAccelerationWhileBrakingForRedSignal() {
        val result = TrafficSimulationEngine.run(signalizedIntersection(), TrafficSimulationDemand(
            movements = listOf(MovementTrafficDemand("m1", 1800.0)),
            durationSeconds = 120,
            approachStorageLengthMeters = 20.0,
            approachSpeedKmh = 50.0
        ))
        val samples = result.snapshots.flatMap { snapshot ->
            snapshot.activeVehicles.map { snapshot.timeSeconds to it }
        }
        assertTrue("Braking should be represented by negative acceleration", samples.any { (_, vehicle) ->
            vehicle.accelerationMetersPerSecondSquared < -0.1 && vehicle.speedMetersPerSecond > 0.0
        })
        assertTrue("Queued vehicles must remain behind the stop line",
            result.snapshots.flatMap { it.activeVehicles }.none {
                it.queued && it.positionMeters > 0.0
            })
    }

}
