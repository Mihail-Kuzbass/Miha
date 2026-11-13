package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.Point2D
import ru.trafficsignalstudio.model.VehicleClass
import ru.trafficsignalstudio.model.VehicleModel
import ru.trafficsignalstudio.model.VehicleModelCatalog
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Total flow includes the explicitly classified flows. Any remainder is kept as
 * unclassified demand (rendered with the default passenger-car model only).
 */
data class MovementTrafficDemand(
    val connectionId: String,
    val totalVehiclesPerHour: Double,
    val categoryFlowsPerHour: Map<VehicleClass, Double> = emptyMap()
) {
    init {
        require(connectionId.isNotBlank())
        require(totalVehiclesPerHour.isFinite() && totalVehiclesPerHour >= 0.0)
        require(categoryFlowsPerHour.values.all { it.isFinite() && it >= 0.0 })
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
    val timeStepSeconds: Double = 0.25,
    /** Usable upstream storage length measured from the stop line, metres. */
    val approachStorageLengthMeters: Double = 120.0,
    /** Free-flow speed at the upstream boundary, km/h. */
    val approachSpeedKmh: Double = 30.0,
    /** Optional global lower bound; when absent, vehicle-class-specific gaps are used. */
    val minimumGapMeters: Double? = null
) {
    init {
        require(durationSeconds in 60..7200)
        require(timeStepSeconds in 0.05..1.0)
        require(movements.map { it.connectionId }.distinct().size == movements.size)
        require(approachStorageLengthMeters.isFinite() && approachStorageLengthMeters in 20.0..1000.0)
        require(approachSpeedKmh.isFinite() && approachSpeedKmh in 5.0..80.0)
        require(minimumGapMeters == null || (minimumGapMeters.isFinite() && minimumGapMeters in 0.5..10.0))
    }
}

data class SimulatedVehicle(
    val id: Long,
    val connectionId: String,
    val vehicleClass: VehicleClass?,
    /** Centre position along the movement path divided by path length. Negative means upstream. */
    val progress: Double,
    val queued: Boolean,
    val queueIndex: Int,
    /** Front-bumper position relative to the stop line, metres. */
    val positionMeters: Double = 0.0,
    val speedMetersPerSecond: Double = 0.0,
    val lengthMeters: Double = 4.6,
    val widthMeters: Double = 1.8,
    /** Previous lane's movement path while the lateral manoeuvre is in progress. */
    val laneChangeFromConnectionId: String? = null,
    val laneChangeProgress: Double = 1.0
)

data class MovementSimulationMetrics(
    val connectionId: String,
    val arrivals: Int,
    val departures: Int,
    val queueVehiclesAtEnd: Int,
    val maximumQueueVehicles: Int,
    val averageDelaySeconds: Double,
    val totalDelayVehicleHours: Double,
    val queueLengthMetersAtEnd: Double,
    val maximumQueueLengthMeters: Double,
    val spillbackVehiclesAtEnd: Int,
    val maximumSpillbackVehicles: Int
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
    val departedVehicles: Int,
    val approachStorageLengthMeters: Double = 120.0,
    val spillbackVehicles: Int = 0
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
    val diagnostics: List<String>,
    val maximumQueueLengthMeters: Double,
    val spillbackVehiclesAtEnd: Int,
    val maximumSpillbackVehicles: Int,
    val physicalLaneVehiclesAtEnd: Int,
    val upstreamQueueAtEnd: Int,
    val laneStorageUtilizationPercent: Double,
    val maximumLaneStorageUtilizationPercent: Double
) {
    val throughputVehiclesPerHour: Double
        get() = totalDepartures * 3600.0 / durationSeconds.coerceAtLeast(1)
}

/**
 * Time-domain lane simulation with IDM car-following.
 *
 * Vehicles enter at the upstream boundary, occupy a finite length of lane,
 * follow a leader using their own length, minimum gap, desired headway,
 * acceleration and service deceleration, and stop at the stop line on red.
 * Vehicles from different turning movements that share the same incoming lane
 * share one physical approach queue until they cross the stop line.
 *
 * This is a deterministic engineering microsimulation foundation, not yet a
 * calibrated Wiedemann/IDM implementation or a complete multi-lane network model.
 */
object TrafficSimulationEngine {
    private data class WaitingVehicle(
        val id: Long,
        val vehicleClass: VehicleClass?,
        val model: VehicleModel,
        val arrivalTime: Double,
        val demandConnectionId: String
    )

    private data class VehicleAgent(
        val waiting: WaitingVehicle,
        var positionMeters: Double,
        var speedMetersPerSecond: Double,
        var accelerationMetersPerSecondSquared: Double = 0.0,
        var crossingTimeSeconds: Double? = null,
        var laneChangeFromConnectionId: String? = null,
        var laneChangeProgress: Double = 1.0
    )

    private class MovementState(
        val connectionId: String,
        val fromApproachId: String,
        val fromLaneId: String,
        val movementKind: ru.trafficsignalstudio.model.MovementKind,
        val pathLengthMeters: Double
    ) {
        val pending = ArrayDeque<WaitingVehicle>()
        val vehicles = mutableListOf<VehicleAgent>()
        var arrivals = 0
        var departures = 0
        var maxQueue = 0
        var maxQueueLengthMeters = 0.0
        var maxSpillback = 0
        var delaySum = 0.0
        var delayVehicleHours = 0.0
    }

    fun run(intersection: Intersection, demand: TrafficSimulationDemand): TrafficSimulationResult {
        val diagnostics = mutableListOf<String>()
        val timing = SignalPlanTimingAnalyzer.analyze(intersection)
        val signalPlan = intersection.signalPlan
        if (!timing.isValid) diagnostics += timing.diagnostics.map { it.message }
        if (timing.cycleLengthSeconds <= 0 || signalPlan?.enabled != true) {
            diagnostics += "Нет действующего циклического светофорного плана: выпуск из очередей не выполняется."
        }
        TrajectoryConflictAnalyzer.analyzeMovementConflicts(intersection).forEach { conflict ->
            when (conflict.signalStatus) {
                ConflictSignalStatus.SIMULTANEOUS_GREEN -> diagnostics +=
                    "Опасный конфликт: движения ${conflict.firstConnectionId} и ${conflict.secondConnectionId} имеют одновременный зелёный."
                ConflictSignalStatus.PHASES_SEPARATED_NO_CLEARANCE -> diagnostics +=
                    "Недостаточно защитных интервалов между движениями ${conflict.firstConnectionId} и ${conflict.secondConnectionId}."
                else -> Unit
            }
        }

        val trajectories = TrajectoryConflictAnalyzer.buildMovementTrajectories(intersection).associateBy { it.connectionId }
        val connections = intersection.laneConnections.associateBy { it.id }
        // Keep a physical state for every valid lane connection, including
        // connections with zero demand. Otherwise an empty adjacent lane cannot
        // be selected as a lane-change target.
        val configured = demand.movements.filter { it.connectionId in trajectories }
        if (configured.size != demand.movements.size) {
            diagnostics += "Часть потоков ссылается на отсутствующие или некорректные связи полос и не включена в расчёт."
        }
        val states = trajectories.values.associate { trajectory ->
            val connection = connections.getValue(trajectory.connectionId)
            val length = trajectory.points.let(::polylineLength).coerceAtLeast(8.0)
            trajectory.connectionId to MovementState(
                trajectory.connectionId,
                connection.fromApproachId,
                connection.fromLaneId,
                trajectory.movementKind,
                length
            )
        }
        // Lane IDs are only unique within an approach, not across the network.
        val laneGroups = states.values.groupBy { it.fromApproachId to it.fromLaneId }
        val approachGroups = states.values.groupBy { it.fromApproachId }
        val approachLanes = intersection.approaches.associate { approach ->
            approach.id to approach.lanes.associateBy { it.id }
        }
        if (laneGroups.values.any { group -> group.size > 1 }) {
            diagnostics += "Несколько движений с одной въездной полосы используют общую физическую очередь и общую дистанцию между автомобилями."
        }

        var nextId = 1L
        val nextArrival = mutableMapOf<Pair<String, VehicleClass?>, Double>()
        configured.forEach { movement ->
            val streams = movement.categoryFlowsPerHour.map { it.key to it.value } +
                listOf(null to movement.unclassifiedVehiclesPerHour)
            streams.forEach { (vehicleClass, rate) ->
                if (rate > 0.0) nextArrival[movement.connectionId to vehicleClass] = 3600.0 / rate
            }
        }

        val categoryArrivals = mutableMapOf<VehicleClass?, Int>()
        val categoryDepartures = mutableMapOf<VehicleClass?, Int>()
        val categoryDelay = mutableMapOf<VehicleClass?, Double>()
        val snapshots = mutableListOf<TrafficSimulationSnapshot>()
        var totalArrivals = 0
        var totalDepartures = 0
        var maximumQueueVehicles = 0
        var maximumQueueLengthMeters = 0.0
        var maximumSpillbackVehicles = 0
        var time = 0.0
        var snapshotAt = 0.0

        fun greenFor(connectionId: String, atSeconds: Double): Boolean {
            if (!timing.isValid || timing.cycleLengthSeconds <= 0 || signalPlan?.enabled != true) return false
            val cycleTime = atSeconds % timing.cycleLengthSeconds
            return timing.phaseWindows.any { window ->
                cycleTime >= window.startSeconds && cycleTime < window.greenEndSeconds &&
                    signalPlan.phases.firstOrNull { it.id == window.phaseId }
                        ?.greenConnectionIds?.contains(connectionId) == true
            }
        }

        fun createModel(vehicleClass: VehicleClass?): VehicleModel {
            val base = vehicleClass?.let { cls -> VehicleModelCatalog.defaults.firstOrNull { it.vehicleClass == cls } }
                ?: VehicleModelCatalog.defaultPassengerCar
            val configuredGap = max(base.minimumGapMeters, demand.minimumGapMeters ?: 0.0)
            return if (configuredGap == base.minimumGapMeters) base
                else base.copy(minimumGapMeters = configuredGap)
        }

        fun approachQueueLength(agents: List<VehicleAgent>): Double {
            val approach = agents.filter { it.positionMeters <= 0.0 }
            if (approach.isEmpty()) return 0.0
            val tailRear = approach.minOf { it.positionMeters - it.waiting.model.lengthMeters }
            return (-tailRear).coerceAtLeast(0.0)
        }

        fun idmAcceleration(
            vehicle: VehicleAgent,
            leaderGapMeters: Double?,
            leaderSpeedMetersPerSecond: Double?,
            desiredSpeedMetersPerSecond: Double
        ): Double {
            val model = vehicle.waiting.model
            val acceleration = model.accelerationMetersPerSecondSquared.coerceAtLeast(0.1)
            val deceleration = model.serviceDecelerationMetersPerSecondSquared.coerceAtLeast(0.1)
            val speed = vehicle.speedMetersPerSecond.coerceAtLeast(0.0)
            val freeRoadTerm = if (desiredSpeedMetersPerSecond <= 0.01) 0.0
                else (speed / desiredSpeedMetersPerSecond).let { it * it * it * it }
            if (leaderGapMeters == null || leaderSpeedMetersPerSecond == null) {
                return (acceleration * (1.0 - freeRoadTerm)).coerceIn(-deceleration, acceleration)
            }
            val gap = leaderGapMeters.coerceAtLeast(0.1)
            val closingSpeed = speed - leaderSpeedMetersPerSecond
            val dynamicGap = model.minimumGapMeters +
                max(0.0, speed * model.desiredTimeHeadwaySeconds +
                    speed * closingSpeed / (2.0 * sqrt(acceleration * deceleration)))
            val interaction = (dynamicGap / gap) * (dynamicGap / gap)
            return (acceleration * (1.0 - freeRoadTerm - interaction)).coerceIn(-deceleration, acceleration)
        }

        val step = demand.timeStepSeconds
        while (time <= demand.durationSeconds + 1e-9) {
            // Schedule arrivals into virtual upstream demand queues.
            configured.forEach { movement ->
                val state = states.getValue(movement.connectionId)
                val streams = movement.categoryFlowsPerHour.map { it.key to it.value } +
                    listOf(null to movement.unclassifiedVehiclesPerHour)
                for ((vehicleClass, rate) in streams) {
                    if (rate <= 0.0) continue
                    val key = movement.connectionId to vehicleClass
                    val interval = 3600.0 / rate
                    var arrival = nextArrival[key] ?: interval
                    while (arrival <= time + 1e-9) {
                        state.pending.addLast(WaitingVehicle(nextId++, vehicleClass, createModel(vehicleClass), arrival, state.connectionId))
                        state.arrivals++
                        totalArrivals++
                        categoryArrivals[vehicleClass] = (categoryArrivals[vehicleClass] ?: 0) + 1
                        arrival += interval
                    }
                    nextArrival[key] = arrival
                }
            }

            // Insert arrivals into finite physical approach storage. Movements
            // sharing one incoming lane compete for the same physical space.
            laneGroups.values.forEach { group ->
                while (true) {
                    val candidate = group.mapNotNull { state ->
                        state.pending.firstOrNull()?.let { state to it }
                    }.minWithOrNull(compareBy<Pair<MovementState, WaitingVehicle>>({ it.second.arrivalTime }, { it.first.connectionId }))
                        ?: break
                    val waiting = candidate.second
                    val tail = group.flatMap { it.vehicles }
                        .filter { it.positionMeters <= 0.0 }
                        .minByOrNull { it.positionMeters - it.waiting.model.lengthMeters }
                    val upstreamFront = if (tail == null) {
                        -demand.approachStorageLengthMeters + waiting.model.lengthMeters
                    } else {
                        tail.positionMeters - tail.waiting.model.lengthMeters -
                            waiting.model.minimumGapMeters
                    }
                    val upstreamRear = upstreamFront - waiting.model.lengthMeters
                    val fits = upstreamRear >= -demand.approachStorageLengthMeters - 1e-6
                    if (!fits || upstreamFront >= 0.0) break
                    candidate.first.pending.removeFirst()
                    candidate.first.vehicles += VehicleAgent(
                        waiting = waiting,
                        positionMeters = upstreamFront,
                        speedMetersPerSecond = min(demand.approachSpeedKmh, waiting.model.maximumSpeedKmh) / 3.6
                    )
                }
            }

            // Lane choice is route-based: a vehicle may move only to another
            // source lane on the same approach that permits the same manoeuvre.
            // Both the target front and rear gaps are checked before transfer.
            val laneChanges = mutableListOf<Triple<MovementState, MovementState, VehicleAgent>>()
            approachGroups.values.forEach { approachStates ->
                val statesByLane = approachStates.groupBy { it.fromLaneId }
                approachStates.forEach { sourceState ->
                    val sourceVehicles = sourceState.vehicles.toList()
                    sourceVehicles.forEach vehicleLoop@{ vehicle ->
                        if (vehicle.positionMeters !in -55.0..-5.0 ||
                            vehicle.speedMetersPerSecond < 2.0 || vehicle.laneChangeProgress < 1.0) return@vehicleLoop
                        val sourceLane = approachLanes[sourceState.fromApproachId]?.get(sourceState.fromLaneId)
                            ?: return@vehicleLoop
                        val sourceLaneAgents = statesByLane[sourceState.fromLaneId].orEmpty().flatMap { it.vehicles }
                        val sourceLeader = sourceLaneAgents.filter { it !== vehicle && it.positionMeters > vehicle.positionMeters }
                            .minByOrNull { it.positionMeters - vehicle.positionMeters }
                        val sourceGap = sourceLeader?.let {
                            it.positionMeters - it.waiting.model.lengthMeters - vehicle.positionMeters
                        } ?: Double.POSITIVE_INFINITY
                        val sourceLoad = sourceLaneAgents.count { it.positionMeters <= 0.0 }
                        val candidates = approachStates.filter { target ->
                            if (target.fromLaneId == sourceState.fromLaneId || target.movementKind != sourceState.movementKind) return@filter false
                            val targetLane = approachLanes[target.fromApproachId]?.get(target.fromLaneId) ?: return@filter false
                            targetLane.allowedMovements.contains(target.movementKind) &&
                                targetLane.widthMeters >= vehicle.waiting.model.widthMeters + 0.2
                        }.mapNotNull { target ->
                            val targetAgents = statesByLane[target.fromLaneId].orEmpty().flatMap { it.vehicles }
                                .filter { it.positionMeters <= 0.0 && it !== vehicle }
                            val leader = targetAgents.filter { it.positionMeters > vehicle.positionMeters }
                                .minByOrNull { it.positionMeters - vehicle.positionMeters }
                            val follower = targetAgents.filter { it.positionMeters < vehicle.positionMeters }
                                .maxByOrNull { it.positionMeters }
                            val frontGap = leader?.let {
                                it.positionMeters - it.waiting.model.lengthMeters - vehicle.positionMeters
                            } ?: Double.POSITIVE_INFINITY
                            val rearGap = follower?.let {
                                vehicle.positionMeters - vehicle.waiting.model.lengthMeters - it.positionMeters
                            } ?: Double.POSITIVE_INFINITY
                            val requiredFront = vehicle.waiting.model.minimumGapMeters + vehicle.speedMetersPerSecond * 0.45
                            val requiredRear = (follower?.waiting?.model?.minimumGapMeters ?: 0.0) +
                                (follower?.speedMetersPerSecond ?: 0.0) * 0.45
                            if (frontGap + 1e-6 < requiredFront || rearGap + 1e-6 < requiredRear) null
                            else Triple(target, targetAgents.count { it.positionMeters <= 0.0 }, frontGap)
                        }
                        val best = candidates.minWithOrNull(compareBy<Triple<MovementState, Int, Double>>({ it.second }, { -it.third }))
                        if (best != null && (sourceGap < 24.0 || sourceLoad > best.second + 1)) {
                            laneChanges += Triple(sourceState, best.first, vehicle)
                        }
                    }
                }
            }
            laneChanges.distinctBy { it.third.waiting.id }.forEach { (source, target, vehicle) ->
                if (source.vehicles.remove(vehicle)) {
                    vehicle.laneChangeFromConnectionId = source.connectionId
                    vehicle.laneChangeProgress = 0.0
                    target.vehicles += vehicle
                }
            }

            // Update vehicles front-to-back on each physical approach lane. On
            // the approach, a vehicle follows any leader sharing its current lane;
            // after crossing, it follows only vehicles on its own movement path.
            laneGroups.values.forEach { group ->
                val allAgents = group.flatMap { state -> state.vehicles.map { state to it } }
                val ordered = allAgents.sortedByDescending { it.second.positionMeters }
                for ((state, vehicle) in ordered) {
                    val model = vehicle.waiting.model
                    val onApproach = vehicle.positionMeters < 0.0
                    val leaders = if (onApproach) {
                        ordered.filter { candidate ->
                            val candidateState = candidate.first
                            val candidateVehicle = candidate.second
                            candidateVehicle !== vehicle &&
                                candidateVehicle.positionMeters > vehicle.positionMeters &&
                                (candidateVehicle.positionMeters <= 0.0 ||
                                    candidateState.connectionId == state.connectionId)
                        }.map { it.second }
                    } else {
                        state.vehicles.filter {
                            it !== vehicle && it.positionMeters > vehicle.positionMeters
                        }
                    }
                    val leader = leaders.minByOrNull { it.positionMeters - vehicle.positionMeters }
                    var obstacleGap: Double? = leader?.let {
                        it.positionMeters - it.waiting.model.lengthMeters - vehicle.positionMeters
                    }
                    var obstacleSpeed: Double? = leader?.speedMetersPerSecond

                    if (onApproach && !greenFor(state.connectionId, time)) {
                        val stopLineGap = -vehicle.positionMeters
                        if (obstacleGap == null || stopLineGap < obstacleGap) {
                            obstacleGap = stopLineGap
                            obstacleSpeed = 0.0
                        }
                    }

                    val desiredSpeed = min(demand.approachSpeedKmh, model.maximumSpeedKmh) / 3.6
                    val acceleration = idmAcceleration(vehicle, obstacleGap, obstacleSpeed, desiredSpeed)
                    val oldPosition = vehicle.positionMeters
                    val nextSpeed = (vehicle.speedMetersPerSecond + acceleration * step)
                        .coerceIn(0.0, desiredSpeed.coerceAtLeast(0.1))
                    var nextPosition = oldPosition +
                        (vehicle.speedMetersPerSecond + nextSpeed) * 0.5 * step

                    if (onApproach && !greenFor(state.connectionId, time) && nextPosition >= 0.0) {
                        nextPosition = -0.05
                        vehicle.speedMetersPerSecond = 0.0
                    } else if (leader != null) {
                        val safeFront = leader.positionMeters - leader.waiting.model.lengthMeters -
                            model.minimumGapMeters
                        if (nextPosition > safeFront) {
                            nextPosition = max(oldPosition, safeFront)
                            vehicle.speedMetersPerSecond = min(nextSpeed, leader.speedMetersPerSecond)
                        } else {
                            vehicle.speedMetersPerSecond = nextSpeed
                        }
                    } else {
                        vehicle.speedMetersPerSecond = nextSpeed
                    }
                    vehicle.positionMeters = nextPosition
                    vehicle.accelerationMetersPerSecondSquared = acceleration
                    if (vehicle.laneChangeFromConnectionId != null) {
                        vehicle.laneChangeProgress = (vehicle.laneChangeProgress + step / 3.0).coerceAtMost(1.0)
                        if (vehicle.laneChangeProgress >= 1.0) vehicle.laneChangeFromConnectionId = null
                    }

                    if (vehicle.crossingTimeSeconds == null && oldPosition < 0.0 && nextPosition >= 0.0) {
                        val fraction = (0.0 - oldPosition) / (nextPosition - oldPosition).coerceAtLeast(1e-9)
                        val crossingTime = time + fraction.coerceIn(0.0, 1.0) * step
                        vehicle.crossingTimeSeconds = crossingTime
                        (states[vehicle.waiting.demandConnectionId] ?: state).departures++
                        totalDepartures++
                        categoryDepartures[vehicle.waiting.vehicleClass] =
                            (categoryDepartures[vehicle.waiting.vehicleClass] ?: 0) + 1
                        val freeFlowSpeed = min(demand.approachSpeedKmh, model.maximumSpeedKmh) / 3.6
                        val freeFlowSeconds = demand.approachStorageLengthMeters / freeFlowSpeed.coerceAtLeast(0.1)
                        val delay = (crossingTime - vehicle.waiting.arrivalTime - freeFlowSeconds).coerceAtLeast(0.0)
                        val demandState = states[vehicle.waiting.demandConnectionId] ?: state
                        demandState.delaySum += delay
                        demandState.delayVehicleHours += delay / 3600.0
                        categoryDelay[vehicle.waiting.vehicleClass] =
                            (categoryDelay[vehicle.waiting.vehicleClass] ?: 0.0) + delay
                    }
                }
            }

            // Remove vehicles only after their rear bumper has cleared the path.
            states.values.forEach { state ->
                state.vehicles.removeAll { vehicle ->
                    vehicle.positionMeters - vehicle.waiting.model.lengthMeters > state.pathLengthMeters
                }
            }

            var queueTotal = 0
            var spillbackTotal = 0
            laneGroups.values.forEach { group ->
                val laneVehicles = group.flatMap { it.vehicles }
                val laneQueue = laneVehicles.count { it.positionMeters <= 0.0 } +
                    group.sumOf { it.pending.size }
                val laneSpillback = group.sumOf { it.pending.size }
                val laneQueueLength = approachQueueLength(laneVehicles)
                queueTotal += laneQueue
                spillbackTotal += laneSpillback
                maximumQueueLengthMeters = max(maximumQueueLengthMeters, laneQueueLength)
                group.forEach { state ->
                    val ownQueue = state.vehicles.count { it.positionMeters <= 0.0 } + state.pending.size
                    state.maxQueue = max(state.maxQueue, ownQueue)
                    state.maxQueueLengthMeters = max(state.maxQueueLengthMeters,
                        approachQueueLength(state.vehicles))
                    state.maxSpillback = max(state.maxSpillback, state.pending.size)
                }
            }
            maximumQueueVehicles = max(maximumQueueVehicles, queueTotal)
            maximumSpillbackVehicles = max(maximumSpillbackVehicles, spillbackTotal)

            if (time + 1e-9 >= snapshotAt) {
                val visible = states.values.flatMap { state ->
                    state.vehicles.sortedByDescending { it.positionMeters }.mapIndexed { index, vehicle ->
                        val centerPosition = vehicle.positionMeters - vehicle.waiting.model.lengthMeters / 2.0
                        SimulatedVehicle(
                            id = vehicle.waiting.id,
                            connectionId = state.connectionId,
                            vehicleClass = vehicle.waiting.vehicleClass,
                            progress = centerPosition / state.pathLengthMeters,
                            queued = vehicle.positionMeters <= 0.0,
                            queueIndex = index,
                            positionMeters = vehicle.positionMeters,
                            speedMetersPerSecond = vehicle.speedMetersPerSecond,
                            lengthMeters = vehicle.waiting.model.lengthMeters,
                            widthMeters = vehicle.waiting.model.widthMeters,
                            laneChangeFromConnectionId = vehicle.laneChangeFromConnectionId,
                            laneChangeProgress = vehicle.laneChangeProgress
                        )
                    }
                }.toMutableList()

                // Render the upstream spillback as a physically spaced queue beyond
                // the finite approach-storage boundary, rather than only showing a count.
                laneGroups.values.forEach { group ->
                    val tailRear = group.flatMap { it.vehicles }
                        .filter { it.positionMeters <= 0.0 }
                        .minOfOrNull { it.positionMeters - it.waiting.model.lengthMeters }
                        ?: -demand.approachStorageLengthMeters
                    var nextRear = tailRear
                    val pending = group.flatMap { state ->
                        state.pending.map { waiting -> state to waiting }
                    }.sortedBy { it.second.arrivalTime }
                    pending.forEachIndexed { index, (state, waiting) ->
                        val frontPosition = nextRear - waiting.model.minimumGapMeters
                        val centerPosition = frontPosition - waiting.model.lengthMeters / 2.0
                        visible += SimulatedVehicle(
                            id = waiting.id,
                            connectionId = state.connectionId,
                            vehicleClass = waiting.vehicleClass,
                            progress = centerPosition / state.pathLengthMeters,
                            queued = true,
                            queueIndex = index,
                            positionMeters = frontPosition,
                            speedMetersPerSecond = 0.0,
                            lengthMeters = waiting.model.lengthMeters,
                            widthMeters = waiting.model.widthMeters
                        )
                        nextRear = frontPosition - waiting.model.lengthMeters
                    }
                }

                snapshots += TrafficSimulationSnapshot(
                    timeSeconds = time,
                    activeVehicles = visible.take(500),
                    totalQueueVehicles = queueTotal,
                    departedVehicles = totalDepartures,
                    approachStorageLengthMeters = demand.approachStorageLengthMeters,
                    spillbackVehicles = spillbackTotal
                )
                snapshotAt += 1.0
            }
            time += step
        }

        // Include delay of vehicles still on the approach or waiting upstream.
        states.values.forEach { state ->
            val notYetCrossed = state.vehicles.filter { it.crossingTimeSeconds == null } +
                state.pending.map { waiting ->
                    VehicleAgent(waiting, -demand.approachStorageLengthMeters, 0.0)
                }
            notYetCrossed.forEach { vehicle ->
                val model = vehicle.waiting.model
                val freeFlowSpeed = min(demand.approachSpeedKmh, model.maximumSpeedKmh) / 3.6
                val freeFlowSeconds = demand.approachStorageLengthMeters / freeFlowSpeed.coerceAtLeast(0.1)
                val delay = (demand.durationSeconds - vehicle.waiting.arrivalTime - freeFlowSeconds).coerceAtLeast(0.0)
                state.delaySum += delay
                state.delayVehicleHours += delay / 3600.0
                categoryDelay[vehicle.waiting.vehicleClass] =
                    (categoryDelay[vehicle.waiting.vehicleClass] ?: 0.0) + delay
            }
        }

        val movementMetrics = states.values.map { state ->
            val queueVehicles = state.vehicles.count { it.positionMeters <= 0.0 } + state.pending.size
            MovementSimulationMetrics(
                connectionId = state.connectionId,
                arrivals = state.arrivals,
                departures = state.departures,
                queueVehiclesAtEnd = queueVehicles,
                maximumQueueVehicles = state.maxQueue,
                averageDelaySeconds = if (state.arrivals == 0) 0.0 else state.delaySum / state.arrivals,
                totalDelayVehicleHours = state.delayVehicleHours,
                queueLengthMetersAtEnd = approachQueueLength(state.vehicles),
                maximumQueueLengthMeters = state.maxQueueLengthMeters,
                spillbackVehiclesAtEnd = state.pending.size,
                maximumSpillbackVehicles = state.maxSpillback
            )
        }
        val categoryMetrics = (categoryArrivals.keys + categoryDepartures.keys).distinct().map { cls ->
            val arrivals = categoryArrivals[cls] ?: 0
            CategorySimulationMetrics(
                cls, arrivals, categoryDepartures[cls] ?: 0,
                if (arrivals == 0) 0.0 else (categoryDelay[cls] ?: 0.0) / arrivals
            )
        }
        val totalDelay = movementMetrics.sumOf { it.totalDelayVehicleHours }
        val totalDelaySeconds = movementMetrics.sumOf { it.averageDelaySeconds * it.arrivals }
        val finalQueue = movementMetrics.sumOf { it.queueVehiclesAtEnd }
        val finalSpillback = movementMetrics.sumOf { it.spillbackVehiclesAtEnd }
        val physicalLaneVehicles = states.values.sumOf { state -> state.vehicles.count { it.positionMeters <= 0.0 } }
        val finalLaneQueueLength = laneGroups.values.maxOfOrNull { group ->
            approachQueueLength(group.flatMap { it.vehicles })
        } ?: 0.0
        return TrafficSimulationResult(
            durationSeconds = demand.durationSeconds,
            cycleLengthSeconds = timing.cycleLengthSeconds,
            totalArrivals = totalArrivals,
            totalDepartures = totalDepartures,
            totalQueueAtEnd = finalQueue,
            maximumQueueVehicles = maximumQueueVehicles,
            averageDelaySeconds = if (totalArrivals == 0) 0.0 else totalDelaySeconds / totalArrivals,
            totalDelayVehicleHours = totalDelay,
            movementMetrics = movementMetrics,
            categoryMetrics = categoryMetrics,
            snapshots = snapshots,
            diagnostics = diagnostics.distinct(),
            maximumQueueLengthMeters = maximumQueueLengthMeters,
            spillbackVehiclesAtEnd = finalSpillback,
            maximumSpillbackVehicles = maximumSpillbackVehicles,
            physicalLaneVehiclesAtEnd = physicalLaneVehicles,
            upstreamQueueAtEnd = finalSpillback,
            laneStorageUtilizationPercent = (finalLaneQueueLength / demand.approachStorageLengthMeters * 100.0).coerceIn(0.0, 100.0),
            maximumLaneStorageUtilizationPercent = (maximumQueueLengthMeters / demand.approachStorageLengthMeters * 100.0).coerceIn(0.0, 100.0)
        )
    }

    private fun polylineLength(points: List<Point2D>): Double =
        points.zipWithNext().sumOf { (a, b) -> hypot(b.xMeters - a.xMeters, b.yMeters - a.yMeters) }
}
