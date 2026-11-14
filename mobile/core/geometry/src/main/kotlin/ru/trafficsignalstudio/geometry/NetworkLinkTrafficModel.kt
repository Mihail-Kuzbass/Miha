package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.NetworkTrafficLink
import ru.trafficsignalstudio.model.VehicleModelCatalog
import kotlin.math.max

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
        val maximumEntryDelaySeconds: Double
    )

    data class Result(
        val arrivalsByIntersection: Map<String, List<ExternalVehicleArrival>>,
        val metrics: List<LinkMetrics>
    )

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
                    .forEach { departure ->
                        // Deterministic partition: every vehicle is assigned to at most one link.
                        val bucket = Math.floorMod(departure.vehicleId * 7919L, 10000L) / 100.0
                        val selected = cumulative.firstOrNull { bucket < it.second }?.first
                            ?: return@forEach
                        candidatesByLink.getOrPut(selected.id) { mutableListOf() }
                            .add(Candidate(selected, departure))
                    }
            }

        val arrivals = mutableMapOf<String, MutableList<ExternalVehicleArrival>>()
        val metrics = mutableListOf<LinkMetrics>()
        enabled.forEach { link ->
            val candidates = candidatesByLink[link.id].orEmpty().sortedWith(
                compareBy<Candidate> { it.departure.timeSeconds }.thenBy { it.departure.vehicleId }
            )
            val nextEntryAt = DoubleArray(link.laneCount)
            val entryDelays = mutableListOf<Double>()
            val queueEvents = mutableListOf<Pair<Double, Int>>()
            var arrivedWithinHorizon = 0
            candidates.forEach { candidate ->
                val vehicleModel = candidate.departure.vehicleClass?.let { vehicleClass ->
                    VehicleModelCatalog.defaults.firstOrNull { it.vehicleClass == vehicleClass }
                } ?: VehicleModelCatalog.defaultPassengerCar
                val speed = link.travelSpeedKmh / 3.6
                val safeHeadway = max(
                    vehicleModel.desiredTimeHeadwaySeconds,
                    (vehicleModel.lengthMeters + vehicleModel.minimumGapMeters) / speed
                )
                val laneIndex = nextEntryAt.indices.minByOrNull { nextEntryAt[it] } ?: 0
                val entryTime = max(candidate.departure.timeSeconds, nextEntryAt[laneIndex])
                val entryDelay = entryTime - candidate.departure.timeSeconds
                nextEntryAt[laneIndex] = entryTime + safeHeadway
                val freeFlowTravelTime = link.distanceMeters / speed
                val arrivalTime = entryTime + freeFlowTravelTime
                entryDelays += entryDelay
                if (entryDelay > 1e-9) {
                    queueEvents += candidate.departure.timeSeconds to 1
                    queueEvents += entryTime to -1
                }
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
            queueEvents.sortedWith(compareBy<Pair<Double, Int>> { it.first }.thenBy { it.second })
                .forEach { (_, delta) ->
                    queue = (queue + delta).coerceAtLeast(0)
                    maximumQueue = max(maximumQueue, queue)
                }
            metrics += LinkMetrics(
                linkId = link.id,
                vehiclesEntered = candidates.size,
                vehiclesArrivedWithinHorizon = arrivedWithinHorizon,
                maximumEntryQueue = maximumQueue,
                averageEntryDelaySeconds = entryDelays.average().takeIf { it.isFinite() } ?: 0.0,
                maximumEntryDelaySeconds = entryDelays.maxOrNull() ?: 0.0
            )
        }
        return Result(
            arrivalsByIntersection = arrivals.mapValues { it.value.sortedBy(ExternalVehicleArrival::timeSeconds) },
            metrics = metrics
        )
    }
}
