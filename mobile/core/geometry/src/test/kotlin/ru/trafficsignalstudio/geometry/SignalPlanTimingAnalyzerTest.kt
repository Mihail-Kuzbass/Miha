package ru.trafficsignalstudio.geometry

import org.junit.Assert.*
import org.junit.Test
import ru.trafficsignalstudio.model.*

class SignalPlanTimingAnalyzerTest {
    private fun intersection(plan: SignalPlan) = Intersection(
        name = "Test", type = IntersectionType.CUSTOM, approaches = emptyList(),
        laneConnections = listOf(LaneConnection(id = "move", fromApproachId = "a", fromLaneId = "in",
            toApproachId = "b", toLaneId = "out")), signalPlan = plan)

    @Test fun calculatesSequentialCycleAndClearanceWindows() {
        val report = SignalPlanTimingAnalyzer.analyze(intersection(SignalPlan(phases = listOf(
            SignalPhase(id = "p1", name = "Первая", greenConnectionIds = setOf("move"), greenSeconds = 20, yellowSeconds = 3, allRedSeconds = 2),
            SignalPhase(id = "p2", name = "Вторая", greenConnectionIds = setOf("move-2"), greenSeconds = 30, yellowSeconds = 3, allRedSeconds = 1)
        ))))
        assertEquals(59, report.cycleLengthSeconds)
        assertEquals(0, report.phaseWindows[0].startSeconds)
        assertEquals(20, report.phaseWindows[0].greenEndSeconds)
        assertEquals(23, report.phaseWindows[0].yellowEndSeconds)
        assertEquals(25, report.phaseWindows[0].allRedEndSeconds)
        assertEquals(25, report.phaseWindows[1].startSeconds)
        assertTrue(report.diagnostics.any { it.code == SignalPlanIssueCode.UNKNOWN_CONNECTION })
    }

    @Test fun reportsEmptyPhasesAndUnassignedMovements() {
        val report = SignalPlanTimingAnalyzer.analyze(intersection(SignalPlan(phases = listOf(
            SignalPhase(id = "p1", name = "Пустая", greenConnectionIds = emptySet())
        ))))
        assertFalse(report.isValid)
        assertTrue(report.diagnostics.any { it.code == SignalPlanIssueCode.EMPTY_PHASE })
        assertTrue(report.diagnostics.any { it.code == SignalPlanIssueCode.UNASSIGNED_CONNECTION })
    }
}
