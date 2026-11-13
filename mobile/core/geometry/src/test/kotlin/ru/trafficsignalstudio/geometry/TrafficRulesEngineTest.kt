package ru.trafficsignalstudio.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficRulesEngineTest {
    @Test fun leftTurnYieldsToOncomingStraightTraffic() {
        val decision = TrafficRulesEngine.decide(RightOfWaySituation(
            ownTurningLeft = true,
            otherOncoming = true,
            otherGoingStraightOrRight = true
        ))
        assertEquals(RightOfWayDecision.YIELD, decision)
    }

    @Test fun secondaryRoadYieldsToMainRoad() {
        val decision = TrafficRulesEngine.decide(RightOfWaySituation(
            ownRoadPriority = RoadPriority.SECONDARY,
            otherRoadPriority = RoadPriority.MAIN
        ))
        assertEquals(RightOfWayDecision.YIELD, decision)
    }

    @Test fun mainRoadHasPriorityOverSecondaryRoad() {
        val decision = TrafficRulesEngine.decide(RightOfWaySituation(
            ownRoadPriority = RoadPriority.MAIN,
            otherRoadPriority = RoadPriority.SECONDARY
        ))
        assertEquals(RightOfWayDecision.PROCEED, decision)
    }

    @Test fun equalUncontrolledIntersectionYieldsToRight() {
        val decision = TrafficRulesEngine.decide(RightOfWaySituation(
            ownRoadPriority = RoadPriority.EQUAL,
            otherRoadPriority = RoadPriority.EQUAL,
            otherIsOnRight = true
        ))
        assertEquals(RightOfWayDecision.YIELD, decision)
    }

    @Test fun redSignalRequiresStop() {
        val decision = TrafficRulesEngine.decide(RightOfWaySituation(
            ownSignal = RightOfWaySignal.RED
        ))
        assertEquals(RightOfWayDecision.STOP_AND_YIELD, decision)
    }

    @Test fun permissiveGreenYieldsToProtectedGreen() {
        val decision = TrafficRulesEngine.decide(RightOfWaySituation(
            ownSignal = RightOfWaySignal.PERMISSIVE_GREEN,
            otherSignal = RightOfWaySignal.PROTECTED_GREEN
        ))
        assertEquals(RightOfWayDecision.YIELD, decision)
    }

    @Test fun vehicleAlreadyInConflictZoneMustBeAllowedToClear() {
        val decision = TrafficRulesEngine.decide(RightOfWaySituation(
            otherVehicleAlreadyInConflictZone = true
        ))
        assertEquals(RightOfWayDecision.YIELD, decision)
    }

    @Test fun roundaboutEntryYieldsToTrafficAlreadyOnRoundabout() {
        val decision = TrafficRulesEngine.decide(RightOfWaySituation(
            ownIsEnteringRoundabout = true,
            otherAlreadyOnRoundabout = true
        ))
        assertEquals(RightOfWayDecision.YIELD, decision)
    }

    @Test fun violationScheduleUsesSimulationTimeEveryFifteenMinutes() {
        assertEquals(
            listOf(900, 1800, 2700, 3600),
            TrafficViolationSchedule.create(3600).map { it.timeSeconds }
        )
    }

    @Test fun noViolationIsScheduledBeforeFirstIntervalOrAfterDuration() {
        assertEquals(emptyList<ScheduledTrafficViolation>(), TrafficViolationSchedule.create(899))
        assertEquals(listOf(900), TrafficViolationSchedule.create(901).map { it.timeSeconds })
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidViolationIntervalIsRejected() {
        TrafficViolationSchedule.create(3600, 0)
    }
}
