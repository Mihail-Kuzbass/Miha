package ru.trafficsignalstudio.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.trafficsignalstudio.model.NetworkTrafficLink
import ru.trafficsignalstudio.model.VehicleClass

class NetworkTrafficNetworkSolverTest {
    @Test fun cyclicNetworkPropagatesVehiclesUntilTheyLeaveTheTimeHorizon() {
        val links = listOf(
            NetworkTrafficLink(
                id = "a-b",
                fromIntersectionId = "A",
                fromMovementConnectionId = "out-A",
                toIntersectionId = "B",
                toMovementConnectionId = "in-B",
                distanceMeters = 100.0,
                travelSpeedKmh = 36.0
            ),
            NetworkTrafficLink(
                id = "b-a",
                fromIntersectionId = "B",
                fromMovementConnectionId = "out-B",
                toIntersectionId = "A",
                toMovementConnectionId = "in-A",
                distanceMeters = 100.0,
                travelSpeedKmh = 36.0
            )
        )
        val result = NetworkTrafficNetworkSolver.run(
            intersectionIds = listOf("A", "B"),
            links = links,
            durationSeconds = 45,
            simulateIntersection = { nodeId, incoming ->
                buildList {
                    if (nodeId == "A") add(VehicleDepartureEvent(
                        timeSeconds = 0.0,
                        connectionId = "out-A",
                        vehicleClass = VehicleClass.PASSENGER_CAR,
                        vehicleId = 1L
                    ))
                    incoming.forEach { arrival ->
                        add(VehicleDepartureEvent(
                            timeSeconds = arrival.timeSeconds,
                            connectionId = if (nodeId == "A") "out-A" else "out-B",
                            vehicleClass = arrival.vehicleClass,
                            vehicleId = arrival.sourceVehicleId
                        ))
                    }
                }
            },
            departuresOf = { it }
        )

        assertTrue("A closed loop should converge within a finite horizon", result.converged)
        assertTrue("At least one circulation hop must be propagated", result.propagationIterations >= 3)
        assertEquals(2, result.nodeResults.getValue("A").size)
        assertEquals(2, result.nodeResults.getValue("B").size)
        val returnedVehicleId = result.nodeResults.getValue("A")
            .map { it.vehicleId }.first { it >= NETWORK_VEHICLE_ID_BASE }
        assertTrue(result.nodeResults.getValue("B").all { it.vehicleId == returnedVehicleId })
    }

    @Test fun roadLinkEntryQueueUsesVehicleHeadwayAndParallelLanes() {
        val oneLane = NetworkTrafficLink(
            id = "one-lane",
            fromIntersectionId = "A",
            fromMovementConnectionId = "out",
            toIntersectionId = "B",
            toMovementConnectionId = "in",
            distanceMeters = 100.0,
            travelSpeedKmh = 36.0,
            laneCount = 1
        )
        val departures = mapOf("A" to listOf(
            VehicleDepartureEvent(0.0, "out", VehicleClass.PASSENGER_CAR, 1L),
            VehicleDepartureEvent(0.0, "out", VehicleClass.PASSENGER_CAR, 2L)
        ))
        val oneLaneResult = NetworkLinkTrafficModel.route(departures, listOf(oneLane), 60)
        val oneLaneArrivals = oneLaneResult.arrivalsByIntersection.getValue("B").sortedBy { it.timeSeconds }
        assertEquals(2, oneLaneArrivals.size)
        assertTrue(oneLaneArrivals[1].timeSeconds - oneLaneArrivals[0].timeSeconds > 1.5)
        assertEquals(1, oneLaneResult.metrics.single().maximumEntryQueue)

        val twoLaneResult = NetworkLinkTrafficModel.route(
            departures,
            listOf(oneLane.copy(id = "two-lane", laneCount = 2)),
            60
        )
        val twoLaneArrivals = twoLaneResult.arrivalsByIntersection.getValue("B")
        assertEquals(2, twoLaneArrivals.size)
        assertEquals(twoLaneArrivals[0].timeSeconds, twoLaneArrivals[1].timeSeconds, 0.001)
        assertEquals(0, twoLaneResult.metrics.single().maximumEntryQueue)
    }

    @Test fun linkSnapshotsExposeAcceleratingAndQueuedVehicles() {
        val link = NetworkTrafficLink(
            id = "live-link",
            fromIntersectionId = "A",
            fromMovementConnectionId = "out",
            toIntersectionId = "B",
            toMovementConnectionId = "in",
            distanceMeters = 100.0,
            travelSpeedKmh = 36.0
        )
        val result = NetworkLinkTrafficModel.route(
            mapOf("A" to listOf(
                VehicleDepartureEvent(0.0, "out", VehicleClass.PASSENGER_CAR, 1L),
                VehicleDepartureEvent(0.0, "out", VehicleClass.PASSENGER_CAR, 2L)
            )),
            listOf(link),
            60
        )
        val atOneSecond = result.vehiclesAt(1.0)
        assertEquals(1, atOneSecond.size)
        val moving = atOneSecond.single()
        assertEquals(2.0, moving.speedMetersPerSecond, 0.001)
        assertEquals(1.0, moving.positionMeters, 0.001)
        assertEquals(1, result.metrics.single().maximumEntryQueue)
    }

    @Test fun fasterFollowingVehicleCannotOvertakeSlowerVehicleOnSingleLaneLink() {
        val link = NetworkTrafficLink(
            id = "mixed-traffic",
            fromIntersectionId = "A",
            fromMovementConnectionId = "out",
            toIntersectionId = "B",
            toMovementConnectionId = "in",
            distanceMeters = 100.0,
            travelSpeedKmh = 36.0,
            laneCount = 1
        )
        val result = NetworkLinkTrafficModel.route(
            mapOf("A" to listOf(
                VehicleDepartureEvent(0.0, "out", VehicleClass.CITY_BUS, 1L),
                VehicleDepartureEvent(0.0, "out", VehicleClass.PASSENGER_CAR, 2L)
            )),
            listOf(link),
            60
        )
        for (step in 0..240) {
            val time = step * 0.25
            val vehicles = result.vehiclesAt(time).sortedBy { it.positionMeters }
            if (vehicles.size == 2) {
                val follower = vehicles[0]
                val leader = vehicles[1]
                assertEquals(0, follower.laneIndex)
                assertEquals(0, leader.laneIndex)
                assertTrue(
                    "Following car must not overlap or pass the bus at t=$time",
                    follower.positionMeters + 2.0 <=
                        leader.positionMeters - leader.lengthMeters + 1e-6
                )
            }
        }
    }

    @Test fun queueLengthBeyondRoadStorageIsReportedAsSpillback() {
        val link = NetworkTrafficLink(
            id = "short-link",
            fromIntersectionId = "A",
            fromMovementConnectionId = "out",
            toIntersectionId = "B",
            toMovementConnectionId = "in",
            distanceMeters = 100.0,
            travelSpeedKmh = 36.0,
            queueStorageLengthMeters = 1.0
        )
        val result = NetworkLinkTrafficModel.route(
            mapOf("A" to listOf(
                VehicleDepartureEvent(0.0, "out", VehicleClass.PASSENGER_CAR, 1L),
                VehicleDepartureEvent(0.0, "out", VehicleClass.PASSENGER_CAR, 2L)
            )),
            listOf(link),
            60
        )
        assertTrue(result.metrics.single().maximumEntryQueueLengthMeters > link.queueStorageLengthMeters)
        assertEquals(1, result.metrics.single().spillbackVehicles)
    }

    @Test fun vehicleClassChangesRoadLinkSafeHeadway() {
        val link = NetworkTrafficLink(
            id = "bus-link",
            fromIntersectionId = "A",
            fromMovementConnectionId = "out",
            toIntersectionId = "B",
            toMovementConnectionId = "in",
            distanceMeters = 100.0,
            travelSpeedKmh = 36.0
        )
        val result = NetworkLinkTrafficModel.route(
            mapOf("A" to listOf(
                VehicleDepartureEvent(0.0, "out", VehicleClass.CITY_BUS, 1L),
                VehicleDepartureEvent(0.0, "out", VehicleClass.CITY_BUS, 2L)
            )),
            listOf(link),
            60
        )
        val arrivals = result.arrivalsByIntersection.getValue("B").sortedBy { it.timeSeconds }
        assertTrue(arrivals[1].timeSeconds - arrivals[0].timeSeconds > 2.0)
    }

    @Test fun splitLinksAssignEachVehicleToAtMostOneOutgoingLink() {
        val links = listOf(
            NetworkTrafficLink(
                id = "to-B", fromIntersectionId = "A", fromMovementConnectionId = "out",
                toIntersectionId = "B", toMovementConnectionId = "in", flowSharePercent = 50.0
            ),
            NetworkTrafficLink(
                id = "to-C", fromIntersectionId = "A", fromMovementConnectionId = "out",
                toIntersectionId = "C", toMovementConnectionId = "in", flowSharePercent = 50.0
            )
        )
        val departures = (1L..100L).map {
            VehicleDepartureEvent(it.toDouble(), "out", VehicleClass.PASSENGER_CAR, it)
        }
        val result = NetworkLinkTrafficModel.route(mapOf("A" to departures), links, 3600)
        assertEquals(100, result.vehicleSchedules.size)
        assertEquals(100, result.vehicleSchedules.map { it.vehicleId }.distinct().size)
        assertEquals(100, result.metrics.sumOf { it.vehiclesEntered + it.vehiclesWaitingAtEnd })
        assertTrue(result.arrivalsByIntersection.values.sumOf { it.size } <= 100)
    }
}
