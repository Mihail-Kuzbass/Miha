package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.LaneConnection
import ru.trafficsignalstudio.model.Point2D
import ru.trafficsignalstudio.model.VehicleClass
import ru.trafficsignalstudio.model.VehicleModel
import ru.trafficsignalstudio.model.VehicleModelCatalog
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.hypot

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
    val approachStorageLengthMeters: Double = 120.0,
    val minimumGapMeters: Double = 2.0,
    val desiredHeadwaySeconds: Double = 1.2
) {
    init {
        require(durationSeconds in 60..7200)
        require(timeStepSeconds in 0.05..1.0)
        require(approachStorageLengthMeters in 20.0..1000.0)
        require(minimumGapMeters in 0.5..10.0)
        require(desiredHeadwaySeconds in 0.5..3.0)
        require(movements.map { it.connectionId }.distinct().size == movements.size)
    }
}

data class SimulatedVehicle(
    val id: Long,
    val connectionId: String,
    val vehicleClass: VehicleClass?,
    /** 0..1 on the movement path; negative values are measured upstream from the stop line. */
    val progress: Double,
    val queued: Boolean,
    val queueIndex: Int,
    val lengthMeters: Double = 4.6,
    val widthMeters: Double = 1.8,
    val speedKmh: Double = 0.0,
    /** Distance from the stop line; meaningful for vehicles still on the approach. */
    val distanceToStopLineMeters: Double? = null
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
    val departedVehicles: Int,
    val physicalLaneVehicles: Int = 0,
    val upstreamWaitingVehicles: Int = 0
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
    val physicalLaneVehiclesAtEnd: Int = 0,
    val upstreamQueueAtEnd: Int = 0,
    val laneStorageUtilizationPercent: Double = 0.0,
    val maximumLaneStorageUtilizationPercent: Double = 0.0
) {
    val throughputVehiclesPerHour: Double
        get() = totalDepartures * 3600.0 / durationSeconds.coerceAtLeast(1)
}

/**
 * Fixed-step longitudinal traffic simulation.
 *
 * Vehicles occupy a shared physical approach lane, are inserted only when their
 * complete body fits into the modeled storage, follow a leader using an IDM-style
 * car-following equation, stop at red, and enter their movement path only on green.
 * This is a deterministic engineering model, not yet a calibrated microsimulation.
 */
object TrafficSimulationEngine {
    private data class WaitingVehicle(
        val id: Long,
        val connectionId: String,
        val vehicleClass: VehicleClass?,
        val arrivalTime: Double,
        val model: VehicleModel,
        var distanceToStopLineMeters: Double = 0.0,
        var speedMps: Double = 0.0
    )

    private data class ActiveVehicle(
        val waiting: WaitingVehicle,
        var distanceAlongPathMeters: Double = 0.0,
        var speedMps: Double = 0.0
    )

    private class MovementState(
        val connection: LaneConnection,
        val pathLengthMeters: Double
    ) {
        val active = mutableListOf<ActiveVehicle>()
        var arrivals = 0
        var departures = 0
        var maxQueue = 0
        var delaySum = 0.0
        var delayVehicleHours = 0.0
        var nextDepartureTime = 0.0
    }

    private class PhysicalLaneState(
        val key: String,
        val movements: List<String>
    ) {
        /** Sorted by distance to stop line: front-most vehicle first. */
        val vehicles = mutableListOf<WaitingVehicle>()
        val upstreamQueue = ArrayDeque<WaitingVehicle>()
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

        val trajectories = TrajectoryConflictAnalyzer.buildMovementTrajectories(intersection)
            .associateBy { it.connectionId }
        val knownConnections = intersection.laneConnections.associateBy { it.id }
        val configured = demand.movements.mapNotNull { item ->
            knownConnections[item.connectionId]?.let { it to item }
        }
        if (configured.size != demand.movements.size) {
            diagnostics += "Часть потоков ссылается на отсутствующие связи полос и не включена в расчёт."
        }

        val states = configured.associate { (connection, _) ->
            val pathLength = trajectories[connection.id]?.points?.let(::polylineLength)?.coerceAtLeast(8.0) ?: 20.0
            connection.id to MovementState(connection, pathLength)
        }
        // Group from the actual source lane; movements sharing it share one physical queue.
        val physicalLanes = configured.groupBy { (connection, _) ->
            "${connection.fromApproachId}|${connection.fromLaneId}"
        }.mapValues { (key, entries) ->
            PhysicalLaneState(key, entries.map { it.first.id })
        }.values

        var nextId = 1L
        val nextArrival = mutableMapOf<Pair<String, VehicleClass?>, Double>()
        configured.forEach { (connection, movement) ->
            val streams = movement.categoryFlowsPerHour.map { it.key to it.value }.toMutableList()
            if (movement.unclassifiedVehiclesPerHour > 1e-6) {
                streams += null to movement.unclassifiedVehiclesPerHour
            }
            streams.forEach { (vehicleClass, rate) ->
                if (rate > 0.0) nextArrival[connection.id to vehicleClass] = 3600.0 / rate
            }
        }

        val categoryArrivals = mutableMapOf<VehicleClass?, Int>()
        val categoryDepartures = mutableMapOf<VehicleClass?, Int>()
        val categoryDelay = mutableMapOf<VehicleClass?, Double>()
        val snapshots = mutableListOf<TrafficSimulationSnapshot>()
        val laneByMovement = physicalLanes.flatMap { lane -> lane.movements.map { it to lane } }.toMap()
        var time = 0.0
        var totalArrivals = 0
        var totalDepartures = 0
        var globalMaxQueue = 0
        var maximumLaneStorageUtilizationPercent = 0.0
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

        fun modelFor(vehicleClass: VehicleClass?): VehicleModel =
            vehicleClass?.let { cls -> VehicleModelCatalog.defaults.firstOrNull { it.vehicleClass == cls } }
                ?: VehicleModelCatalog.defaultPassengerCar

        fun canEnterStorage(lane: PhysicalLaneState, vehicle: WaitingVehicle): Boolean {
            val farthest = lane.vehicles.maxByOrNull { it.distanceToStopLineMeters }
            val candidateFront = if (farthest == null) {
                demand.approachStorageLengthMeters - vehicle.model.lengthMeters
            } else {
                farthest.distanceToStopLineMeters + farthest.model.lengthMeters + demand.minimumGapMeters
            }
            return candidateFront + vehicle.model.lengthMeters <= demand.approachStorageLengthMeters + 1e-6
        }

        fun laneQueueCount(lane: PhysicalLaneState, connectionId: String): Int =
            lane.vehicles.count { it.connectionId == connectionId } +
                lane.upstreamQueue.count { it.connectionId == connectionId }

        val step = demand.timeStepSeconds
        while (time <= demand.durationSeconds + 1e-9) {
            // Demand arrivals enter a shared upstream reservoir for their source lane.
            configured.forEach { (connection, movement) ->
                val lane = laneByMovement.getValue(connection.id)
                val streams = movement.categoryFlowsPerHour.map { it.key to it.value } +
                    listOf(null to movement.unclassifiedVehiclesPerHour)
                streams.forEach streamLoop@{ (vehicleClass, rate) ->
                    if (rate <= 0.0) return@streamLoop
                    val key = connection.id to vehicleClass
                    val interval = 3600.0 / rate
                    var arrival = nextArrival[key] ?: interval
                    while (arrival <= time + 1e-9) {
                        val vehicle = WaitingVehicle(nextId++, connection.id, vehicleClass, arrival, modelFor(vehicleClass))
                        lane.upstreamQueue.addLast(vehicle)
                        states.getValue(connection.id).arrivals++
                        totalArrivals++
                        categoryArrivals[vehicleClass] = (categoryArrivals[vehicleClass] ?: 0) + 1
                        arrival += interval
                    }
                    nextArrival[key] = arrival
                }
            }

            // Feed upstream demand into the finite physical lane storage when a full body fits.
            physicalLanes.forEach { lane ->
                while (lane.upstreamQueue.isNotEmpty()) {
                    val candidate = lane.upstreamQueue.first()
                    if (!canEnterStorage(lane, candidate)) break
                    lane.upstreamQueue.removeFirst()
                    candidate.distanceToStopLineMeters = if (lane.vehicles.isEmpty()) {
                        demand.approachStorageLengthMeters - candidate.model.lengthMeters
                    } else {
                        val farthest = lane.vehicles.maxBy { it.distanceToStopLineMeters }
                        farthest.distanceToStopLineMeters + farthest.model.lengthMeters + demand.minimumGapMeters
                    }
                    lane.vehicles += candidate
                    lane.vehicles.sortBy { it.distanceToStopLineMeters }
                }
            }

            // Update each lane from its leader to its followers. No two vehicle bodies overlap.
            physicalLanes.forEach { lane ->
                var index = 0
                while (index < lane.vehicles.size) {
                    val vehicle = lane.vehicles[index]
                    val state = states.getValue(vehicle.connectionId)
                    val leader = if (index > 0) lane.vehicles[index - 1] else null
                    val green = greenFor(vehicle.connectionId, time) &&
                        time + 1e-9 >= state.nextDepartureTime
                    val desiredSpeed = min(vehicle.model.maximumSpeedKmh, 50.0) / 3.6
                    val maxAcceleration = vehicle.model.accelerationMetersPerSecondSquared.coerceAtLeast(0.1)
                    val comfortableDeceleration = vehicle.model.serviceDecelerationMetersPerSecondSquared.coerceAtLeast(0.5)
                    val gap: Double
                    val leaderSpeed: Double
                    if (leader != null) {
                        gap = (vehicle.distanceToStopLineMeters -
                            (leader.distanceToStopLineMeters + leader.model.lengthMeters)).coerceAtLeast(0.05)
                        leaderSpeed = leader.speedMps
                    } else if (!green) {
                        gap = vehicle.distanceToStopLineMeters.coerceAtLeast(0.05)
                        leaderSpeed = 0.0
                    } else {
                        gap = Double.POSITIVE_INFINITY
                        leaderSpeed = vehicle.speedMps
                    }

                    val speed = vehicle.speedMps
                    val closingSpeed = speed - leaderSpeed
                    val desiredGap = if (gap.isInfinite()) 0.0 else {
                        val dynamicGap = speed * demand.desiredHeadwaySeconds +
                            speed * closingSpeed / (2.0 * sqrt(maxAcceleration * comfortableDeceleration))
                        demand.minimumGapMeters + max(0.0, dynamicGap)
                    }
                    val interaction = if (gap.isInfinite()) 0.0 else (desiredGap / gap).pow(2.0)
                    val freeRoad = if (desiredSpeed <= 0.0) 0.0 else (speed / desiredSpeed).pow(4.0)
                    val acceleration = (maxAcceleration * (1.0 - freeRoad - interaction))
                        .coerceIn(-comfortableDeceleration, maxAcceleration)
                    val newSpeed = (speed + acceleration * step).coerceIn(0.0, desiredSpeed)
                    val newPosition = vehicle.distanceToStopLineMeters -
                        (speed + newSpeed) * 0.5 * step

                    if (leader == null && green && newPosition <= 0.0) {
                        val traveled = vehicle.distanceToStopLineMeters.coerceAtLeast(0.0)
                        val crossingFraction = if (traveled <= 1e-9) 0.0 else
                            (traveled / ((speed + newSpeed) * 0.5 * step).coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
                        val departureTime = time + crossingFraction * step
                        val delay = (departureTime - vehicle.arrivalTime).coerceAtLeast(0.0)
                        state.active += ActiveVehicle(vehicle, 0.0, max(newSpeed, 1.0))
                        state.departures++
                        totalDepartures++
                        state.delaySum += delay
                        state.delayVehicleHours += delay / 3600.0
                        categoryDepartures[vehicle.vehicleClass] = (categoryDepartures[vehicle.vehicleClass] ?: 0) + 1
                        categoryDelay[vehicle.vehicleClass] = (categoryDelay[vehicle.vehicleClass] ?: 0.0) + delay
                        state.nextDepartureTime = departureTime + max(0.9, 1.0 + vehicle.model.lengthMeters / 40.0)
                        lane.vehicles.removeAt(index)
                        continue
                    }

                    vehicle.speedMps = newSpeed
                    vehicle.distanceToStopLineMeters = max(0.0, newPosition)
                    index++
                }

                lane.upstreamQueue.firstOrNull()?.let { waiting ->
                    // Upstream demand is physically queued; its delay is retained in the final statistics.
                    if (waiting.distanceToStopLineMeters == 0.0) waiting.distanceToStopLineMeters = demand.approachStorageLengthMeters
                }
            }

            // Vehicles already inside the junction advance along their own movement trajectories.
            states.values.forEach { state ->
                val iterator = state.active.iterator()
                while (iterator.hasNext()) {
                    val active = iterator.next()
                    val desiredSpeed = min(active.waiting.model.maximumSpeedKmh, 30.0) / 3.6
                    val acceleration = active.waiting.model.accelerationMetersPerSecondSquared
                    active.speedMps = min(desiredSpeed, active.speedMps + acceleration * step)
                    active.distanceAlongPathMeters += active.speedMps * step
                    if (active.distanceAlongPathMeters >= state.pathLengthMeters) iterator.remove()
                }
            }

            val queueTotal = physicalLanes.sumOf { it.vehicles.size + it.upstreamQueue.size }
            val laneVehicleTotal = physicalLanes.sumOf { it.vehicles.size }
            val occupiedStorageMeters = physicalLanes.sumOf { lane ->
                lane.vehicles.sumOf { it.model.lengthMeters } +
                    (lane.vehicles.size - 1).coerceAtLeast(0) * demand.minimumGapMeters
            }
            val storageCapacityMeters = physicalLanes.size * demand.approachStorageLengthMeters
            val storageUtilization = if (storageCapacityMeters <= 0.0) 0.0
                else (occupiedStorageMeters / storageCapacityMeters * 100.0).coerceIn(0.0, 100.0)
            maximumLaneStorageUtilizationPercent = maxOf(maximumLaneStorageUtilizationPercent, storageUtilization)
            globalMaxQueue = maxOf(globalMaxQueue, queueTotal)
            states.forEach { (connectionId, state) ->
                val lane = laneByMovement[connectionId]
                state.maxQueue = maxOf(state.maxQueue, lane?.let { laneQueueCount(it, connectionId) } ?: 0)
            }

            if (time + 1e-9 >= snapshotAt) {
                val visible = mutableListOf<SimulatedVehicle>()
                physicalLanes.forEach { lane ->
                    lane.vehicles.forEach { vehicle ->
                        val state = states.getValue(vehicle.connectionId)
                        val queueIndex = lane.vehicles.filter { it.connectionId == vehicle.connectionId }
                            .sortedBy { it.distanceToStopLineMeters }.indexOf(vehicle)
                        visible += SimulatedVehicle(
                            vehicle.id, vehicle.connectionId, vehicle.vehicleClass,
                            -vehicle.distanceToStopLineMeters / state.pathLengthMeters,
                            vehicle.speedMps < 0.5, queueIndex,
                            vehicle.model.lengthMeters, vehicle.model.widthMeters,
                            vehicle.speedMps * 3.6, vehicle.distanceToStopLineMeters
                        )
                    }
                    lane.upstreamQueue.forEachIndexed { index, vehicle ->
                        val state = states.getValue(vehicle.connectionId)
                        val distance = demand.approachStorageLengthMeters +
                            (index + 1) * (vehicle.model.lengthMeters + demand.minimumGapMeters)
                        visible += SimulatedVehicle(
                            vehicle.id, vehicle.connectionId, vehicle.vehicleClass,
                            -distance / state.pathLengthMeters, true, index,
                            vehicle.model.lengthMeters, vehicle.model.widthMeters, 0.0, distance
                        )
                    }
                }
                states.forEach { (connectionId, state) ->
                    state.active.forEach { active ->
                        visible += SimulatedVehicle(
                            active.waiting.id, connectionId, active.waiting.vehicleClass,
                            (active.distanceAlongPathMeters / state.pathLengthMeters).coerceIn(0.0, 1.0),
                            false, 0, active.waiting.model.lengthMeters, active.waiting.model.widthMeters,
                            active.speedMps * 3.6, null
                        )
                    }
                }
                snapshots += TrafficSimulationSnapshot(
                    time, visible.take(500), queueTotal, totalDepartures,
                    laneVehicleTotal, physicalLanes.sumOf { it.upstreamQueue.size }
                )
                snapshotAt += 1.0
            }
            time += step
        }

        // Include delay accumulated by vehicles still on the modeled approach or in the upstream reservoir.
        physicalLanes.forEach { lane ->
            (lane.vehicles + lane.upstreamQueue).forEach { vehicle ->
                val waitingSeconds = (demand.durationSeconds - vehicle.arrivalTime).coerceAtLeast(0.0)
                val state = states.getValue(vehicle.connectionId)
                state.delaySum += waitingSeconds
                state.delayVehicleHours += waitingSeconds / 3600.0
                categoryDelay[vehicle.vehicleClass] = (categoryDelay[vehicle.vehicleClass] ?: 0.0) + waitingSeconds
            }
        }

        val movementMetrics = states.map { (connectionId, state) ->
            val lane = laneByMovement[connectionId]
            MovementSimulationMetrics(
                connectionId, state.arrivals, state.departures,
                lane?.let { laneQueueCount(it, connectionId) } ?: 0, state.maxQueue,
                if (state.arrivals == 0) 0.0 else state.delaySum / state.arrivals,
                state.delayVehicleHours
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
        val physicalLaneVehiclesAtEnd = physicalLanes.sumOf { it.vehicles.size }
        val upstreamQueueAtEnd = physicalLanes.sumOf { it.upstreamQueue.size }
        val occupiedAtEnd = physicalLanes.sumOf { lane ->
            lane.vehicles.sumOf { it.model.lengthMeters } +
                (lane.vehicles.size - 1).coerceAtLeast(0) * demand.minimumGapMeters
        }
        val capacityAtEnd = physicalLanes.size * demand.approachStorageLengthMeters
        val storageUtilizationAtEnd = if (capacityAtEnd <= 0.0) 0.0
            else (occupiedAtEnd / capacityAtEnd * 100.0).coerceIn(0.0, 100.0)
        return TrafficSimulationResult(
            demand.durationSeconds, timing.cycleLengthSeconds, totalArrivals, totalDepartures,
            physicalLaneVehiclesAtEnd + upstreamQueueAtEnd, globalMaxQueue,
            if (totalArrivals == 0) 0.0 else totalDelaySeconds / totalArrivals,
            totalDelay, movementMetrics, categoryMetrics, snapshots, diagnostics.distinct(),
            physicalLaneVehiclesAtEnd, upstreamQueueAtEnd, storageUtilizationAtEnd,
            maximumLaneStorageUtilizationPercent
        )
    }

    private fun polylineLength(points: List<Point2D>): Double =
        points.zipWithNext().sumOf { (a, b) -> hypot(b.xMeters - a.xMeters, b.yMeters - a.yMeters) }
}
