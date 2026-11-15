package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.Point2D
import kotlin.math.abs

data class PedestrianVehicleConflict(
    val crossingId: String,
    val connectionId: String
)

/**
 * Finds at-grade intersections between a pedestrian crossing centreline and
 * vehicle movement trajectories. This is a geometric screening check; it
 * does not replace swept-envelope or signal-clearance analysis.
 */
object PedestrianConflictAnalyzer {
    fun analyze(intersection: Intersection): List<PedestrianVehicleConflict> {
        if (intersection.pedestrianCrossings.isEmpty() || intersection.laneConnections.isEmpty()) return emptyList()
        val trajectories = TrajectoryConflictAnalyzer.buildMovementTrajectories(intersection)
        return buildList {
            intersection.pedestrianCrossings.forEach { crossing ->
                trajectories.forEach { trajectory ->
                    val intersects = trajectory.points.zipWithNext().any { (a, b) ->
                        segmentsIntersect(a, b, crossing.start, crossing.end)
                    }
                    if (intersects) add(PedestrianVehicleConflict(crossing.id, trajectory.connectionId))
                }
            }
        }.distinct()
    }

    private fun orientation(a: Point2D, b: Point2D, c: Point2D): Double =
        (b.xMeters - a.xMeters) * (c.yMeters - a.yMeters) -
            (b.yMeters - a.yMeters) * (c.xMeters - a.xMeters)

    private fun onSegment(a: Point2D, b: Point2D, p: Point2D): Boolean =
        p.xMeters >= minOf(a.xMeters, b.xMeters) - EPS &&
            p.xMeters <= maxOf(a.xMeters, b.xMeters) + EPS &&
            p.yMeters >= minOf(a.yMeters, b.yMeters) - EPS &&
            p.yMeters <= maxOf(a.yMeters, b.yMeters) + EPS

    private fun segmentsIntersect(a: Point2D, b: Point2D, c: Point2D, d: Point2D): Boolean {
        val o1 = orientation(a, b, c)
        val o2 = orientation(a, b, d)
        val o3 = orientation(c, d, a)
        val o4 = orientation(c, d, b)
        if (((o1 > EPS && o2 < -EPS) || (o1 < -EPS && o2 > EPS)) &&
            ((o3 > EPS && o4 < -EPS) || (o3 < -EPS && o4 > EPS))) return true
        return (abs(o1) <= EPS && onSegment(a, b, c)) ||
            (abs(o2) <= EPS && onSegment(a, b, d)) ||
            (abs(o3) <= EPS && onSegment(c, d, a)) ||
            (abs(o4) <= EPS && onSegment(c, d, b))
    }

    private const val EPS = 1e-8
}
