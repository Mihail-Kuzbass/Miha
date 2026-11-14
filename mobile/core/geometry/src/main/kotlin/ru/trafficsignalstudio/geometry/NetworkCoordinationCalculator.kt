package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.CoordinationDirection
import ru.trafficsignalstudio.model.NetworkCoordinationSettings
import ru.trafficsignalstudio.model.NetworkTrafficLink
import ru.trafficsignalstudio.model.RoadNetwork
import kotlin.math.roundToInt

/**
 * Calculates signal start offsets along configured network links.
 * The first discovered route from the reference node is used for each node;
 * disconnected nodes retain a zero offset.
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
        val offsets = mutableMapOf(root to 0)
        val queue = ArrayDeque<String>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val currentOffset = offsets.getValue(current)
            outgoing[current].orEmpty().forEach { link ->
                if (link.toIntersectionId in offsets) return@forEach
                val travelSeconds = (link.distanceMeters / (settings.progressionSpeedKmh / 3.6)).roundToInt()
                offsets[link.toIntersectionId] =
                    (currentOffset + travelSeconds).mod(settings.cycleLengthSeconds)
                queue.addLast(link.toIntersectionId)
            }
        }
        return nodes.associateWith { offsets[it] ?: 0 }
    }
}
