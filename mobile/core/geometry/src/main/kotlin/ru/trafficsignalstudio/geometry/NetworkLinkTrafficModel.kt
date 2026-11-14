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
        val widthMeters: Double
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
        val queuedAtEntry: Boolean
    )

    data class Result(
        val arrivalsByIntersection: Map<String, List<ExternalVehicleArrival>>,
        val metrics: List<LinkMetrics>,
        val vehicleSchedules: List<VehicleSchedule>
    ) {
        /** Returns the current physical state of vehicles on all inter-intersection links. */
        fun vehiclesAt(timeSeconds: Double): List<VehicleSnapshot> =
            vehicleSchedules.mapNotNull { schedule ->
                if (timeSeconds < schedule.departureTimeSeconds || timeSeconds >= schedule.arrivalTimeSeconds) {
                    return@mapNotNull null
                }
                val queued = timeSeconds < schedule.entryTimeSeconds
                val elapsed = (timeSeconds - schedule.entryTimeSeconds).coerceAtLeast(0.0)
                val acceleration = schedule.accelerationMetersPerSecondSquared
                val accelTime = schedule.cruiseSpeedMetersPerSecond / acceleration
                val accelDistance = 0.5 * acceleration * accelTime * accelTime
                val position = if (queued) 0.0 else if (elapsed < accelTime) {
                    0.5 * acceleration * elapsed * elapsed
                } else {
                    accelDistance + schedule.cruiseSpeedMetersPerSecond * (elapsed - accelTime)
                }.coerceIn(0.0, schedule.distanceMeters)
                val speed = if (queued) 0.0 else
                    min(schedule.cruiseSpeedMetersPerSecond, acceleration * elapsed)
                VehicleSnapshot(
                    linkId = schedule.linkId,
                    vehicleId = schedule.vehicleId,
                    vehicleClass = schedule.vehicleClass,
                    laneIndex = schedule.laneIndex,
                    positionMeters = position,
                    speedMetersPerSecond = speed,
                    lengthMeters = schedule.lengthMeters,
                    widthMeters = schedule.widthMeters,
                    queuedAtEntry = queued
                )
            }
    }

    private data class Candidate(
        val link: NetworkTrafficLink,
        val departure: VehicleDepartureEvent
    )

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
                        // Deterministic partition: every vehicle is assigned to at most one link.
                        val bucket = Math.floorMod(departure.vehicleId * 7919L, 10000L) / 100.0
                        val selected = cumulative.firstOrNull { bucket < it.second }?.first
                            ?: return@departureLoop
                        candidatesByLink.getOrPut(selected.id) { mutableListOf() }
                            .add(Candidate(selected, departure))
                    }
            }

        val arrivals = mutableMapOf<String, MutableList<ExternalVehicleArrival>>()
        val metrics = mutableListOf<LinkMetrics>()
        val schedules = mutableListOf<VehicleSchedule>()
        enabled.forEach { link ->
            val candidates = candidatesByLink[link.id].orEmpty().sortedWith(
                compareBy<Candidate> { it.departure.timeSeconds }.thenBy { it.departure.vehicleId }
            )
            val nextEntryAt = DoubleArray(link.laneCount)
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
                val entryTime = max(candidate.departure.timeSeconds, nextEntryAt[laneIndex])
                val entryDelay = entryTime - candidate.departure.timeSeconds
                nextEntryAt[laneIndex] = entryTime + safeHeadway
                val acceleration = vehicleModel.accelerationMetersPerSecondSquared.coerceAtLeast(0.1)
                val accelTime = speed / acceleration
                val accelDistance = 0.5 * acceleration * accelTime * accelTime
                val travelTime = if (link.distanceMeters <= accelDistance) {
                    sqrt(2.0 * link.distanceMeters / acceleration)
                } else {
                    accelTime + (link.distanceMeters - accelDistance) / speed
                }
                val arrivalTime = entryTime + travelTime
                entryDelays += entryDelay
                if (entryDelay > 1e-9) {
                    val queueLength = vehicleModel.lengthMeters + vehicleModel.minimumGapMeters
                    queueEvents += Triple(candidate.departure.timeSeconds, 1, queueLength)
                    queueEvents += Triple(entryTime, -1, -queueLength)
                }
                schedules += VehicleSchedule(
                    linkId = link.id,
                    vehicleId = candidate.departure.vehicleId,
                    vehicleClass = candidate.departure.vehicleClass,
                    laneIndex = laneIndex,
                    departureTimeSeconds = candidate.departure.timeSeconds,
                    entryTimeSeconds = entryTime,
                    arrivalTimeSeconds = arrivalTime,
                    distanceMeters = link.distanceMeters,
                    accelerationMetersPerSecondSquared = acceleration,
                    cruiseSpeedMetersPerSecond = speed,
                    lengthMeters = vehicleModel.lengthMeters,
                    widthMeters = vehicleModel.widthMeters
                )
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
                    if (queueLength > link.distanceMeters * link.laneCount) spillbackVehicles++
                }
            metrics += LinkMetrics(
                linkId = link.id,
                vehiclesEntered = candidates.size,
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
