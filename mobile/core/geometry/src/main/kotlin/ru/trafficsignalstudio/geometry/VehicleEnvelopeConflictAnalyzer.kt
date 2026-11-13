package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.Point2D
import kotlin.math.hypot

/** Conservative 2D swept-width screening; not a time-domain or regulatory safety model. */
data class SweptEnvelopeConflict(
    val firstConnectionId: String,
    val secondConnectionId: String,
    val minimumCenterlineDistanceMeters: Double,
    val requiredClearanceMeters: Double,
    val point: Point2D,
    val centerlinesIntersect: Boolean
)

object VehicleEnvelopeConflictAnalyzer {
    const val DEFAULT_VEHICLE_WIDTH_METERS = 2.5
    private data class PairPoints(val first: Point2D, val second: Point2D) {
        val distance get() = hypot(first.xMeters - second.xMeters, first.yMeters - second.yMeters)
        val midpoint get() = Point2D((first.xMeters + second.xMeters) / 2.0, (first.yMeters + second.yMeters) / 2.0)
    }

    fun analyze(trajectories: List<MovementTrajectory>): List<SweptEnvelopeConflict> {
        data class Segment(
            val start: Point2D,
            val end: Point2D,
            val minX: Double,
            val maxX: Double,
            val minY: Double,
            val maxY: Double
        )
        val segmentsByConnection = trajectories.associate { trajectory ->
            trajectory.connectionId to trajectory.points.zipWithNext().map { (start, end) ->
                Segment(
                    start, end,
                    minOf(start.xMeters, end.xMeters), maxOf(start.xMeters, end.xMeters),
                    minOf(start.yMeters, end.yMeters), maxOf(start.yMeters, end.yMeters)
                )
            }
        }
        val results = mutableListOf<SweptEnvelopeConflict>()
        for (i in trajectories.indices) for (j in i + 1 until trajectories.size) {
            val first = trajectories[i]
            val second = trajectories[j]
            if (first.connectionId == second.connectionId || first.points.size < 2 || second.points.size < 2) continue
            val required = (first.vehicleWidthMeters + second.vehicleWidthMeters) / 2.0
            val firstSegments = segmentsByConnection[first.connectionId].orEmpty()
            val secondSegments = segmentsByConnection[second.connectionId].orEmpty()
            var closest: PairPoints? = null
            for (a in firstSegments) for (b in secondSegments) {
                // Conservative AABB rejection: if the expanded segment boxes
                // cannot approach within the required half-width clearance, the
                // exact segment-distance calculation cannot produce a conflict.
                if (a.maxX + required < b.minX || b.maxX + required < a.minX ||
                    a.maxY + required < b.minY || b.maxY + required < a.minY) continue
                val candidate = closestPair(a.start, a.end, b.start, b.end)
                if (closest == null || candidate.distance < closest.distance) closest = candidate
            }
            val pair = closest ?: continue
            if (pair.distance + 1e-6 < required) results += SweptEnvelopeConflict(
                first.connectionId, second.connectionId, pair.distance, required, pair.midpoint, pair.distance <= 1e-6
            )
        }
        return results
    }

    private fun closestPair(a: Point2D, b: Point2D, c: Point2D, d: Point2D): PairPoints {
        intersection(a, b, c, d)?.let { return PairPoints(it, it) }
        return listOf(
            PairPoints(a, project(a, c, d)), PairPoints(b, project(b, c, d)),
            PairPoints(project(c, a, b), c), PairPoints(project(d, a, b), d)
        ).minBy { it.distance }
    }

    private fun project(p: Point2D, a: Point2D, b: Point2D): Point2D {
        val dx = b.xMeters - a.xMeters; val dy = b.yMeters - a.yMeters
        val length = dx * dx + dy * dy
        if (length <= 1e-12) return a
        val t = (((p.xMeters - a.xMeters) * dx + (p.yMeters - a.yMeters) * dy) / length).coerceIn(0.0, 1.0)
        return Point2D(a.xMeters + t * dx, a.yMeters + t * dy)
    }

    private fun intersection(a: Point2D, b: Point2D, c: Point2D, d: Point2D): Point2D? {
        val rx = b.xMeters - a.xMeters; val ry = b.yMeters - a.yMeters
        val sx = d.xMeters - c.xMeters; val sy = d.yMeters - c.yMeters
        val denominator = rx * sy - ry * sx
        if (kotlin.math.abs(denominator) < 1e-9) return null
        val qx = c.xMeters - a.xMeters; val qy = c.yMeters - a.yMeters
        val t = (qx * sy - qy * sx) / denominator
        val u = (qx * ry - qy * rx) / denominator
        if (t !in 0.0..1.0 || u !in 0.0..1.0) return null
        return Point2D(a.xMeters + t * rx, a.yMeters + t * ry)
    }
}
