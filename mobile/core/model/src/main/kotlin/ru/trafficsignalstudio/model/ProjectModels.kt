package ru.trafficsignalstudio.model

import java.util.UUID

enum class IntersectionType {
    FOUR_LEG, T_JUNCTION, Y_JUNCTION, FIVE_LEG, SIX_LEG,
    ROUNDABOUT, DIAMOND_INTERCHANGE, CLOVERLEAF_INTERCHANGE, CUSTOM
}

enum class MovementKind {
    THROUGH, LEFT, RIGHT, U_TURN, TRANSIT_ONLY
}

/** Direction of traffic on a lane relative to the intersection node. */
enum class LaneFlowDirection {
    INTO_INTERSECTION,
    OUT_OF_INTERSECTION
}

data class Point2D(
    val xMeters: Double,
    val yMeters: Double
)

data class Lane(
    val id: String = UUID.randomUUID().toString(),
    val index: Int,
    val widthMeters: Double = 3.5,
    val allowedMovements: Set<MovementKind> = setOf(MovementKind.THROUGH),
    val flowDirection: LaneFlowDirection = LaneFlowDirection.INTO_INTERSECTION
)

data class Approach(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val headingDegrees: Double,
    val lanes: List<Lane>
)

/**
 * Independent road centerline. Points are local coordinates in metres,
 * relative to the intersection centre. A non-zero level represents a
 * grade-separated roadway and is not an at-grade conflict by itself.
 */
enum class RoadSegmentKind {
    STRAIGHT, POLYLINE, ARC, BRIDGE, RAMP
}

enum class LaneTravelDirection {
    ALONG_CENTERLINE,
    AGAINST_CENTERLINE
}

/** A lane belonging to a freely drawn road segment. */
data class RoadSegmentLane(
    val id: String = UUID.randomUUID().toString(),
    val index: Int,
    val widthMeters: Double = 3.5,
    val travelDirection: LaneTravelDirection = LaneTravelDirection.ALONG_CENTERLINE,
    val allowedMovements: Set<MovementKind> = setOf(MovementKind.THROUGH)
)

data class RoadSegment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val centerline: List<Point2D>,
    val widthMeters: Double = 7.0,
    val laneCount: Int = 2,
    val elevationLevel: Int = 0,
    val isRamp: Boolean = false,
    val kind: RoadSegmentKind = if (isRamp) RoadSegmentKind.RAMP else RoadSegmentKind.POLYLINE,
    val arcRadiusMeters: Double? = null,
    /** Empty means a legacy segment; lane definitions are synthesized from laneCount/widthMeters. */
    val lanes: List<RoadSegmentLane> = emptyList()
)

data class RoadJunctionNode(
    val id: String = UUID.randomUUID().toString(),
    val point: Point2D,
    val connectedSegmentIds: Set<String> = emptySet()
)

data class LaneConnection(
    val id: String = UUID.randomUUID().toString(),
    val fromApproachId: String,
    val fromLaneId: String,
    val toApproachId: String,
    val toLaneId: String
)

/** A signal phase grants green to the listed lane-to-lane movements. */
data class SignalPhase(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val greenConnectionIds: Set<String>,
    val greenSeconds: Int = 30,
    val yellowSeconds: Int = 3,
    val allRedSeconds: Int = 1
)

data class SignalPlan(
    val phases: List<SignalPhase> = emptyList(),
    /** If false, phase separation is not used to declare movements non-conflicting. */
    val enabled: Boolean = true
)

data class SafetyIsland(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Островок безопасности",
    val boundary: List<Point2D>,
    val elevationLevel: Int = 0
)

data class Intersection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: IntersectionType,
    val center: Point2D = Point2D(0.0, 0.0),
    val approaches: List<Approach>,
    val roadSegments: List<RoadSegment> = emptyList(),
    val junctionNodes: List<RoadJunctionNode> = emptyList(),
    val safetyIslands: List<SafetyIsland> = emptyList(),
    val laneConnections: List<LaneConnection> = emptyList(),
    val signalPlan: SignalPlan? = null
)

data class RoadNetwork(
    val intersections: List<Intersection> = emptyList()
)

data class ProjectDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val network: RoadNetwork = RoadNetwork()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 5

        fun newProject(name: String): ProjectDocument =
            ProjectDocument(name = name.trim().ifEmpty { "Новый проект" })
    }
}
