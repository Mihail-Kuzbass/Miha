package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.LaneTravelDirection
import ru.trafficsignalstudio.model.Point2D
import ru.trafficsignalstudio.model.RoadSegment
import kotlin.math.abs
import kotlin.math.hypot

enum class TrajectoryConflictKind {
    CROSSING,
    MERGING,
    SHARED_PATH
}

data class TrajectoryConflict(
    val firstSegmentId: String,
    val firstLaneId: String,
    val firstLaneIndex: Int,
    val secondSegmentId: String,
    val secondLaneId: String,
    val secondLaneIndex: Int,
    val point: Point2D,
    val kind: TrajectoryConflictKind
)

/**
 * Finds plan-view conflicts between individual lane centre trajectories.
 * Only roads on the same elevation level are compared. This is a geometric
 * screening tool, not a regulatory road-safety certification.
 */
object TrajectoryConflictAnalyzer {
    private const val EPS = 1e-8
    private const val DEDUPE_DISTANCE_METERS = 0.25

    private data class LanePath(
        val segment: RoadSegment,
        val laneId: String,
        val laneIndex: Int,
        val points: List<Point2D>
    )

    fun analyze(intersection: Intersection): List<TrajectoryConflict> {
        val paths = intersection.roadSegments.flatMap { segment -> buildLanePaths(segment) }
        val conflicts = mutableListOf<TrajectoryConflict>()
        for (i in paths.indices) {
            for (j in i + 1 until paths.size) {
                val first = paths[i]
                val second = paths[j]
                if (first.segment.id == second.segment.id) continue
                if (first.segment.elevationLevel != second.segment.elevationLevel) continue
                for (a in first.points.zipWithNext()) {
                    for (b in second.points.zipWithNext()) {
                        intersections(a.first, a.second, b.first, b.second).forEach { hit ->
                            val kind = when {
                                hit.overlap -> TrajectoryConflictKind.SHARED_PATH
                                hit.atEndpoint -> TrajectoryConflictKind.MERGING
                                else -> TrajectoryConflictKind.CROSSING
                            }
                            val conflict = TrajectoryConflict(
                                first.segment.id, first.laneId, first.laneIndex,
                                second.segment.id, second.laneId, second.laneIndex,
                                hit.point, kind
                            )
                            if (conflicts.none { existing ->
                                existing.firstSegmentId == conflict.firstSegmentId &&
                                    existing.firstLaneId == conflict.firstLaneId &&
                                    existing.secondSegmentId == conflict.secondSegmentId &&
                                    existing.secondLaneId == conflict.secondLaneId &&
                                    distance(existing.point, conflict.point) <= DEDUPE_DISTANCE_METERS
                            }) conflicts += conflict
                        }
                    }
                }
            }
        }
        return conflicts
    }

    private fun buildLanePaths(segment: RoadSegment): List<LanePath> {
        if (segment.centerline.size < 2) return emptyList()
        val lanes = IntersectionGeometryEditor.effectiveRoadLanes(segment)
        val totalWidth = lanes.sumOf { it.widthMeters }
        var offsetFromLeft = 0.0
        return lanes.map { lane ->
            val offset = -totalWidth / 2.0 + offsetFromLeft + lane.widthMeters / 2.0
            offsetFromLeft += lane.widthMeters
            var points = offsetPolyline(segment.centerline, offset)
            if (lane.travelDirection == LaneTravelDirection.AGAINST_CENTERLINE) points = points.reversed()
            LanePath(segment, lane.id, lane.index, points)
        }
    }

    private fun offsetPolyline(points: List<Point2D>, offset: Double): List<Point2D> {
        if (points.size < 2 || abs(offset) < EPS) return points
        return points.mapIndexed { index, point ->
            val before = points[(index - 1).coerceAtLeast(0)]
            val after = points[(index + 1).coerceAtMost(points.lastIndex)]
            val dx = after.xMeters - before.xMeters
            val dy = after.yMeters - before.yMeters
            val length = hypot(dx, dy).coerceAtLeast(EPS)
            Point2D(point.xMeters - dy / length * offset, point.yMeters + dx / length * offset)
        }
    }

    private data class Hit(val point: Point2D, val atEndpoint: Boolean, val overlap: Boolean)

    private fun intersections(a: Point2D, b: Point2D, c: Point2D, d: Point2D): List<Hit> {
        val rx = b.xMeters - a.xMeters
        val ry = b.yMeters - a.yMeters
        val sx = d.xMeters - c.xMeters
        val sy = d.yMeters - c.yMeters
        val denominator = cross(rx, ry, sx, sy)
        val qpx = c.xMeters - a.xMeters
        val qpy = c.yMeters - a.yMeters
        if (abs(denominator) < EPS) {
            if (abs(cross(qpx, qpy, rx, ry)) >= EPS) return emptyList()
            val lengthSquared = rx * rx + ry * ry
            if (lengthSquared < EPS) return emptyList()
            val t0 = (qpx * rx + qpy * ry) / lengthSquared
            val t1 = t0 + (sx * rx + sy * ry) / lengthSquared
            val low = maxOf(0.0, minOf(t0, t1))
            val high = minOf(1.0, maxOf(t0, t1))
            if (high < low - EPS) return emptyList()
            val t = (low + high) / 2.0
            val point = Point2D(a.xMeters + t * rx, a.yMeters + t * ry)
            return listOf(Hit(point, atEndpoint = high - low <= EPS, overlap = high - low > EPS))
        }
        val t = cross(qpx, qpy, sx, sy) / denominator
        val u = cross(qpx, qpy, rx, ry) / denominator
        if (t < -EPS || t > 1.0 + EPS || u < -EPS || u > 1.0 + EPS) return emptyList()
        val endpoint = t <= EPS || t >= 1.0 - EPS || u <= EPS || u >= 1.0 - EPS
        return listOf(Hit(Point2D(a.xMeters + t * rx, a.yMeters + t * ry), endpoint, false))
    }

    private fun cross(ax: Double, ay: Double, bx: Double, by: Double) = ax * by - ay * bx
    private fun distance(a: Point2D, b: Point2D) = hypot(a.xMeters - b.xMeters, a.yMeters - b.yMeters)
}
