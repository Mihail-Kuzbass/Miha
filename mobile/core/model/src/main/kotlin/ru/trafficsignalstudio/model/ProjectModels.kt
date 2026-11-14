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
    /** Permissive flashing-green interval before yellow. */
    val flashingGreenSeconds: Int = 3,
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

enum class CoordinationDirection { FORWARD, REVERSE, BIDIRECTIONAL }

/** A directed traffic assignment from one intersection movement to another. */
data class NetworkTrafficLink(
    val id: String = UUID.randomUUID().toString(),
    val fromIntersectionId: String,
    val fromMovementConnectionId: String,
    val toIntersectionId: String,
    val toMovementConnectionId: String,
    val flowSharePercent: Double = 100.0,
    val distanceMeters: Double = 500.0,
    val enabled: Boolean = true,
    /** Free-flow travel speed on the link, used to schedule individual arrivals. */
    val travelSpeedKmh: Double = 30.0,
    /** Number of parallel lanes available on the inter-intersection road section. */
    val laneCount: Int = 1,
    /** Queue storage length before vehicles spill back into the upstream intersection, metres. */
    val queueStorageLengthMeters: Double = 120.0,
    /** Optional road centreline in local network coordinates; empty keeps legacy straight geometry. */
    val centerline: List<Point2D> = emptyList(),
    /** Physical width of each inter-node lane, metres. */
    val laneWidthMeters: Double = 3.5,
    /** Whether vehicles may perform safe lane changes while travelling on this link. */
    val laneChangesEnabled: Boolean = true
) {
    init {
        require(fromIntersectionId.isNotBlank() && toIntersectionId.isNotBlank())
        require(fromMovementConnectionId.isNotBlank() && toMovementConnectionId.isNotBlank())
        require(fromIntersectionId != toIntersectionId)
        require(flowSharePercent.isFinite() && flowSharePercent in 0.0..100.0)
        require(distanceMeters.isFinite() && distanceMeters in 1.0..100000.0)
        require(travelSpeedKmh.isFinite() && travelSpeedKmh in 5.0..120.0)
        require(laneCount in 1..8)
        require(queueStorageLengthMeters.isFinite() && queueStorageLengthMeters in 1.0..5000.0)
        require(centerline.isEmpty() || centerline.size >= 2) {
            "Геометрия межузловой дороги должна содержать минимум две точки."
        }
        require(centerline.all { it.xMeters.isFinite() && it.yMeters.isFinite() })
        require(centerline.isEmpty() || centerline.zipWithNext().sumOf { (a, b) ->
            kotlin.math.hypot(b.xMeters - a.xMeters, b.yMeters - a.yMeters)
        } > 1e-6) { "Геометрия межузловой дороги должна иметь ненулевую длину." }
        require(laneWidthMeters.isFinite() && laneWidthMeters in 2.5..5.0)
    }
}

data class NetworkCoordinationSettings(
    val enabled: Boolean = false,
    val cycleLengthSeconds: Int = 90,
    val progressionSpeedKmh: Double = 50.0,
    val referenceIntersectionId: String? = null,
    val direction: CoordinationDirection = CoordinationDirection.FORWARD,
    val offsetsSeconds: Map<String, Int> = emptyMap()
) {
    init {
        require(cycleLengthSeconds in 30..240)
        require(progressionSpeedKmh.isFinite() && progressionSpeedKmh in 5.0..120.0)
    }
}

data class RoadNetwork(
    val intersections: List<Intersection> = emptyList(),
    val trafficLinks: List<NetworkTrafficLink> = emptyList(),
    val coordination: NetworkCoordinationSettings = NetworkCoordinationSettings()
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
        const val CURRENT_SCHEMA_VERSION = 10

        fun newProject(name: String): ProjectDocument =
            ProjectDocument(name = name.trim().ifEmpty { "Новый проект" })
    }
}
