package ru.trafficsignalstudio.feature.editor

import ru.trafficsignalstudio.geometry.TrajectoryConflictAnalyzer
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.MovementKind

/** Human-readable labels for lane-to-lane movement connections. */
internal fun movementDisplayLabels(intersection: Intersection): Map<String, String> {
    val approaches = intersection.approaches.associateBy { it.id }
    fun cardinal(headingDegrees: Double): String {
        val normalized = ((headingDegrees % 360.0) + 360.0) % 360.0
        return when (((normalized + 45.0) / 90.0).toInt() % 4) {
            0 -> "север"
            1 -> "восток"
            2 -> "юг"
            else -> "запад"
        }
    }
    fun approachName(id: String, suffix: String): String {
        val approach = approaches[id] ?: return "Неизвестный подход"
        val roadName = approach.name.trim().ifBlank { "Подход" }
        return "$roadName (${cardinal(approach.headingDegrees)} $suffix)"
    }
    fun manoeuvre(kind: MovementKind): String = when (kind) {
        MovementKind.THROUGH -> "прямо"
        MovementKind.LEFT -> "налево"
        MovementKind.RIGHT -> "направо"
        MovementKind.U_TURN -> "разворот"
        MovementKind.TRANSIT_ONLY -> "транзит"
    }
    return TrajectoryConflictAnalyzer.buildMovementTrajectories(intersection).associate { trajectory ->
        trajectory.connectionId to (
            approachName(trajectory.fromApproachId, "подход") + " → " +
                approachName(trajectory.toApproachId, "выезд") + ", " + manoeuvre(trajectory.movementKind)
        )
    }
}
