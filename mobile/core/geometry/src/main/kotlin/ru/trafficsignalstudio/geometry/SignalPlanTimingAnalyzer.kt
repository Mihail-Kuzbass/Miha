package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.Intersection

enum class SignalPlanIssueCode {
    NO_SIGNAL_PLAN, PLAN_DISABLED, NO_PHASES, DUPLICATE_PHASE_ID, EMPTY_PHASE,
    INVALID_GREEN_DURATION, INVALID_FLASHING_GREEN_DURATION, INVALID_YELLOW_DURATION, INVALID_ALL_RED_DURATION,
    INVALID_RED_YELLOW_DURATION,
    UNKNOWN_CONNECTION, CONNECTION_ASSIGNED_MULTIPLE_PHASES, UNASSIGNED_CONNECTION
}

data class SignalPlanDiagnostic(
    val code: SignalPlanIssueCode,
    val message: String,
    val phaseId: String? = null,
    val connectionId: String? = null
)

data class SignalPhaseWindow(
    val phaseId: String, val phaseName: String,
    /** Start of the green interval; the red-yellow transition precedes it. */
    val startSeconds: Int, val greenEndSeconds: Int,
    val flashingGreenEndSeconds: Int, val yellowEndSeconds: Int, val allRedEndSeconds: Int,
    val redYellowStartSeconds: Int = 0,
    val redYellowEndSeconds: Int = 0
)

data class SignalPlanTimingReport(
    val cycleLengthSeconds: Int,
    val phaseWindows: List<SignalPhaseWindow>,
    val diagnostics: List<SignalPlanDiagnostic>
) {
    val isValid: Boolean get() = diagnostics.none {
        it.code == SignalPlanIssueCode.NO_SIGNAL_PLAN ||
            it.code == SignalPlanIssueCode.PLAN_DISABLED ||
            it.code == SignalPlanIssueCode.NO_PHASES ||
            it.code == SignalPlanIssueCode.DUPLICATE_PHASE_ID ||
            it.code == SignalPlanIssueCode.EMPTY_PHASE ||
            it.code == SignalPlanIssueCode.INVALID_GREEN_DURATION ||
            it.code == SignalPlanIssueCode.INVALID_FLASHING_GREEN_DURATION ||
            it.code == SignalPlanIssueCode.INVALID_YELLOW_DURATION ||
            it.code == SignalPlanIssueCode.INVALID_ALL_RED_DURATION ||
            it.code == SignalPlanIssueCode.INVALID_RED_YELLOW_DURATION ||
            it.code == SignalPlanIssueCode.UNKNOWN_CONNECTION ||
            it.code == SignalPlanIssueCode.CONNECTION_ASSIGNED_MULTIPLE_PHASES ||
            it.code == SignalPlanIssueCode.UNASSIGNED_CONNECTION
    }
}

/** Sequential phase arithmetic including red-yellow, green, yellow and all-red intervals. */
object SignalPlanTimingAnalyzer {
    fun analyze(intersection: Intersection): SignalPlanTimingReport {
        val plan = intersection.signalPlan ?: return SignalPlanTimingReport(0, emptyList(), listOf(
            SignalPlanDiagnostic(SignalPlanIssueCode.NO_SIGNAL_PLAN, "Светофорный план не задан")
        ))
        val diagnostics = mutableListOf<SignalPlanDiagnostic>()
        if (!plan.enabled) diagnostics += SignalPlanDiagnostic(SignalPlanIssueCode.PLAN_DISABLED, "Светофорный план отключён")
        if (plan.phases.isEmpty()) diagnostics += SignalPlanDiagnostic(SignalPlanIssueCode.NO_PHASES, "В плане отсутствуют фазы")
        plan.phases.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { id ->
            diagnostics += SignalPlanDiagnostic(SignalPlanIssueCode.DUPLICATE_PHASE_ID, "Повторяется идентификатор фазы: " + id, phaseId = id)
        }
        val known = intersection.laneConnections.map { it.id }.toSet()
        val assignments = mutableMapOf<String, MutableList<String>>()
        val windows = mutableListOf<SignalPhaseWindow>()
        var cursor = 0
        plan.phases.forEach { phase ->
            if (phase.greenConnectionIds.isEmpty()) diagnostics += SignalPlanDiagnostic(
                SignalPlanIssueCode.EMPTY_PHASE, "Фаза «" + phase.name + "» не содержит разрешённых движений", phaseId = phase.id)
            if (phase.redYellowSeconds < 0 || phase.redYellowSeconds > 2) diagnostics += SignalPlanDiagnostic(
                SignalPlanIssueCode.INVALID_RED_YELLOW_DURATION, "Красный с жёлтым должен длиться от 0 до 2 с", phaseId = phase.id)
            if (phase.greenSeconds <= 0) diagnostics += SignalPlanDiagnostic(
                SignalPlanIssueCode.INVALID_GREEN_DURATION, "Зелёный интервал должен быть больше нуля", phaseId = phase.id)
            if (phase.flashingGreenSeconds < 0) diagnostics += SignalPlanDiagnostic(
                SignalPlanIssueCode.INVALID_FLASHING_GREEN_DURATION, "Мигающий зелёный интервал не может быть отрицательным", phaseId = phase.id)
            if (phase.yellowSeconds != 3) diagnostics += SignalPlanDiagnostic(
                SignalPlanIssueCode.INVALID_YELLOW_DURATION, "Жёлтый сигнал должен длиться 3 с согласно п. 7.5.2 ГОСТ Р 52289-2019", phaseId = phase.id)
            if (phase.allRedSeconds < 0) diagnostics += SignalPlanDiagnostic(
                SignalPlanIssueCode.INVALID_ALL_RED_DURATION, "Общий красный интервал не может быть отрицательным", phaseId = phase.id)
            phase.greenConnectionIds.forEach { id ->
                assignments.getOrPut(id) { mutableListOf() }.add(phase.id)
                if (id !in known) diagnostics += SignalPlanDiagnostic(
                    SignalPlanIssueCode.UNKNOWN_CONNECTION, "Фаза «" + phase.name + "» ссылается на отсутствующее движение",
                    phaseId = phase.id, connectionId = id)
            }
            val redYellowStart = cursor
            val redYellowEnd = redYellowStart + phase.redYellowSeconds.coerceAtLeast(0)
            val greenStart = redYellowEnd
            val greenEnd = greenStart + phase.greenSeconds.coerceAtLeast(0)
            val flashingGreenEnd = greenEnd + phase.flashingGreenSeconds.coerceAtLeast(0)
            val yellowEnd = flashingGreenEnd + phase.yellowSeconds.coerceAtLeast(0)
            val allRedEnd = yellowEnd + phase.allRedSeconds.coerceAtLeast(0)
            windows += SignalPhaseWindow(
                phase.id, phase.name, greenStart, greenEnd, flashingGreenEnd, yellowEnd, allRedEnd,
                redYellowStart, redYellowEnd
            )
            cursor = allRedEnd
        }
        assignments.filterValues { it.size > 1 }.keys.forEach { id ->
            diagnostics += SignalPlanDiagnostic(SignalPlanIssueCode.CONNECTION_ASSIGNED_MULTIPLE_PHASES,
                "Одно движение назначено более чем в одну фазу", connectionId = id)
        }
        known.filter { it !in assignments }.forEach { id ->
            diagnostics += SignalPlanDiagnostic(SignalPlanIssueCode.UNASSIGNED_CONNECTION,
                "Для движения не назначена ни одна фаза", connectionId = id)
        }
        return SignalPlanTimingReport(cursor, windows, diagnostics)
    }
}
