package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.Intersection

enum class CrashRiskBand { FOLLOWING_DISTANCE, INTERSECTION_CONFLICT, MULTI_VEHICLE }

data class CrashRiskAssessment(
    val riskPercent: Double,
    val geometricConflictCount: Int,
    val hourlyDemandVehicles: Double,
    val band: CrashRiskBand,
    val conflicts: List<MovementTrajectoryConflict>
)

/** Transparent scenario-risk indicator, not a calibrated real-world crash prediction. */
object CrashRiskAnalyzer {
    fun assess(intersection: Intersection, demand: TrafficSimulationDemand): CrashRiskAssessment {
        val conflicts = TrajectoryConflictAnalyzer.analyzeMovementConflicts(intersection)
        val flow = demand.movements.sumOf { it.totalVehiclesPerHour }.coerceAtLeast(0.0)
        val flowComponent = (flow / 1000.0 * 2.5).coerceAtMost(9.9)
        val conflictComponent = conflicts.sumOf { conflict ->
            when (conflict.signalStatus) {
                ConflictSignalStatus.SIMULTANEOUS_GREEN -> 12.0
                ConflictSignalStatus.PHASES_SEPARATED_NO_CLEARANCE -> 8.0
                ConflictSignalStatus.UNASSIGNED, ConflictSignalStatus.NO_SIGNAL_PLAN -> 6.0
                ConflictSignalStatus.SEPARATED_BY_PHASE -> 3.0
            }
        }
        val risk = (flowComponent + conflictComponent).coerceIn(0.0, 100.0)
        val band = when {
            risk < 10.0 -> CrashRiskBand.FOLLOWING_DISTANCE
            risk < 40.0 -> CrashRiskBand.INTERSECTION_CONFLICT
            else -> CrashRiskBand.MULTI_VEHICLE
        }
        return CrashRiskAssessment(risk, conflicts.size, flow, band, conflicts)
    }
}
