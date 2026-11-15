package ru.trafficsignalstudio.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.IntersectionType
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
}
