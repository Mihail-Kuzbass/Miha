package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.NetworkTrafficLink
import kotlin.math.ceil

/**
 * Runs an entire directed road network on one finite model-time horizon.
 * Iterative propagation supports both acyclic networks and closed circulation loops.
 */
object NetworkTrafficNetworkSolver {
    data class Result<T>(
        val nodeResults: Map<String, T>,
        val linkMetrics: List<NetworkLinkTrafficModel.LinkMetrics>,
        val propagationIterations: Int,
        val converged: Boolean
    )

    fun <T> run(
        intersectionIds: List<String>,
        links: List<NetworkTrafficLink>,
        durationSeconds: Int,
        simulateIntersection: (intersectionId: String, incoming: List<ExternalVehicleArrival>) -> T,
        departuresOf: (T) -> List<VehicleDepartureEvent>
    ): Result<T> {
        require(intersectionIds.distinct().size == intersectionIds.size)
        require(durationSeconds > 0)
        val enabledLinks = links.filter { it.enabled }
        require(enabledLinks.all {
            it.fromIntersectionId in intersectionIds && it.toIntersectionId in intersectionIds
        }) { "Связь ссылается на отсутствующий перекрёсток." }

        var incoming: Map<String, List<ExternalVehicleArrival>> = emptyMap()
        var finalResults: Map<String, T> = emptyMap()
        var finalMetrics = emptyList<NetworkLinkTrafficModel.LinkMetrics>()
        var converged = enabledLinks.isEmpty()
        var iterations = 0
        val minTravelTime = enabledLinks.minOfOrNull {
            it.distanceMeters / (it.travelSpeedKmh / 3.6)
        } ?: durationSeconds.toDouble()
        val iterationLimit = minOf(
            2048,
            maxOf(32, ceil(durationSeconds / minTravelTime).toInt() + 2)
        )

        fun signature(events: Map<String, List<ExternalVehicleArrival>>): List<String> =
            events.flatMap { (nodeId, arrivals) ->
                arrivals.map { event ->
                    "$nodeId|${event.connectionId}|${(event.timeSeconds * 1000.0).toLong()}|" +
                        "${event.vehicleClass}|${event.sourceVehicleId}"
                }
            }.sorted()

        while (iterations < iterationLimit) {
            val current = linkedMapOf<String, T>()
            intersectionIds.forEach { id ->
                current[id] = simulateIntersection(id, incoming[id].orEmpty())
            }
            val routed = NetworkLinkTrafficModel.route(
                current.mapValues { departuresOf(it.value) },
                enabledLinks,
                durationSeconds
            )
            finalResults = current
            finalMetrics = routed.metrics
            val nextIncoming = routed.arrivalsByIntersection
            iterations++
            if (signature(incoming) == signature(nextIncoming)) {
                converged = true
                break
            }
            incoming = nextIncoming
        }
        return Result(finalResults, finalMetrics, iterations, converged)
    }
}
