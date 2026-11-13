package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.Approach
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.Lane
import ru.trafficsignalstudio.model.LaneConnection
import ru.trafficsignalstudio.model.LaneFlowDirection
import ru.trafficsignalstudio.model.LaneTravelDirection
import ru.trafficsignalstudio.model.MovementKind
import ru.trafficsignalstudio.model.Point2D
import ru.trafficsignalstudio.model.RoadSegment
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class TrajectoryConflictKind {
    CROSSING,
    MERGING,
    SHARED_PATH
}

enum class ConflictSignalStatus {
    /** Both movements are granted green in the same signal phase. */
    SIMULTANEOUS_GREEN,
    /** The movements belong to different sequential phases with clearance intervals. */
    SEPARATED_BY_PHASE,
    /** One or both movements have no phase assignment. */
    UNASSIGNED,
    /** No usable signal plan is configured; temporal safety cannot be assessed. */
    NO_SIGNAL_PLAN
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

data class MovementTrajectory(
    val connectionId: String,
    val fromApproachId: String,
    val fromLaneId: String,
    val toApproachId: String,
    val toLaneId: String,
    val movementKind: MovementKind,
    val points: List<Point2D>
)

data class MovementTrajectoryConflict(
    val firstConnectionId: String,
    val secondConnectionId: String,
    val firstMovement: MovementKind,
    val secondMovement: MovementKind,
    val point: Point2D,
    val kind: TrajectoryConflictKind,
    val signalStatus: ConflictSignalStatus,
    val firstPhaseName: String? = null,
    val secondPhaseName: String? = null
)

/**
 * Geometric screening for lane centre paths. It combines freeform road lanes
 * and explicit approach-to-approach lane connections. Signal phases are then
 * used to distinguish geometric intersections from simultaneous green conflicts.
 *
 * This is not a swept-vehicle-envelope, pedestrian, queue, or regulatory safety model.
 */
object TrajectoryConflictAnalyzer {
    private const val EPS = 1e-8
    private const val DEDUPE_DISTANCE_METERS = 0.25
    private const val CURVE_STEPS = 32

    private data class LanePath(
        val segment: RoadSegment,
        val laneId: String,
        val laneIndex: Int,
        val points: List<Point2D>
    )

    private data class Hit(val point: Point2D, val atEndpoint: Boolean, val overlap: Boolean)

    fun analyze(intersection: Intersection): List<TrajectoryConflict> {
        val paths = intersection.roadSegments.flatMap(::buildLanePaths)
        val conflicts = mutableListOf<TrajectoryConflict>()
        for (i in paths.indices) for (j in i + 1 until paths.size) {
            val first = paths[i]
            val second = paths[j]
            if (first.segment.id == second.segment.id ||
                first.segment.elevationLevel != second.segment.elevationLevel) continue
            collectHits(first.points, second.points).forEach { hit ->
                val conflict = TrajectoryConflict(
                    first.segment.id, first.laneId, first.laneIndex,
                    second.segment.id, second.laneId, second.laneIndex,
                    hit.point, hit.kind()
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
        return conflicts
    }

    /** Build smooth, lane-specific Bézier paths for every configured lane connection. */
    fun buildMovementTrajectories(intersection: Intersection): List<MovementTrajectory> =
        intersection.laneConnections.mapNotNull { connection ->
            val fromApproach = intersection.approaches.firstOrNull { it.id == connection.fromApproachId } ?: return@mapNotNull null
            val toApproach = intersection.approaches.firstOrNull { it.id == connection.toApproachId } ?: return@mapNotNull null
            val fromLane = fromApproach.lanes.firstOrNull { it.id == connection.fromLaneId } ?: return@mapNotNull null
            val toLane = toApproach.lanes.firstOrNull { it.id == connection.toLaneId } ?: return@mapNotNull null
            if (fromLane.flowDirection != LaneFlowDirection.INTO_INTERSECTION ||
                toLane.flowDirection != LaneFlowDirection.OUT_OF_INTERSECTION) return@mapNotNull null
            MovementTrajectory(
                connection.id, fromApproach.id, fromLane.id, toApproach.id, toLane.id,
                classifyMovement(fromApproach, toApproach),
                buildConnectionCurve(fromApproach, fromLane, toApproach, toLane)
            )
        }

    /**
     * Geometric conflict pairs annotated with signal timing. Different phases
     * are considered separated only when both movements have exactly one
     * valid phase assignment and the plan is enabled.
     */
    fun analyzeMovementConflicts(intersection: Intersection): List<MovementTrajectoryConflict> {
        val trajectories = buildMovementTrajectories(intersection)
        val plan = intersection.signalPlan?.takeIf { it.enabled && it.phases.isNotEmpty() }
        val phaseAssignments = plan?.phases.orEmpty().flatMap { phase ->
            phase.greenConnectionIds.map { it to phase }
        }.groupBy({ it.first }, { it.second })
        val results = mutableListOf<MovementTrajectoryConflict>()
        for (i in trajectories.indices) for (j in i + 1 until trajectories.size) {
            val first = trajectories[i]
            val second = trajectories[j]
            collectHits(first.points, second.points).forEach { hit ->
                val firstPhases = phaseAssignments[first.connectionId].orEmpty()
                val secondPhases = phaseAssignments[second.connectionId].orEmpty()
                val status = when {
                    plan == null -> ConflictSignalStatus.NO_SIGNAL_PLAN
                    firstPhases.size != 1 || secondPhases.size != 1 ->
                        ConflictSignalStatus.UNASSIGNED
                    firstPhases.single().id == secondPhases.single().id ->
                        ConflictSignalStatus.SIMULTANEOUS_GREEN
                    else -> ConflictSignalStatus.SEPARATED_BY_PHASE
                }
                val conflict = MovementTrajectoryConflict(
                    first.connectionId, second.connectionId,
                    first.movementKind, second.movementKind,
                    hit.point, hit.kind(), status,
                    firstPhases.singleOrNull()?.name, secondPhases.singleOrNull()?.name
                )
                if (results.none { existing ->
                    existing.firstConnectionId == conflict.firstConnectionId &&
                        existing.secondConnectionId == conflict.secondConnectionId &&
                        distance(existing.point, conflict.point) <= DEDUPE_DISTANCE_METERS
                }) results += conflict
            }
        }
        return results
    }

    private fun classifyMovement(from: Approach, to: Approach): MovementKind {
        val incomingHeading = normalizeDegrees(from.headingDegrees + 180.0)
        val delta = signedAngle(incomingHeading, normalizeDegrees(to.headingDegrees))
        return when {
            abs(delta) <= 30.0 -> MovementKind.THROUGH
            abs(delta) >= 150.0 -> MovementKind.U_TURN
            delta > 0.0 -> MovementKind.RIGHT
            else -> MovementKind.LEFT
        }
    }

    private fun buildConnectionCurve(
        from: Approach, fromLane: Lane, to: Approach, toLane: Lane
    ): List<Point2D> {
        val radius = maxOf(12.0, from.lanes.sumOf { it.widthMeters } + to.lanes.sumOf { it.widthMeters } + 4.0)
        fun laneOffset(approach: Approach, lane: Lane): Double {
            val sorted = approach.lanes.sortedBy { it.index }
            val total = sorted.sumOf { it.widthMeters }
            var before = 0.0
            for (item in sorted) {
                if (item.id == lane.id) return -total / 2.0 + before + item.widthMeters / 2.0
                before += item.widthMeters
            }
            return 0.0
        }
        fun pointAt(approach: Approach, lane: Lane, distance: Double): Point2D {
            val angle = Math.toRadians(approach.headingDegrees)
            val outwardX = sin(angle)
            val outwardY = cos(angle)
            val lateralX = cos(angle)
            val lateralY = -sin(angle)
            val offset = laneOffset(approach, lane)
            return Point2D(
                outwardX * distance + lateralX * offset,
                outwardY * distance + lateralY * offset
            )
        }
        val start = pointAt(from, fromLane, radius)
        val end = pointAt(to, toLane, radius)
        val fromAngle = Math.toRadians(from.headingDegrees)
        val toAngle = Math.toRadians(to.headingDegrees)
        val control1 = Point2D(
            start.xMeters - sin(fromAngle) * radius * 0.78,
            start.yMeters - cos(fromAngle) * radius * 0.78
        )
        val control2 = Point2D(
            end.xMeters - sin(toAngle) * radius * 0.78,
            end.yMeters - cos(toAngle) * radius * 0.78
        )
        return (0..CURVE_STEPS).map { index ->
            val t = index.toDouble() / CURVE_STEPS
            val u = 1.0 - t
            Point2D(
                u*u*u*start.xMeters + 3*u*u*t*control1.xMeters + 3*u*t*t*control2.xMeters + t*t*t*end.xMeters,
                u*u*u*start.yMeters + 3*u*u*t*control1.yMeters + 3*u*t*t*control2.yMeters + t*t*t*end.yMeters
            )
        }
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

    private fun collectHits(first: List<Point2D>, second: List<Point2D>): List<Hit> {
        val hits = mutableListOf<Hit>()
        for (a in first.zipWithNext()) for (b in second.zipWithNext()) {
            intersections(a.first, a.second, b.first, b.second).forEach { hit ->
                if (hits.none { distance(it.point, hit.point) <= DEDUPE_DISTANCE_METERS }) hits += hit
            }
        }
        return hits
    }

    private fun Hit.kind() = when {
        overlap -> TrajectoryConflictKind.SHARED_PATH
        atEndpoint -> TrajectoryConflictKind.MERGING
        else -> TrajectoryConflictKind.CROSSING
    }

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
            return listOf(Hit(Point2D(a.xMeters + t * rx, a.yMeters + t * ry),
                atEndpoint = high - low <= EPS, overlap = high - low > EPS))
        }
        val t = cross(qpx, qpy, sx, sy) / denominator
        val u = cross(qpx, qpy, rx, ry) / denominator
        if (t < -EPS || t > 1.0 + EPS || u < -EPS || u > 1.0 + EPS) return emptyList()
        val endpoint = t <= EPS || t >= 1.0 - EPS || u <= EPS || u >= 1.0 - EPS
        return listOf(Hit(Point2D(a.xMeters + t * rx, a.yMeters + t * ry), endpoint, false))
    }

    private fun normalizeDegrees(value: Double) = ((value % 360.0) + 360.0) % 360.0
    private fun signedAngle(from: Double, to: Double): Double {
        var value = (to - from + 540.0) % 360.0 - 180.0
        if (value <= -180.0) value += 360.0
        return value
    }
    private fun cross(ax: Double, ay: Double, bx: Double, by: Double) = ax * by - ay * bx
    private fun distance(a: Point2D, b: Point2D) = hypot(a.xMeters - b.xMeters, a.yMeters - b.yMeters)
}
