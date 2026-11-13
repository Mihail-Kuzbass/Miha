package ru.trafficsignalstudio.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.IntersectionType
import ru.trafficsignalstudio.model.Approach
import ru.trafficsignalstudio.model.Lane
import ru.trafficsignalstudio.model.LaneConnection
import ru.trafficsignalstudio.model.LaneFlowDirection
import ru.trafficsignalstudio.model.MovementKind
import ru.trafficsignalstudio.model.SignalPhase
import ru.trafficsignalstudio.model.SignalPlan
import ru.trafficsignalstudio.model.Point2D
import ru.trafficsignalstudio.model.RoadSegment

class TrajectoryConflictAnalyzerTest {
    private fun crossingMovementIntersection(plan: SignalPlan? = null): Intersection {
        val northIn = Approach(id = "north-in", name = "Север", headingDegrees = 0.0,
            lanes = listOf(Lane(id = "north-in-lane", index = 1, flowDirection = LaneFlowDirection.INTO_INTERSECTION)))
        val southOut = Approach(id = "south-out", name = "Юг", headingDegrees = 180.0,
            lanes = listOf(Lane(id = "south-out-lane", index = 1, flowDirection = LaneFlowDirection.OUT_OF_INTERSECTION)))
        val westIn = Approach(id = "west-in", name = "Запад", headingDegrees = 270.0,
            lanes = listOf(Lane(id = "west-in-lane", index = 1, flowDirection = LaneFlowDirection.INTO_INTERSECTION)))
        val eastOut = Approach(id = "east-out", name = "Восток", headingDegrees = 90.0,
            lanes = listOf(Lane(id = "east-out-lane", index = 1, flowDirection = LaneFlowDirection.OUT_OF_INTERSECTION)))
        return Intersection(
            name = "Crossing movements",
            type = IntersectionType.FOUR_LEG,
            approaches = listOf(northIn, southOut, westIn, eastOut),
            laneConnections = listOf(
                LaneConnection(id = "north-through", fromApproachId = northIn.id, fromLaneId = "north-in-lane",
                    toApproachId = southOut.id, toLaneId = "south-out-lane"),
                LaneConnection(id = "west-through", fromApproachId = westIn.id, fromLaneId = "west-in-lane",
                    toApproachId = eastOut.id, toLaneId = "east-out-lane")
            ),
            signalPlan = plan
        )
    }

    @Test
    fun opposingThroughMovementsHaveGeometricCrossing() {
        val intersection = crossingMovementIntersection()
        val trajectories = TrajectoryConflictAnalyzer.buildMovementTrajectories(intersection)
        assertEquals(2, trajectories.size)
        assertTrue(trajectories.all { it.movementKind == MovementKind.THROUGH })
        val conflicts = TrajectoryConflictAnalyzer.analyzeMovementConflicts(intersection)
        assertEquals(1, conflicts.size)
        assertEquals(TrajectoryConflictKind.CROSSING, conflicts.single().kind)
        assertEquals(ConflictSignalStatus.NO_SIGNAL_PLAN, conflicts.single().signalStatus)
    }

    @Test
    fun movementsGrantedGreenInSamePhaseAreReportedAsSimultaneousConflict() {
        val plan = SignalPlan(phases = listOf(
            SignalPhase(id = "phase-1", name = "Основная", greenConnectionIds = setOf("north-through", "west-through"))
        ))
        val conflicts = TrajectoryConflictAnalyzer.analyzeMovementConflicts(crossingMovementIntersection(plan))
        assertEquals(ConflictSignalStatus.SIMULTANEOUS_GREEN, conflicts.single().signalStatus)
    }

    @Test
    fun movementsInDifferentPhasesAreGeometricButTemporallySeparated() {
        val plan = SignalPlan(phases = listOf(
            SignalPhase(id = "phase-1", name = "Север-юг", greenConnectionIds = setOf("north-through")),
            SignalPhase(id = "phase-2", name = "Запад-восток", greenConnectionIds = setOf("west-through"))
        ))
        val conflicts = TrajectoryConflictAnalyzer.analyzeMovementConflicts(crossingMovementIntersection(plan))
        assertEquals(1, conflicts.size)
        assertEquals(ConflictSignalStatus.SEPARATED_BY_PHASE, conflicts.single().signalStatus)
    }

    @Test
    fun differentPhasesWithoutYellowAndAllRedAreNotDeclaredFullySeparated() {
        val plan = SignalPlan(phases = listOf(
            SignalPhase(id = "phase-1", name = "Север-юг", greenConnectionIds = setOf("north-through"),
                yellowSeconds = 0, allRedSeconds = 0),
            SignalPhase(id = "phase-2", name = "Запад-восток", greenConnectionIds = setOf("west-through"))
        ))
        val conflicts = TrajectoryConflictAnalyzer.analyzeMovementConflicts(crossingMovementIntersection(plan))
        assertEquals(ConflictSignalStatus.PHASES_SEPARATED_NO_CLEARANCE, conflicts.single().signalStatus)
    }

    @Test
    fun unassignedMovementIsNotAssumedToBeSafelySeparated() {
        val plan = SignalPlan(phases = listOf(
            SignalPhase(id = "phase-1", name = "Север-юг", greenConnectionIds = setOf("north-through"))
        ))
        val conflicts = TrajectoryConflictAnalyzer.analyzeMovementConflicts(crossingMovementIntersection(plan))
        assertEquals(ConflictSignalStatus.UNASSIGNED, conflicts.single().signalStatus)
    }

    @Test
    fun crossingTwoLaneRoadsProduceLaneLevelConflicts() {
        val intersection = Intersection(
            name = "Crossing",
            type = IntersectionType.CUSTOM,
            approaches = emptyList(),
            roadSegments = listOf(
                RoadSegment(name = "East-West", centerline = listOf(Point2D(-10.0, 0.0), Point2D(10.0, 0.0)), widthMeters = 7.0, laneCount = 2),
                RoadSegment(name = "North-South", centerline = listOf(Point2D(0.0, -10.0), Point2D(0.0, 10.0)), widthMeters = 7.0, laneCount = 2)
            )
        )
        val conflicts = TrajectoryConflictAnalyzer.analyze(intersection)
        assertEquals(4, conflicts.size)
        assertTrue(conflicts.all { it.kind == TrajectoryConflictKind.CROSSING })
    }

    @Test
    fun gradeSeparatedRoadsDoNotConflict() {
        val intersection = Intersection(
            name = "Bridge",
            type = IntersectionType.CUSTOM,
            approaches = emptyList(),
            roadSegments = listOf(
                RoadSegment(name = "Ground", centerline = listOf(Point2D(-10.0, 0.0), Point2D(10.0, 0.0)), widthMeters = 7.0, laneCount = 2),
                RoadSegment(name = "Bridge", centerline = listOf(Point2D(0.0, -10.0), Point2D(0.0, 10.0)), widthMeters = 7.0, laneCount = 2, elevationLevel = 1)
            )
        )
        assertTrue(TrajectoryConflictAnalyzer.analyze(intersection).isEmpty())
    }

    @Test
    fun nonIntersectingRoadSegmentsProduceNoConflicts() {
        val intersection = Intersection(
            name = "Separate",
            type = IntersectionType.CUSTOM,
            approaches = emptyList(),
            roadSegments = listOf(
                RoadSegment(name = "A", centerline = listOf(Point2D(0.0, 0.0), Point2D(10.0, 0.0))),
                RoadSegment(name = "B", centerline = listOf(Point2D(0.0, 5.0), Point2D(10.0, 5.0)))
            )
        )
        assertTrue(TrajectoryConflictAnalyzer.analyze(intersection).isEmpty())
    }
}
