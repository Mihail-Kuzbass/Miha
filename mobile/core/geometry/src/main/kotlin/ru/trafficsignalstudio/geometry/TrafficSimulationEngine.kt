package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.Point2D
import ru.trafficsignalstudio.model.VehicleClass
import ru.trafficsignalstudio.model.VehicleModelCatalog
import kotlin.math.hypot
import kotlin.math.min

/**
 * Total flow includes the explicitly classified flows. Any remainder is kept as
 * unclassified demand (rendered with the default passenger-car silhouette only).
 */
data class MovementTrafficDemand(
    val connectionId: String,
    val totalVehiclesPerHour: Double,
    val categoryFlowsPerHour: Map<VehicleClass, Double> = emptyMap()
) {
    init {
        require(connectionId.isNotBlank())
        require(totalVehiclesPerHour >= 0.0)
        require(categoryFlowsPerHour.values.all { it >= 0.0 })
        require(categoryFlowsPerHour.values.sum() <= totalVehiclesPerHour + 1e-6) {
            "Сумма интенсивностей по категориям не может превышать общую интенсивность"
        }
    }

    val unclassifiedVehiclesPerHour: Double
        get() = (totalVehiclesPerHour - categoryFlowsPerHour.values.sum()).coerceAtLeast(0.0)
}

data class TrafficSimulationDemand(
    val movements: List<MovementTrafficDemand>,
    val durationSeconds: Int = 900,
    val timeStepSeconds: Double = 0.5
) {
    init {
        require(durationSeconds in 60..7200)
        require(timeStepSeconds in 0.1..2.0)
        require(movements.map { it.connectionId }.distinct().size == movements.size)
    }
}

data class SimulatedVehicle(
    val id: Long,
    val connectionId: String,
    val vehicleClass: VehicleClass?,
    /** 0..1 on the movement path; negative values represent a queued vehicle before the stop line. */
    val progress: Double,
    val queued: Boolean,
    val queueIndex: Int
)

data class MovementSimulationMetrics(
    val connectionId: String,
    val arrivals: Int,
    val departures: Int,
    val queueVehiclesAtEnd: Int,
    val maximumQueueVehicles: Int,
    val averageDelaySeconds: Double,
    val totalDelayVehicleHours: Double
)

data class CategorySimulationMetrics(
    val vehicleClass: VehicleClass?,
    val arrivals: Int,
    val departures: Int,
    val averageDelaySeconds: Double
)

data class TrafficSimulationSnapshot(
    val timeSeconds: Double,
    val activeVehicles: List<SimulatedVehicle>,
    val totalQueueVehicles: Int,
    val departedVehicles: Int
)

data class TrafficSimulationResult(
    val durationSeconds: Int,
    val cycleLengthSeconds: Int,
    val totalArrivals: Int,
    val totalDepartures: Int,
    val totalQueueAtEnd: Int,
    val maximumQueueVehicles: Int,
    val averageDelaySeconds: Double,
    val totalDelayVehicleHours: Double,
    val movementMetrics: List<MovementSimulationMetrics>,
    val categoryMetrics: List<CategorySimulationMetrics>,
    val snapshots: List<TrafficSimulationSnapshot>,
    val diagnostics: List<String>
) {
    val throughputVehiclesPerHour: Double
        get() = totalDepartures * 3600.0 / durationSeconds.coerceAtLeast(1)
}

/**
 * Deterministic, fixed-time-step queue simulation for signalized lane connections.
 * It is an initial macroscopic/mesoscopic screening model, not a calibrated
 * car-following or normative capacity model.
 */
object TrafficSimulationEngine {
    private data class WaitingVehicle(
        val id: Long,
        val vehicleClass: VehicleClass?,
        val arrivalTime: Double,
        val lengthMeters: Double
    )

    private data class ActiveVehicle(
        val waiting: WaitingVehicle,
        val departureTime: Double,
        val travelTime: Double
    )

    private class MovementState(val connectionId: String, val pathLength: Double) {
        val queue = ArrayDeque<WaitingVehicle>()
        val active = mutableListOf<ActiveVehicle>()
        var arrivals = 0
        var departures = 0
        var maxQueue = 0
        var delaySum = 0.0
        var delayVehicleHours = 0.0
        var nextDepartureTime = 0.0
    }

    fun run(intersection: Intersection, demand: TrafficSimulationDemand): TrafficSimulationResult {
        val diagnostics = mutableListOf<String>()
        val timing = SignalPlanTimingAnalyzer.analyze(intersection)
        val signalPlan = intersection.signalPlan
        if (!timing.isValid) diagnostics += timing.diagnostics.map { it.message }
        if (timing.cycleLengthSeconds <= 0 || intersection.signalPlan?.enabled != true) {
            diagnostics += "Нет действующего циклического светофорного плана: выпуск из очередей не выполняется."
        }
        val trajectories = TrajectoryConflictAnalyzer.buildMovementTrajectories(intersection)
            .associateBy { it.connectionId }
        val knownConnections = intersection.laneConnections.map { it.id }.toSet()
        val configured = demand.movements.filter { it.connectionId in knownConnections }
        if (configured.size != demand.movements.size) {
            diagnostics += "Часть потоков ссылается на отсутствующие связи полос и не включена в расчёт."
        }
        val states = configured.associate { item ->
            val length = trajectories[item.connectionId]?.points?.let(::polylineLength)?.coerceAtLeast(8.0) ?: 20.0
            item.connectionId to MovementState(item.connectionId, length)
        }
        var nextId = 1L
        val nextArrival = mutableMapOf<Pair<String, VehicleClass?>, Double>()
        configured.forEach { movement ->
            val residual = movement.unclassifiedVehiclesPerHour
            val streams = mutableListOf<Pair<VehicleClass?, Double>>()
            streams += movement.categoryFlowsPerHour.map { it.key to it.value }
            if (residual > 1e-6) streams += null to residual
            streams.forEach { (vehicleClass, rate) ->
                if (rate > 0.0) nextArrival[movement.connectionId to vehicleClass] = 3600.0 / rate
            }
        }
        val categoryArrivals = mutableMapOf<VehicleClass?, Int>()
        val categoryDepartures = mutableMapOf<VehicleClass?, Int>()
        val categoryDelay = mutableMapOf<VehicleClass?, Double>()
        val snapshots = mutableListOf<TrafficSimulationSnapshot>()
        var time = 0.0
        var totalArrivals = 0
        var totalDepartures = 0
        var globalMaxQueue = 0
        var snapshotAt = 0.0

        fun greenFor(connectionId: String, atSeconds: Double): Boolean {
            if (!timing.isValid || timing.cycleLengthSeconds <= 0 || signalPlan?.enabled != true) return false
            val cycleTime = atSeconds % timing.cycleLengthSeconds
            val phase = timing.phaseWindows.firstOrNull { window ->
                cycleTime >= window.startSeconds && cycleTime < window.greenEndSeconds &&
                    signalPlan.phases.firstOrNull { it.id == window.phaseId }
                        ?.greenConnectionIds?.contains(connectionId) == true
            }
            return phase != null
        }

        val step = demand.timeStepSeconds
        while (time <= demand.durationSeconds + 1e-9) {
            configured.forEach { movement ->
                val state = states.getValue(movement.connectionId)
                val streams = movement.categoryFlowsPerHour.map { it.key to it.value } +
                    listOf(null to movement.unclassifiedVehiclesPerHour)
                streams.forEach { (vehicleClass, rate) ->
                    if (rate <= 0.0) return@forEach
                    val key = movement.connectionId to vehicleClass
                    val interval = 3600.0 / rate
                    var arrival = nextArrival[key] ?: interval
                    while (arrival <= time + 1e-9) {
                        val model = vehicleClass?.let { cls -> VehicleModelCatalog.defaults.firstOrNull { it.vehicleClass == cls } }
                            ?: VehicleModelCatalog.defaultPassengerCar
                        state.queue.addLast(WaitingVehicle(nextId++, vehicleClass, arrival, model.lengthMeters))
                        state.arrivals++
                        totalArrivals++
                        categoryArrivals[vehicleClass] = (categoryArrivals[vehicleClass] ?: 0) + 1
                        arrival += interval
                    }
                    nextArrival[key] = arrival
                }
                state.maxQueue = maxOf(state.maxQueue, state.queue.size)
                if (state.queue.isNotEmpty() && greenFor(movement.connectionId, time) &&
                    time + 1e-9 >= state.nextDepartureTime) {
                    val vehicle = state.queue.removeFirst()
                    val model = vehicle.vehicleClass?.let { cls -> VehicleModelCatalog.defaults.firstOrNull { it.vehicleClass == cls } }
                        ?: VehicleModelCatalog.defaultPassengerCar
                    val delay = (time - vehicle.arrivalTime).coerceAtLeast(0.0)
                    val speedMps = min(model.maximumSpeedKmh, 30.0) / 3.6
                    val travelTime = state.pathLength / speedMps.coerceAtLeast(2.0)
                    state.active += ActiveVehicle(vehicle, time, travelTime)
                    state.nextDepartureTime = time + 1.8 + model.lengthMeters / 35.0
                    state.departures++
                    totalDepartures++
                    state.delaySum += delay
                    state.delayVehicleHours += delay / 3600.0
                    categoryDepartures[vehicle.vehicleClass] = (categoryDepartures[vehicle.vehicleClass] ?: 0) + 1
                    categoryDelay[vehicle.vehicleClass] = (categoryDelay[vehicle.vehicleClass] ?: 0.0) + delay
                }
                val iterator = state.active.iterator()
                while (iterator.hasNext()) {
                    val active = iterator.next()
                    if (time - active.departureTime >= active.travelTime) iterator.remove()
                }
            }
            val queueTotal = states.values.sumOf { it.queue.size }
            globalMaxQueue = maxOf(globalMaxQueue, queueTotal)
            if (time + 1e-9 >= snapshotAt) {
                val visible = states.values.flatMap { state ->
                    val pathLength = state.pathLength
                    state.queue.mapIndexed { index, vehicle ->
                        SimulatedVehicle(vehicle.id, state.connectionId, vehicle.vehicleClass,
                            -(index + 1) * (vehicle.lengthMeters + 2.0) / pathLength, true, index)
                    } + state.active.map { active ->
                        val progress = ((time - active.departureTime) / active.travelTime).coerceIn(0.0, 1.0)
                        SimulatedVehicle(active.waiting.id, state.connectionId, active.waiting.vehicleClass, progress, false, 0)
                    }
                }
                // Keep animation memory bounded on long, oversaturated scenarios.
                snapshots += TrafficSimulationSnapshot(time, visible.take(500), queueTotal, totalDepartures)
                snapshotAt += 1.0
            }
            time += step
        }
        val movementMetrics = states.values.map { state ->
            MovementSimulationMetrics(
                state.connectionId, state.arrivals, state.departures, state.queue.size, state.maxQueue,
                if (state.departures == 0) 0.0 else state.delaySum / state.departures,
                state.delayVehicleHours
            )
        }
        val categoryMetrics = (categoryArrivals.keys + categoryDepartures.keys).distinct().map { cls ->
            val departures = categoryDepartures[cls] ?: 0
            CategorySimulationMetrics(cls, categoryArrivals[cls] ?: 0, departures,
                if (departures == 0) 0.0 else (categoryDelay[cls] ?: 0.0) / departures)
        }
        val allDelay = movementMetrics.sumOf { it.totalDelayVehicleHours }
        val allDepartedDelaySeconds = movementMetrics.sumOf { it.averageDelaySeconds * it.departures }
        return TrafficSimulationResult(
            demand.durationSeconds, timing.cycleLengthSeconds, totalArrivals, totalDepartures,
            states.values.sumOf { it.queue.size }, globalMaxQueue,
            if (totalDepartures == 0) 0.0 else allDepartedDelaySeconds / totalDepartures,
            allDelay, movementMetrics, categoryMetrics, snapshots, diagnostics.distinct()
        )
    }

    private fun polylineLength(points: List<Point2D>): Double =
        points.zipWithNext().sumOf { (a, b) -> hypot(b.xMeters - a.xMeters, b.yMeters - a.yMeters) }
}
