package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.NetworkTrafficLink
import ru.trafficsignalstudio.model.VehicleModelCatalog
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Routes individual vehicles through finite-capacity road links.
 *
 * A link is modelled as parallel lanes with a vehicle-specific safe entry
 * headway. Vehicles can travel simultaneously; the entry queue forms only
 * when the requested flow exceeds the link's lane capacity.
 */
object NetworkLinkTrafficModel {
    data class RoutedArrival(
        val targetIntersectionId: String,
        val arrival: ExternalVehicleArrival,
        val linkId: String,
        val entryDelaySeconds: Double
    )

    data class LinkMetrics(
        val linkId: String,
        val vehiclesEntered: Int,
        val vehiclesWaitingAtEnd: Int,
        val vehiclesArrivedWithinHorizon: Int,
        val maximumEntryQueue: Int,
        val averageEntryDelaySeconds: Double,
        val maximumEntryDelaySeconds: Double,
        val maximumEntryQueueLengthMeters: Double,
        val spillbackVehicles: Int
    )

    data class VehicleSchedule(
        val linkId: String,
        val vehicleId: Long,
        val vehicleClass: ru.trafficsignalstudio.model.VehicleClass?,
        val laneIndex: Int,
        val departureTimeSeconds: Double,
        val entryTimeSeconds: Double,
        val arrivalTimeSeconds: Double,
        val distanceMeters: Double,
        val accelerationMetersPerSecondSquared: Double,
        val cruiseSpeedMetersPerSecond: Double,
        val lengthMeters: Double,
        val widthMeters: Double,
        val minimumGapMeters: Double,
        val centerline: List<ru.trafficsignalstudio.model.Point2D> = emptyList(),
        val laneCount: Int = 1,
        val laneWidthMeters: Double = 3.5
    )

    data class VehicleSnapshot(
        val linkId: String,
        val vehicleId: Long,
        val vehicleClass: ru.trafficsignalstudio.model.VehicleClass?,
        val laneIndex: Int,
        val positionMeters: Double,
        val speedMetersPerSecond: Double,
        val lengthMeters: Double,
        val widthMeters: Double,
        val worldPoint: ru.trafficsignalstudio.model.Point2D,
        val headingRadians: Double
    )

    data class Result(
        val arrivalsByIntersection: Map<String, List<ExternalVehicleArrival>>,
        val metrics: List<LinkMetrics>,
        val vehicleSchedules: List<VehicleSchedule>
    ) {
        /** Returns the current physical state of vehicles on all inter-intersection links. */
        fun vehiclesAt(timeSeconds: Double): List<VehicleSnapshot> =
            NetworkLinkTrafficModel.vehiclesAt(vehicleSchedules, timeSeconds)
    }

    private data class Candidate(
        val link: NetworkTrafficLink,
        val departure: VehicleDepartureEvent
    )

    fun vehiclesAt(
        schedules: List<VehicleSchedule>,
        timeSeconds: Double
    ): List<VehicleSnapshot> = schedules.mapNotNull { schedule ->
        if (timeSeconds < schedule.entryTimeSeconds || timeSeconds >= schedule.arrivalTimeSeconds) {
            return@mapNotNull null
        }
        val elapsed = (timeSeconds - schedule.entryTimeSeconds).coerceAtLeast(0.0)
        val acceleration = schedule.accelerationMetersPerSecondSquared
        val accelTime = schedule.cruiseSpeedMetersPerSecond / acceleration
        val accelDistance = 0.5 * acceleration * accelTime * accelTime
        val rawPosition = if (elapsed < accelTime) {
            0.5 * acceleration * elapsed * elapsed
        } else {
            accelDistance + schedule.cruiseSpeedMetersPerSecond * (elapsed - accelTime)
        }
        val position = rawPosition.coerceIn(0.0, schedule.distanceMeters)
        val speed = min(schedule.cruiseSpeedMetersPerSecond, acceleration * elapsed)
        val path = schedule.centerline.ifEmpty {
            listOf(
                ru.trafficsignalstudio.model.Point2D(0.0, 0.0),
                ru.trafficsignalstudio.model.Point2D(schedule.distanceMeters, 0.0)
            )
        }
        val pose = NetworkRoadGeometry.poseAt(
            path, position, schedule.laneIndex, schedule.laneCount, schedule.laneWidthMeters
        )
        VehicleSnapshot(
            linkId = schedule.linkId,
            vehicleId = schedule.vehicleId,
            vehicleClass = schedule.vehicleClass,
            laneIndex = schedule.laneIndex,
            positionMeters = position,
            speedMetersPerSecond = speed,
            lengthMeters = schedule.lengthMeters,
            widthMeters = schedule.widthMeters,
            worldPoint = pose.point,
            headingRadians = pose.headingRadians
        )
    }

    private fun positionAt(schedule: VehicleSchedule, timeSeconds: Double): Double {
        if (timeSeconds <= schedule.entryTimeSeconds) return 0.0
        val elapsed = (timeSeconds - schedule.entryTimeSeconds).coerceAtLeast(0.0)
        val acceleration = schedule.accelerationMetersPerSecondSquared
        val accelTime = schedule.cruiseSpeedMetersPerSecond / acceleration
        val accelDistance = 0.5 * acceleration * accelTime * accelTime
        val position = if (elapsed < accelTime) 0.5 * acceleration * elapsed * elapsed
            else accelDistance + schedule.cruiseSpeedMetersPerSecond * (elapsed - accelTime)
        return position.coerceIn(0.0, schedule.distanceMeters)
    }

    private fun isSafeFollowing(leader: VehicleSchedule, follower: VehicleSchedule): Boolean {
        if (leader.arrivalTimeSeconds <= follower.entryTimeSeconds) return true
        var time = follower.entryTimeSeconds
        val end = min(leader.arrivalTimeSeconds, follower.arrivalTimeSeconds)
        while (time < end) {
            val leaderPosition = positionAt(leader, time)
            val followerPosition = positionAt(follower, time)
            val leaderRearPosition = leaderPosition - leader.lengthMeters
            if (followerPosition + follower.minimumGapMeters >
                leaderRearPosition - 1e-6) return false
            time += 0.25
        }
        return true
    }

    fun route(
        departuresByIntersection: Map<String, List<VehicleDepartureEvent>>,
        links: List<NetworkTrafficLink>,
        durationSeconds: Int
    ): Result {
        require(durationSeconds > 0)
        val enabled = links.filter { it.enabled }
        val candidatesByLink = mutableMapOf<String, MutableList<Candidate>>()

        enabled.groupBy { it.fromIntersectionId to it.fromMovementConnectionId }
            .forEach { (source, outgoingLinks) ->
                val sortedLinks = outgoingLinks.sortedBy { it.id }
                val totalShare = sortedLinks.sumOf { it.flowSharePercent }
                require(totalShare <= 100.000001) {
                    "Суммарная доля связей для движения ${source.second} превышает 100%."
                }
                val cumulative = mutableListOf<Pair<NetworkTrafficLink, Double>>()
                var boundary = 0.0
                sortedLinks.forEach { link ->
                    boundary += link.flowSharePercent
                    cumulative += link to boundary
                }
                departuresByIntersection[source.first].orEmpty()
                    .asSequence()
                    .filter { it.connectionId == source.second }
                    .forEach departureLoop@{ departure ->
                        // Assign a stable network-wide identity once; preserve it on every later hop.
                        val networkVehicleId = if (departure.vehicleId >= NETWORK_VEHICLE_ID_BASE) {
                            departure.vehicleId
                        } else {
                            val sourcePart = (source.first.hashCode().toLong() and 0x3fffffffL) shl 32
                            NETWORK_VEHICLE_ID_BASE or sourcePart or (departure.vehicleId and 0xffffffffL)
                        }
                        // Deterministic partition: every vehicle is assigned to at most one link.
                        val bucket = Math.floorMod(networkVehicleId * 7919L, 10000L) / 100.0
                        val selected = cumulative.firstOrNull { bucket < it.second }?.first
                            ?: return@departureLoop
                        candidatesByLink.getOrPut(selected.id) { mutableListOf() }
                            .add(Candidate(selected, departure.copy(vehicleId = networkVehicleId)))
                    }
            }

        val arrivals = mutableMapOf<String, MutableList<ExternalVehicleArrival>>()
        val metrics = mutableListOf<LinkMetrics>()
        val schedules = mutableListOf<VehicleSchedule>()
        enabled.forEach { link ->
            val candidates = candidatesByLink[link.id].orEmpty().sortedWith(
                compareBy<Candidate> { it.departure.timeSeconds }.thenBy { it.departure.vehicleId }
            )
            val linkSchedules = mutableListOf<VehicleSchedule>()
            val nextEntryAt = DoubleArray(link.laneCount)
            val lastVehicleOnLane = arrayOfNulls<VehicleSchedule>(link.laneCount)
            val entryDelays = mutableListOf<Double>()
            val queueEvents = mutableListOf<Triple<Double, Int, Double>>()
            var arrivedWithinHorizon = 0
            val linkSpeed = link.travelSpeedKmh / 3.6
            candidates.forEach { candidate ->
                val vehicleModel = candidate.departure.vehicleClass?.let { vehicleClass ->
                    VehicleModelCatalog.defaults.firstOrNull { it.vehicleClass == vehicleClass }
                } ?: VehicleModelCatalog.defaultPassengerCar
                val speed = min(linkSpeed, vehicleModel.maximumSpeedKmh / 3.6).coerceAtLeast(0.1)
                val safeHeadway = max(
                    vehicleModel.desiredTimeHeadwaySeconds,
                    (vehicleModel.lengthMeters + vehicleModel.minimumGapMeters) / speed
                )
                val laneIndex = nextEntryAt.indices.minByOrNull { nextEntryAt[it] } ?: 0
                var entryTime = max(candidate.departure.timeSeconds, nextEntryAt[laneIndex])
                val acceleration = vehicleModel.accelerationMetersPerSecondSquared.coerceAtLeast(0.1)
                val accelTime = speed / acceleration
                val accelDistance = 0.5 * acceleration * accelTime * accelTime
                val travelTime = if (link.distanceMeters <= accelDistance) {
                    sqrt(2.0 * link.distanceMeters / acceleration)
                } else {
                    accelTime + (link.distanceMeters - accelDistance) / speed
                }
                fun scheduleAt(time: Double) = VehicleSchedule(
                    linkId = link.id,
                    vehicleId = candidate.departure.vehicleId,
                    vehicleClass = candidate.departure.vehicleClass,
                    laneIndex = laneIndex,
                    departureTimeSeconds = candidate.departure.timeSeconds,
                    entryTimeSeconds = time,
                    arrivalTimeSeconds = time + travelTime,
                    distanceMeters = link.distanceMeters,
                    accelerationMetersPerSecondSquared = acceleration,
                    cruiseSpeedMetersPerSecond = speed,
                    lengthMeters = vehicleModel.lengthMeters,
                    widthMeters = vehicleModel.widthMeters,
                    minimumGapMeters = vehicleModel.minimumGapMeters,
                    centerline = link.centerline,
                    laneCount = link.laneCount,
                    laneWidthMeters = link.laneWidthMeters
                )
                var schedule = scheduleAt(entryTime)
                val leader = lastVehicleOnLane[laneIndex]
                var safetyAdjustments = 0
                while (leader != null && !isSafeFollowing(leader, schedule) && safetyAdjustments < 10000) {
                    entryTime += 0.25
                    schedule = scheduleAt(entryTime)
                    safetyAdjustments++
                }
                val entryDelay = entryTime - candidate.departure.timeSeconds
                nextEntryAt[laneIndex] = entryTime + safeHeadway
                lastVehicleOnLane[laneIndex] = schedule
                val arrivalTime = schedule.arrivalTimeSeconds
                entryDelays += entryDelay
                if (entryDelay > 1e-9) {
                    val queueLength = vehicleModel.lengthMeters + vehicleModel.minimumGapMeters
                    queueEvents += Triple(candidate.departure.timeSeconds, 1, queueLength)
                    queueEvents += Triple(entryTime, -1, -queueLength)
                }
                schedules += schedule
                linkSchedules += schedule
                if (arrivalTime <= durationSeconds + 1e-9) {
                    arrivals.getOrPut(link.toIntersectionId) { mutableListOf() } += ExternalVehicleArrival(
                        timeSeconds = arrivalTime,
                        connectionId = link.toMovementConnectionId,
                        vehicleClass = candidate.departure.vehicleClass,
                        sourceVehicleId = candidate.departure.vehicleId
                    )
                    arrivedWithinHorizon++
                }
            }
            var queue = 0
            var maximumQueue = 0
            var queueLength = 0.0
            var maximumQueueLength = 0.0
            var spillbackVehicles = 0
            queueEvents.sortedWith(compareBy<Triple<Double, Int, Double>> { it.first }.thenBy { it.second })
                .forEach { (_, delta, lengthDelta) ->
                    queue = (queue + delta).coerceAtLeast(0)
                    queueLength = (queueLength + lengthDelta).coerceAtLeast(0.0)
                    maximumQueue = max(maximumQueue, queue)
                    maximumQueueLength = max(maximumQueueLength, queueLength)
                    if (delta > 0 && queueLength > link.queueStorageLengthMeters * link.laneCount) spillbackVehicles++
                }
            val waitingAtEnd = linkSchedules.count { it.entryTimeSeconds > durationSeconds }
            metrics += LinkMetrics(
                linkId = link.id,
                vehiclesEntered = candidates.size - waitingAtEnd,
                vehiclesWaitingAtEnd = waitingAtEnd,
                vehiclesArrivedWithinHorizon = arrivedWithinHorizon,
                maximumEntryQueue = maximumQueue,
                averageEntryDelaySeconds = entryDelays.average().takeIf { it.isFinite() } ?: 0.0,
                maximumEntryDelaySeconds = entryDelays.maxOrNull() ?: 0.0,
                maximumEntryQueueLengthMeters = maximumQueueLength,
                spillbackVehicles = spillbackVehicles
            )
        }
        return Result(
            arrivalsByIntersection = arrivals.mapValues { it.value.sortedBy(ExternalVehicleArrival::timeSeconds) },
            metrics = metrics,
            vehicleSchedules = schedules
        )
    }
}
