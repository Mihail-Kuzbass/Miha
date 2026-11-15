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
import ru.trafficsignalstudio.model.PedestrianCrossing
import ru.trafficsignalstudio.model.Point2D
import ru.trafficsignalstudio.model.SignalPhase
import ru.trafficsignalstudio.model.SignalPlan

class PedestrianSimulationEngineTest {
    private fun crossing(length: Double = 10.0) = PedestrianCrossing(
        id = "crossing-1",
        name = "Переход север",
        start = Point2D(0.0, -length / 2.0),
        end = Point2D(0.0, length / 2.0),
        widthMeters = 4.0
    )

    private fun intersection(
        crossing: PedestrianCrossing = crossing(),
        greenSeconds: Int = 20,
        assigned: Boolean = true
    ) = Intersection(
        id = "pedestrian-test",
        name = "Тестовый перекрёсток",
        type = IntersectionType.CUSTOM,
        approaches = emptyList(),
        pedestrianCrossings = listOf(crossing),
        signalPlan = SignalPlan(phases = listOf(
            SignalPhase(
                id = "ped-phase",
                name = "Пешеходная фаза",
                greenConnectionIds = emptySet(),
                greenSeconds = greenSeconds,
                redYellowSeconds = 0,
                flashingGreenSeconds = 0,
                yellowSeconds = 3,
                allRedSeconds = 1,
                greenCrosswalkIds = if (assigned) setOf(crossing.id) else emptySet()
            )
        ))
    )

    @Test
    fun pedestriansArriveWaitForGreenAndCompleteCrossing() {
        val result = PedestrianSimulationEngine.run(
            intersection(),
            listOf(PedestrianFlowDemand("crossing-1", pedestriansPerHour = 360.0)),
            durationSeconds = 120
        )
        assertTrue(result.totalArrivals > 0)
        assertTrue(result.totalCompleted > 0)
        assertTrue(result.snapshots.any { it.activePedestrians.any { pedestrian -> !pedestrian.waiting } })
        assertTrue(result.averageWaitingSeconds >= 0.0)
    }

    @Test
    fun unassignedCrossingKeepsPedestriansWaiting() {
        val result = PedestrianSimulationEngine.run(
            intersection(assigned = false),
            listOf(PedestrianFlowDemand("crossing-1", 360.0)),
            durationSeconds = 120
        )
        assertTrue(result.totalArrivals > 0)
        assertEquals(0, result.totalCompleted)
        assertTrue(result.totalWaitingAtEnd > 0)
        assertTrue(result.diagnostics.any { it.contains("не назначен") })
    }

    @Test
    fun pedestriansDoNotEnterWhenGreenCannotProvideClearanceTime() {
        val result = PedestrianSimulationEngine.run(
            intersection(crossing(length = 30.0), greenSeconds = 5),
            listOf(PedestrianFlowDemand("crossing-1", 360.0)),
            durationSeconds = 120
        )
        assertEquals(0, result.totalCompleted)
        assertTrue(result.totalWaitingAtEnd > 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnreasonableWalkingSpeed() {
        PedestrianFlowDemand("crossing-1", 100.0, walkingSpeedMetersPerSecond = 3.0)
    }

    @Test
    fun pedestrianOnlySignalPhaseIsValidForCrossing() {
        val report = SignalPlanTimingAnalyzer.analyze(intersection())
        assertTrue(report.diagnostics.toString(), report.isValid)
        assertEquals(1, report.phaseWindows.size)
    }
    @Test
    fun samePhaseVehicleAndPedestrianGreenIsRejectedWhenPathsIntersect() {
        val crossing = PedestrianCrossing(
            id = "crossing-conflict",
            name = "Переход через основную дорогу",
            start = Point2D(-5.0, 0.0),
            end = Point2D(5.0, 0.0),
            widthMeters = 4.0
        )
        val north = Approach(
            id = "north",
            name = "Север",
            headingDegrees = 0.0,
            lanes = listOf(Lane(id = "north-in", index = 1))
        )
        val south = Approach(
            id = "south",
            name = "Юг",
            headingDegrees = 180.0,
            lanes = listOf(Lane(id = "south-out", index = 1,
                flowDirection = LaneFlowDirection.OUT_OF_INTERSECTION))
        )
        val intersection = Intersection(
            id = "conflict-test",
            name = "Проверка конфликта",
            type = IntersectionType.CUSTOM,
            approaches = listOf(north, south),
            laneConnections = listOf(LaneConnection(
                id = "north-south",
                fromApproachId = north.id,
                fromLaneId = "north-in",
                toApproachId = south.id,
                toLaneId = "south-out"
            )),
            signalPlan = SignalPlan(phases = listOf(SignalPhase(
                id = "shared-phase",
                name = "Общая фаза",
                greenConnectionIds = setOf("north-south"),
                greenSeconds = 20,
                redYellowSeconds = 0,
                flashingGreenSeconds = 0,
                yellowSeconds = 3,
                allRedSeconds = 1,
                greenCrosswalkIds = setOf(crossing.id)
            ))),
            pedestrianCrossings = listOf(crossing)
        )
        assertTrue(PedestrianConflictAnalyzer.analyze(intersection).any {
            it.crossingId == crossing.id && it.connectionId == "north-south"
        })
        val report = SignalPlanTimingAnalyzer.analyze(intersection)
        assertTrue(report.diagnostics.toString(), report.diagnostics.any {
            it.code == SignalPlanIssueCode.PEDESTRIAN_VEHICLE_CONFLICT
        })
        assertTrue(IntersectionGeometryEditor.validate(intersection).any {
            it.contains("одновременно разрешает пешеходный переход")
        })
    }

    @Test
    fun conflictCheckIncludesCrosswalkBandAndVehicleBodyWidth() {
        val crossing = PedestrianCrossing(
            id = "nearby-crossing",
            name = "Переход рядом с траекторией",
            start = Point2D(2.5, -5.0),
            end = Point2D(2.5, 5.0),
            widthMeters = 4.0
        )
        val north = Approach("north", "Север", 0.0, listOf(Lane(id = "north-in", index = 1)))
        val south = Approach("south", "Юг", 180.0, listOf(Lane(
            id = "south-out", index = 1, flowDirection = LaneFlowDirection.OUT_OF_INTERSECTION
        )))
        val intersection = Intersection(
            name = "Проверка ширины перехода",
            type = IntersectionType.CUSTOM,
            approaches = listOf(north, south),
            laneConnections = listOf(LaneConnection(
                id = "north-south", fromApproachId = "north", fromLaneId = "north-in",
                toApproachId = "south", toLaneId = "south-out"
            )),
            pedestrianCrossings = listOf(crossing)
        )
        assertTrue("Траектория проходит в пределах габарита перехода и ТС.",
            PedestrianConflictAnalyzer.analyze(intersection).any { it.crossingId == crossing.id })
    }

}
