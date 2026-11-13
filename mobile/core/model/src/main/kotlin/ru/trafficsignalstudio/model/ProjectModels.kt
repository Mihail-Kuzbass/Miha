package ru.trafficsignalstudio.model

import java.util.UUID

enum class IntersectionType {
    FOUR_LEG, T_JUNCTION, Y_JUNCTION, ROUNDABOUT, CUSTOM
}

enum class MovementKind {
    THROUGH, LEFT, RIGHT, U_TURN, TRANSIT_ONLY
}

data class Point2D(
    val xMeters: Double,
    val yMeters: Double
)

data class Lane(
    val id: String = UUID.randomUUID().toString(),
    val index: Int,
    val widthMeters: Double = 3.5,
    val allowedMovements: Set<MovementKind> = setOf(MovementKind.THROUGH)
)

data class Approach(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val headingDegrees: Double,
    val lanes: List<Lane>
)

data class Intersection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: IntersectionType,
    val center: Point2D = Point2D(0.0, 0.0),
    val approaches: List<Approach>
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
        const val CURRENT_SCHEMA_VERSION = 1

        fun newProject(name: String): ProjectDocument =
            ProjectDocument(name = name.trim().ifEmpty { "Новый проект" })
    }
}
