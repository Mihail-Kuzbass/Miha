package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.CoordinationDirection
import ru.trafficsignalstudio.model.NetworkCoordinationSettings
import ru.trafficsignalstudio.model.RoadNetwork
import java.util.PriorityQueue
import kotlin.math.roundToInt

/**
 * Calculates signal-cycle start offsets from the reference intersection.
 * The shortest travel-time route is used when several network paths connect
 * the same nodes. Offsets are measured modulo the common cycle length.
 */
object NetworkCoordinationCalculator {
    fun calculateOffsets(
        network: RoadNetwork,
        settings: NetworkCoordinationSettings
    ): Map<String, Int> {
        val nodes = network.intersections.map { it.id }.toSet()
        if (nodes.isEmpty()) return emptyMap()
        val root = settings.referenceIntersectionId?.takeIf { it in nodes } ?: network.intersections.first().id
        val directed = buildList {
            network.trafficLinks.filter { it.enabled }.forEach { link ->
                if (settings.direction != CoordinationDirection.REVERSE) add(link)
                if (settings.direction != CoordinationDirection.FORWARD) {
                    add(link.copy(
                        fromIntersectionId = link.toIntersectionId,
                        toIntersectionId = link.fromIntersectionId
                    ))
                }
            }
        }.filter { it.fromIntersectionId in nodes && it.toIntersectionId in nodes }
        val outgoing = directed.groupBy { it.fromIntersectionId }
        val travelTimes = mutableMapOf(root to 0.0)
        val queue = PriorityQueue<Pair<String, Double>>(compareBy { it.second })
        queue.add(root to 0.0)

        while (queue.isNotEmpty()) {
            val (current, knownTime) = queue.remove()
            if (knownTime > (travelTimes[current] ?: Double.POSITIVE_INFINITY) + 1e-9) continue
            outgoing[current].orEmpty().forEach { link ->
                val edgeTime = link.distanceMeters / (settings.progressionSpeedKmh / 3.6)
                val candidate = knownTime + edgeTime
                if (candidate + 1e-9 < (travelTimes[link.toIntersectionId] ?: Double.POSITIVE_INFINITY)) {
                    travelTimes[link.toIntersectionId] = candidate
                    queue.add(link.toIntersectionId to candidate)
                }
            }
        }

        return nodes.associateWith { id ->
            val travelTime = travelTimes[id] ?: return@associateWith 0
            travelTime.roundToInt().mod(settings.cycleLengthSeconds)
        }
    }
}
