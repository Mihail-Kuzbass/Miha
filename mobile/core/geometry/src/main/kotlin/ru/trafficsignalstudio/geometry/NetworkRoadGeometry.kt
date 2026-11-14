package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.Point2D
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Geometric representation of an inter-intersection road.
 *
 * The centreline is a polyline in network-local metre coordinates. Curves are
 * represented by sufficiently dense points; this keeps the model independent
 * of any particular editor or renderer.
 */
object NetworkRoadGeometry {
    data class Pose(
        val point: Point2D,
        /** Heading in radians, measured counter-clockwise from positive X. */
        val headingRadians: Double,
        val tangentX: Double,
        val tangentY: Double
    )

    fun length(centerline: List<Point2D>): Double {
        require(centerline.size >= 2) { "Дорога должна содержать минимум две точки." }
        return centerline.zipWithNext().sumOf { (a, b) ->
            hypot(b.xMeters - a.xMeters, b.yMeters - a.yMeters)
        }
    }

    fun poseAt(
        centerline: List<Point2D>,
        distanceMeters: Double,
        laneIndex: Int,
        laneCount: Int,
        laneWidthMeters: Double
    ): Pose {
        require(centerline.size >= 2)
        require(distanceMeters.isFinite() && distanceMeters >= 0.0)
        require(laneCount in 1..8 && laneIndex in 0 until laneCount)
        require(laneWidthMeters.isFinite() && laneWidthMeters > 0.0)

        var remaining = distanceMeters.coerceAtMost(length(centerline))
        var segmentStart = centerline.first()
        var segmentEnd = centerline[1]
        var segmentLength = 0.0
        for (index in 0 until centerline.lastIndex) {
            val a = centerline[index]
            val b = centerline[index + 1]
            val currentLength = hypot(b.xMeters - a.xMeters, b.yMeters - a.yMeters)
            if (currentLength <= 1e-9) continue
            if (remaining <= currentLength || index == centerline.lastIndex - 1) {
                segmentStart = a
                segmentEnd = b
                segmentLength = currentLength
                break
            }
            remaining -= currentLength
        }
        require(segmentLength > 1e-9) { "Геометрия дороги не содержит участков ненулевой длины." }
        val tx = (segmentEnd.xMeters - segmentStart.xMeters) / segmentLength
        val ty = (segmentEnd.yMeters - segmentStart.yMeters) / segmentLength
        val progress = (remaining / segmentLength).coerceIn(0.0, 1.0)
        val lateralOffset = (laneIndex - (laneCount - 1) / 2.0) * laneWidthMeters
        // Positive lane index is to the driver's left of the centreline direction.
        val x = segmentStart.xMeters + (segmentEnd.xMeters - segmentStart.xMeters) * progress - ty * lateralOffset
        val y = segmentStart.yMeters + (segmentEnd.yMeters - segmentStart.yMeters) * progress + tx * lateralOffset
        return Pose(Point2D(x, y), atan2(ty, tx), tx, ty)
    }
}
