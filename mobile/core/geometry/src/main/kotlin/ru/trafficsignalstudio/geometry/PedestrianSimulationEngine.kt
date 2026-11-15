package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.Intersection
import kotlin.math.max

data class PedestrianFlowDemand(
    val crossingId: String,
    val pedestriansPerHour: Double,
    val walkingSpeedMetersPerSecond: Double = 1.2
) {
    init {
        require(crossingId.isNotBlank())
        require(pedestriansPerHour.isFinite() && pedestriansPerHour in 0.0..20000.0)
        require(walkingSpeedMetersPerSecond.isFinite() && walkingSpeedMetersPerSecond in 0.5..2.5)
    }
}

data class SimulatedPedestrian(
    val id: Long,
    val crossingId: String,
    /** Progress from the pedestrian's origin curb to the destination curb. */
    val progress: Double,
    /** +1 walks start-to-end; -1 walks end-to-start. */
    val direction: Int,
    val speedMetersPerSecond: Double,
    val waiting: Boolean
)

data class PedestrianSimulationSnapshot(
    val timeSeconds: Double,
    val activePedestrians: List<SimulatedPedestrian>,
    val totalWaiting: Int,
    val completedPedestrians: Int
)

data class PedestrianSimulationResult(
    val durationSeconds: Int,
    val totalArrivals: Int,
    val totalCompleted: Int,
    val totalWaitingAtEnd: Int,
    val maximumQueue: Int,
    val averageWaitingSeconds: Double,
    val snapshots: List<PedestrianSimulationSnapshot>,
    val diagnostics: List<String>
)

/**
 * Deterministic pedestrian microsimulation for signal-controlled crossings.
 * A pedestrian may enter only on the assigned pedestrian green and only when
 * enough green time remains to clear the crossing. People already on the
 * crossing are allowed to finish after green ends.
 */
object PedestrianSimulationEngine {
    private data class Agent(
        val id: Long,
        val crossingId: String,
        val direction: Int,
        val speed: Double,
        val arrivalTime: Double,
        var progress: Double = 0.0,
        var waiting: Boolean = true
    )

    fun run(
        intersection: Intersection,
        demands: List<PedestrianFlowDemand>,
        durationSeconds: Int = 900,
        timeStepSeconds: Double = 0.25
    ): PedestrianSimulationResult {
        require(durationSeconds in 60..7200)
        require(timeStepSeconds.isFinite() && timeStepSeconds in 0.05..1.0)
        require(demands.map { it.crossingId }.distinct().size == demands.size) {
            "Для каждого перехода задайте только одну интенсивность."
        }
        val diagnostics = mutableListOf<String>()
        val crossings = intersection.pedestrianCrossings.associateBy { it.id }
        val validDemands = demands.filter { demand ->
            if (demand.crossingId !in crossings) {
                diagnostics += "Интенсивность задана для отсутствующего перехода: " + demand.crossingId
                false
            } else true
        }
        val timing = SignalPlanTimingAnalyzer.analyze(intersection)
        val phasesByCrossing = intersection.signalPlan?.phases.orEmpty()
            .flatMap { phase -> phase.greenCrosswalkIds.map { it to phase.id } }
            .groupBy({ it.first }, { it.second })
        validDemands.forEach { demand ->
            if (phasesByCrossing[demand.crossingId].isNullOrEmpty()) {
                diagnostics += "Переход «" + (crossings[demand.crossingId]?.name ?: demand.crossingId) +
                    "» не назначен ни в одну пешеходную фазу; люди будут ожидать."
            }
        }
        if (!timing.isValid) diagnostics += "В плане есть ошибки назначения сигналов; пешеходный выпуск ограничен только корректно назначенными фазами."

        val unsafePedestrianPhases = timing.diagnostics
            .filter { it.code == SignalPlanIssueCode.PEDESTRIAN_VEHICLE_CONFLICT }
            .mapNotNull { it.phaseId }.toSet()
        val unsafeCrosswalks = timing.diagnostics
            .filter { it.code == SignalPlanIssueCode.CROSSWALK_ASSIGNED_MULTIPLE_PHASES }
            .mapNotNull { it.crosswalkId }.toSet()
        val greenWindows = validDemands.associate { demand ->
            demand.crossingId to timing.phaseWindows.filter { window ->
                window.phaseId in phasesByCrossing[demand.crossingId].orEmpty() &&
                    window.phaseId !in unsafePedestrianPhases &&
                    demand.crossingId !in unsafeCrosswalks
            }
        }
        val waiting = validDemands.associate { it.crossingId to ArrayDeque<Agent>() }
        val active = validDemands.associate { it.crossingId to mutableListOf<Agent>() }
        val nextArrival = validDemands.associate { demand ->
            demand.crossingId to if (demand.pedestriansPerHour > 0.0) 3600.0 / demand.pedestriansPerHour else Double.POSITIVE_INFINITY
        }.toMutableMap()
        val totalDelayByCrossing = mutableMapOf<String, Double>()
        val lastEntryTime = validDemands.associate { it.crossingId to Double.NEGATIVE_INFINITY }.toMutableMap()
        var nextId = 1L
        var totalArrivals = 0
        var totalCompleted = 0
        var maximumQueue = 0
        var time = 0.0
        var nextSnapshot = 0.0
        val snapshots = mutableListOf<PedestrianSimulationSnapshot>()
        val step = timeStepSeconds
        val planEnabled = intersection.signalPlan?.enabled == true && timing.cycleLengthSeconds > 0 && timing.isValid

        fun remainingPedestrianGreen(crossingId: String, atSeconds: Double): Double {
            if (!planEnabled) return 0.0
            val cycle = timing.cycleLengthSeconds.toDouble()
            val position = ((atSeconds % cycle) + cycle) % cycle
            return greenWindows[crossingId].orEmpty().firstOrNull { window ->
                position >= window.startSeconds && position < window.greenEndSeconds
            }?.let { it.greenEndSeconds - position } ?: 0.0
        }

        while (time <= durationSeconds + 1e-9) {
            validDemands.forEach { demand ->
                val interval = if (demand.pedestriansPerHour > 0.0) 3600.0 / demand.pedestriansPerHour else Double.POSITIVE_INFINITY
                var arrival = nextArrival[demand.crossingId] ?: Double.POSITIVE_INFINITY
                while (arrival <= time + 1e-9) {
                    val id = nextId++
                    waiting.getValue(demand.crossingId).addLast(Agent(
                        id = id,
                        crossingId = demand.crossingId,
                        direction = if (id % 2L == 0L) 1 else -1,
                        speed = demand.walkingSpeedMetersPerSecond,
                        arrivalTime = arrival
                    ))
                    totalArrivals++
                    arrival += interval
                }
                nextArrival[demand.crossingId] = arrival
            }

            validDemands.forEach { demand ->
                val crossing = crossings.getValue(demand.crossingId)
                val queue = waiting.getValue(demand.crossingId)
                val walkers = active.getValue(demand.crossingId)
                val remainingGreen = remainingPedestrianGreen(demand.crossingId, time)
                val requiredClearance = crossing.crossingLengthMeters / demand.walkingSpeedMetersPerSecond + 1.0
                if (queue.isNotEmpty() && remainingGreen + 1e-9 >= requiredClearance &&
                    time - (lastEntryTime[demand.crossingId] ?: Double.NEGATIVE_INFINITY) >= 0.8) {
                    val pedestrian = queue.removeFirst()
                    pedestrian.waiting = false
                    totalDelayByCrossing[demand.crossingId] =
                        (totalDelayByCrossing[demand.crossingId] ?: 0.0) + (time - pedestrian.arrivalTime).coerceAtLeast(0.0)
                    walkers += pedestrian
                    lastEntryTime[demand.crossingId] = time
                }
                walkers.forEach { pedestrian ->
                    pedestrian.progress += pedestrian.speed * step / crossing.crossingLengthMeters
                }
                val completed = walkers.count { it.progress >= 1.0 }
                if (completed > 0) {
                    totalCompleted += completed
                    walkers.removeAll { it.progress >= 1.0 }
                }
                maximumQueue = max(maximumQueue, queue.size)
            }

            if (time + 1e-9 >= nextSnapshot || time >= durationSeconds - 1e-9) {
                val activeSnapshots = validDemands.flatMap { demand ->
                    val crossing = crossings.getValue(demand.crossingId)
                    val queued = waiting.getValue(demand.crossingId).mapIndexed { index, agent ->
                        SimulatedPedestrian(agent.id, agent.crossingId, 0.0, agent.direction, 0.0, true)
                    }
                    val walking = active.getValue(demand.crossingId).map { agent ->
                        SimulatedPedestrian(agent.id, agent.crossingId,
                            agent.progress.coerceIn(0.0, 1.0), agent.direction, agent.speed, false)
                    }
                    queued + walking
                }
                val queueCount = validDemands.sumOf { waiting.getValue(it.crossingId).size }
                snapshots += PedestrianSimulationSnapshot(time, activeSnapshots.take(2000), queueCount, totalCompleted)
                nextSnapshot += 1.0
            }
            time += step
        }

        val waitingAtEnd = validDemands.sumOf { waiting.getValue(it.crossingId).size }
        val pendingWait = validDemands.sumOf { demand ->
            waiting.getValue(demand.crossingId).sumOf { (durationSeconds - it.arrivalTime).coerceAtLeast(0.0) }
        }
        val completedWait = totalDelayByCrossing.values.sum()
        val averageWait = if (totalArrivals == 0) 0.0
            else (completedWait + pendingWait) / totalArrivals
        if (validDemands.isNotEmpty() && !planEnabled) {
            diagnostics += "Нет действующего светофорного плана: пешеходы остаются перед стоп-линией."
        }
        return PedestrianSimulationResult(
            durationSeconds = durationSeconds,
            totalArrivals = totalArrivals,
            totalCompleted = totalCompleted,
            totalWaitingAtEnd = waitingAtEnd,
            maximumQueue = maximumQueue,
            averageWaitingSeconds = averageWait,
            snapshots = snapshots,
            diagnostics = diagnostics.distinct()
        )
    }
}
