package ru.trafficsignalstudio.geometry

/**
 * Explicit right-of-way inputs for one pair of conflicting movements.
 *
 * Geometry and traffic-control interpretation are intentionally supplied by
 * the caller: the rules engine must not guess road signs or signal aspects
 * from incomplete geometry.
 */
enum class RoadPriority { MAIN, SECONDARY, EQUAL, UNDEFINED }
enum class RightOfWaySignal { PROTECTED_GREEN, PERMISSIVE_GREEN, YELLOW, RED, NO_SIGNAL }
enum class RightOfWayDecision { PROCEED, YIELD, STOP_AND_YIELD, CLEAR_INTERSECTION }

data class RightOfWaySituation(
    val ownRoadPriority: RoadPriority = RoadPriority.UNDEFINED,
    val otherRoadPriority: RoadPriority = RoadPriority.UNDEFINED,
    val ownSignal: RightOfWaySignal = RightOfWaySignal.NO_SIGNAL,
    val otherSignal: RightOfWaySignal = RightOfWaySignal.NO_SIGNAL,
    val ownTurningLeft: Boolean = false,
    val otherOncoming: Boolean = false,
    val otherGoingStraightOrRight: Boolean = false,
    val otherVehicleAlreadyInConflictZone: Boolean = false,
    val otherIsOnRight: Boolean = false,
    val ownIsEnteringRoundabout: Boolean = false,
    val otherAlreadyOnRoundabout: Boolean = false
)

/**
 * Deterministic first-stage Russian right-of-way rules.
 *
 * This component decides legal priority only. The physical conflict-zone and
 * collision-prevention layers remain authoritative for safe movement.
 */
object TrafficRulesEngine {
    fun decide(situation: RightOfWaySituation): RightOfWayDecision {
        // A vehicle already occupying the conflict area must be allowed to clear it.
        if (situation.otherVehicleAlreadyInConflictZone) {
            return RightOfWayDecision.YIELD
        }

        // A red signal always prohibits entry; a yellow signal requires stopping
        // unless the vehicle cannot stop safely before the stop line.
        when (situation.ownSignal) {
            RightOfWaySignal.RED -> return RightOfWayDecision.STOP_AND_YIELD
            RightOfWaySignal.YELLOW -> return RightOfWayDecision.STOP_AND_YIELD
            else -> Unit
        }

        // A permissive signal does not grant unconditional priority.
        if (situation.ownSignal == RightOfWaySignal.PERMISSIVE_GREEN &&
            situation.otherSignal == RightOfWaySignal.PROTECTED_GREEN) {
            return RightOfWayDecision.YIELD
        }

        // When signal indications conflict, obey the restrictive indication.
        if (situation.otherSignal == RightOfWaySignal.PROTECTED_GREEN &&
            situation.ownSignal != RightOfWaySignal.PROTECTED_GREEN) {
            return RightOfWayDecision.YIELD
        }

        if (situation.ownIsEnteringRoundabout && situation.otherAlreadyOnRoundabout) {
            return RightOfWayDecision.YIELD
        }

        // Leaving a secondary road: vehicles on the main road have priority.
        if (situation.ownRoadPriority == RoadPriority.SECONDARY &&
            situation.otherRoadPriority == RoadPriority.MAIN) {
            return RightOfWayDecision.YIELD
        }
        if (situation.ownRoadPriority == RoadPriority.MAIN &&
            situation.otherRoadPriority == RoadPriority.SECONDARY) {
            return RightOfWayDecision.PROCEED
        }

        // A left-turning vehicle yields to opposing traffic going straight or right.
        if (situation.ownTurningLeft && situation.otherOncoming &&
            situation.otherGoingStraightOrRight) {
            return RightOfWayDecision.YIELD
        }

        // At an equivalent uncontrolled crossing, give way to traffic from the right.
        if (situation.ownRoadPriority == RoadPriority.EQUAL &&
            situation.otherRoadPriority == RoadPriority.EQUAL &&
            situation.ownSignal == RightOfWaySignal.NO_SIGNAL &&
            situation.otherIsOnRight) {
            return RightOfWayDecision.YIELD
        }

        return RightOfWayDecision.PROCEED
    }
}

/** A deterministic simulated-time schedule: one violation opportunity per interval. */
data class ScheduledTrafficViolation(
    val sequence: Int,
    val timeSeconds: Int
)

object TrafficViolationSchedule {
    const val DEFAULT_INTERVAL_SECONDS = 15 * 60

    fun create(
        durationSeconds: Int,
        intervalSeconds: Int = DEFAULT_INTERVAL_SECONDS
    ): List<ScheduledTrafficViolation> {
        require(durationSeconds >= 0)
        require(intervalSeconds > 0)
        val result = mutableListOf<ScheduledTrafficViolation>()
        var time = intervalSeconds
        var sequence = 1
        while (time <= durationSeconds) {
            result += ScheduledTrafficViolation(sequence++, time)
            if (time > Int.MAX_VALUE - intervalSeconds) break
            time += intervalSeconds
        }
        return result
    }
}
