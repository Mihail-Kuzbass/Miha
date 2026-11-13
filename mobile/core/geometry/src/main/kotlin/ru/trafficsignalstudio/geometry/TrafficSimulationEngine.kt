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
import kotlin.random.Random
import kotlin.math.pow

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
    val laneChangeProgress: Double = 1.0,
    val isCrashed: Boolean = false,
    /** Lateral displacement into the opposing lane while passing an incident. */
    val passingOffsetMeters: Double = 0.0
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

enum class CrashIncidentCause { RISK_SCENARIO, PHYSICAL_CONTACT }

data class CrashIncident(
    val id: Int,
    val timeSeconds: Double,
    val clearedAtSeconds: Double,
    val type: CrashRiskBand,
    val participants: Int,
    val affectedConnectionIds: Set<String>,
    val point: Point2D,
    val cause: CrashIncidentCause = CrashIncidentCause.RISK_SCENARIO
)

data class TrafficSimulationSnapshot(
    val timeSeconds: Double,
    val activeVehicles: List<SimulatedVehicle>,
    val totalQueueVehicles: Int,
    val departedVehicles: Int,
    val approachStorageLengthMeters: Double = 120.0,
    val spillbackVehicles: Int = 0,
    val activeIncidents: List<CrashIncident> = emptyList()
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
    val maximumLaneStorageUtilizationPercent: Double,
    val crashRiskPercent: Double = 0.0,
    val geometricConflictCount: Int = 0,
    val crashRiskBand: CrashRiskBand = CrashRiskBand.FOLLOWING_DISTANCE,
    val crashIncidents: List<CrashIncident> = emptyList()
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
        var laneChangeProgress: Double = 1.0,
        var crashedUntilSeconds: Double? = null,
        var crashIncidentId: Int? = null,
        var passingOffsetMeters: Double = 0.0
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
        val riskAssessment = CrashRiskAnalyzer.assess(intersection, demand)
        val movementConflicts = riskAssessment.conflicts
        val conflictingConnectionsById = mutableMapOf<String, MutableSet<String>>()
        movementConflicts.forEach { conflict ->
            conflictingConnectionsById.getOrPut(conflict.firstConnectionId) { mutableSetOf() }
                .add(conflict.secondConnectionId)
            conflictingConnectionsById.getOrPut(conflict.secondConnectionId) { mutableSetOf() }
                .add(conflict.firstConnectionId)
        }
        movementConflicts.forEach { conflict ->
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
        val incidents = mutableListOf<CrashIncident>()
        val blockedUntilByConnection = mutableMapOf<String, Double>()
        val seed = intersection.id.hashCode().toLong() xor demand.durationSeconds.toLong() xor
            demand.movements.sumOf { (it.totalVehiclesPerHour * 10.0).toLong() }
        val random = Random(seed)
        val scheduledIncidentTimes = mutableListOf<Double>()
        if (riskAssessment.band == CrashRiskBand.MULTI_VEHICLE) {
            // High-risk mode permits up to two independently triggered incidents per simulated hour.
            val hourCount = kotlin.math.ceil(demand.durationSeconds / 3600.0).toInt()
            repeat(hourCount) { hour ->
                repeat(2) { slot ->
                    val slotStart = hour * 3600.0 + slot * 1800.0
                    val slotEnd = minOf(demand.durationSeconds.toDouble(), slotStart + 1800.0)
                    if (slotStart < demand.durationSeconds && random.nextDouble() < riskAssessment.riskPercent / 100.0) {
                        scheduledIncidentTimes += random.nextDouble(slotStart, slotEnd.coerceAtLeast(slotStart + 1.0))
                    }
                }
            }
        } else if (riskAssessment.band != CrashRiskBand.NO_CRASH) {
            val runHours = demand.durationSeconds / 3600.0
            val eventProbability = 1.0 - (1.0 - riskAssessment.riskPercent / 100.0)
                .coerceIn(0.0, 1.0).pow(runHours)
            if (random.nextDouble() < eventProbability) {
                scheduledIncidentTimes += random.nextDouble() * demand.durationSeconds
            }
        }
        scheduledIncidentTimes.sort()
        var nextScheduledIncidentIndex = 0

        fun isBlocked(connectionId: String, atSeconds: Double): Boolean =
            (blockedUntilByConnection[connectionId] ?: 0.0) > atSeconds

        fun greenFor(connectionId: String, atSeconds: Double): Boolean {
            if (!timing.isValid || timing.cycleLengthSeconds <= 0 || signalPlan?.enabled != true) return false
            val cycleTime = atSeconds % timing.cycleLengthSeconds
            return timing.phaseWindows.any { window ->
                cycleTime >= window.startSeconds && cycleTime < window.flashingGreenEndSeconds &&
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


        fun pathPose(connectionId: String, progress: Double): Pair<Point2D, Point2D> {
            val points = trajectories.getValue(connectionId).points
            val lengths = points.zipWithNext().map { (a, b) -> hypot(b.xMeters - a.xMeters, b.yMeters - a.yMeters) }
            var distanceAlong = progress * lengths.sum().coerceAtLeast(1e-6)
            var segmentIndex = 0
            if (distanceAlong >= 0.0) {
                while (segmentIndex < lengths.lastIndex && distanceAlong > lengths[segmentIndex]) {
                    distanceAlong -= lengths[segmentIndex]
                    segmentIndex++
                }
            }
            val a = points[segmentIndex]
            val b = points[segmentIndex + 1]
            val segmentLength = lengths[segmentIndex].coerceAtLeast(1e-6)
            val ratio = if (progress < 0.0 || distanceAlong > segmentLength)
                distanceAlong / segmentLength else (distanceAlong / segmentLength).coerceIn(0.0, 1.0)
            val dx = (b.xMeters - a.xMeters) / segmentLength
            val dy = (b.yMeters - a.yMeters) / segmentLength
            return Point2D(a.xMeters + (b.xMeters - a.xMeters) * ratio,
                a.yMeters + (b.yMeters - a.yMeters) * ratio) to Point2D(dx, dy)
        }

        fun worldPose(
            state: MovementState,
            vehicle: VehicleAgent,
            positionMeters: Double = vehicle.positionMeters,
            lateralOffsetMeters: Double = vehicle.passingOffsetMeters
        ): Pair<Point2D, Point2D> {
            val progress = (positionMeters - vehicle.waiting.model.lengthMeters / 2.0) / state.pathLengthMeters
            val target = pathPose(state.connectionId, progress)
            val sourceState = vehicle.laneChangeFromConnectionId?.let { states[it] }
            val basePoint = if (sourceState == null) target.first else {
                val source = pathPose(sourceState.connectionId, progress).first
                val blend = vehicle.laneChangeProgress.coerceIn(0.0, 1.0)
                Point2D(
                    source.xMeters + (target.first.xMeters - source.xMeters) * blend,
                    source.yMeters + (target.first.yMeters - source.yMeters) * blend
                )
            }
            val direction = target.second
            return Point2D(
                basePoint.xMeters - direction.yMeters * lateralOffsetMeters,
                basePoint.yMeters + direction.xMeters * lateralOffsetMeters
            ) to direction
        }

        /**
         * Project a geometric conflict point onto a movement centreline.
         * The returned station is measured from the stop-line origin used by
         * VehicleAgent.positionMeters.
         */
        fun distanceAlongPath(points: List<Point2D>, point: Point2D): Double {
            var travelled = 0.0
            var bestDistance = Double.POSITIVE_INFINITY
            var bestStation = 0.0
            for ((a, b) in points.zipWithNext()) {
                val dx = b.xMeters - a.xMeters
                val dy = b.yMeters - a.yMeters
                val segmentLength = hypot(dx, dy)
                if (segmentLength <= 1e-8) continue
                val projection = (((point.xMeters - a.xMeters) * dx +
                    (point.yMeters - a.yMeters) * dy) / (segmentLength * segmentLength))
                    .coerceIn(0.0, 1.0)
                val projectedX = a.xMeters + dx * projection
                val projectedY = a.yMeters + dy * projection
                val distance = hypot(point.xMeters - projectedX, point.yMeters - projectedY)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestStation = travelled + segmentLength * projection
                }
                travelled += segmentLength
            }
            return bestStation
        }

        data class ConflictZone(
            val firstConnectionId: String,
            val secondConnectionId: String,
            val firstStationMeters: Double,
            val secondStationMeters: Double,
            val point: Point2D
        )

        data class ConflictControl(
            val accelerationMetersPerSecondSquared: Double,
            val stopAtFrontPositionMeters: Double
        )

        // Each geometric crossing becomes a time-domain reservation zone.
        // A vehicle owns the zone from its front entering the clearance envelope
        // until its rear has completely cleared it.
        val conflictZones = movementConflicts.mapNotNull { conflict ->
            val first = trajectories[conflict.firstConnectionId] ?: return@mapNotNull null
            val second = trajectories[conflict.secondConnectionId] ?: return@mapNotNull null
            ConflictZone(
                conflict.firstConnectionId,
                conflict.secondConnectionId,
                distanceAlongPath(first.points, conflict.point),
                distanceAlongPath(second.points, conflict.point),
                conflict.point
            )
        }

        fun timeToCoverDistance(distanceMeters: Double, vehicle: VehicleAgent): Double {
            if (distanceMeters <= 0.0) return 0.0
            val speed = vehicle.speedMetersPerSecond
            val acceleration = vehicle.waiting.model.accelerationMetersPerSecondSquared.coerceAtLeast(0.1)
            if (speed < 0.1) return sqrt(2.0 * distanceMeters / acceleration)
            val discriminant = speed * speed + 2.0 * acceleration * distanceMeters
            return (sqrt(discriminant) - speed) / acceleration
        }

        /**
         * Dynamic conflict-zone reservation. The earliest vehicle to reach a
         * zone gets priority; simulation ID is used only for near-simultaneous
         * arrivals. A vehicle already occupying the zone always retains it.
         */
        fun conflictZoneControl(state: MovementState, vehicle: VehicleAgent): ConflictControl? {
            // A red-signal queue must not reserve a crossing against a green stream.
            // Vehicles already inside the conflict envelope retain occupancy priority.
            if (vehicle.positionMeters < 0.0 &&
                (!greenFor(state.connectionId, time) || isBlocked(state.connectionId, time))) return null
            var selected: ConflictControl? = null
            for (zone in conflictZones) {
                val ownIsFirst = zone.firstConnectionId == state.connectionId
                if (!ownIsFirst && zone.secondConnectionId != state.connectionId) continue
                val otherConnectionId = if (ownIsFirst) zone.secondConnectionId else zone.firstConnectionId
                val ownStation = if (ownIsFirst) zone.firstStationMeters else zone.secondStationMeters
                val otherStation = if (ownIsFirst) zone.secondStationMeters else zone.firstStationMeters
                val otherState = states[otherConnectionId] ?: continue
                for (other in otherState.vehicles) {
                    if (other === vehicle || other.crashedUntilSeconds?.let { time >= it } == true) continue
                    val clearance = max(
                        1.0,
                        (vehicle.waiting.model.widthMeters + other.waiting.model.widthMeters) / 2.0 + 0.75
                    )
                    val ownEntryFront = ownStation - clearance
                    val ownExitFront = ownStation + clearance + vehicle.waiting.model.lengthMeters
                    val otherEntryFront = otherStation - clearance
                    val otherExitFront = otherStation + clearance + other.waiting.model.lengthMeters
                    val ownEntryTime = timeToCoverDistance(ownEntryFront - vehicle.positionMeters, vehicle)
                    val ownExitTime = timeToCoverDistance(ownExitFront - vehicle.positionMeters, vehicle)
                    val otherEntryTime = timeToCoverDistance(otherEntryFront - other.positionMeters, other)
                    val otherExitTime = timeToCoverDistance(otherExitFront - other.positionMeters, other)
                    val ownInside = vehicle.positionMeters >= ownEntryFront &&
                        vehicle.positionMeters - vehicle.waiting.model.lengthMeters <= ownStation + clearance
                    val otherInside = other.positionMeters >= otherEntryFront &&
                        other.positionMeters - other.waiting.model.lengthMeters <= otherStation + clearance
                    val isCloseEnoughToClaim = other.speedMetersPerSecond > 0.25 ||
                        otherEntryFront - other.positionMeters <= 12.0
                    val otherCanClaimZone = otherInside || other.positionMeters >= 0.0 ||
                        (isCloseEnoughToClaim && greenFor(otherConnectionId, time) &&
                            !isBlocked(otherConnectionId, time))
                    if (!otherCanClaimZone) continue
                    val intervalsOverlap = ownEntryTime <= otherExitTime + 0.35 &&
                        otherEntryTime <= ownExitTime + 0.35
                    if (!intervalsOverlap || ownInside) continue
                    val otherHasPriority = otherInside ||
                        (!ownInside && (otherEntryTime < ownEntryTime - 0.35 ||
                            (kotlin.math.abs(otherEntryTime - ownEntryTime) <= 0.35 &&
                                other.waiting.id < vehicle.waiting.id)))
                    if (!otherHasPriority) continue

                    val stopPosition = ownEntryFront - 0.5
                    val distanceToStop = stopPosition - vehicle.positionMeters
                    val serviceDeceleration = vehicle.waiting.model
                        .serviceDecelerationMetersPerSecondSquared.coerceAtLeast(0.5)
                    val reactionAdjustedDistance = distanceToStop -
                        vehicle.speedMetersPerSecond * 0.4
                    val requiredDeceleration = if (reactionAdjustedDistance <= 0.0) serviceDeceleration
                    else (vehicle.speedMetersPerSecond * vehicle.speedMetersPerSecond /
                        (2.0 * reactionAdjustedDistance)).coerceIn(0.8, serviceDeceleration)
                    val candidate = ConflictControl(-requiredDeceleration, stopPosition)
                    if (candidate.stopAtFrontPositionMeters <
                        (selected?.stopAtFrontPositionMeters ?: Double.POSITIVE_INFINITY)) {
                        selected = candidate
                    }
                }
            }
            return selected
        }

        fun opposingGapIsSafe(
            state: MovementState,
            vehicle: VehicleAgent,
            passDistanceMeters: Double,
            targetOffsetMeters: Double
        ): Boolean {
            val ownPose = worldPose(state, vehicle, lateralOffsetMeters = targetOffsetMeters)
            val tangent = ownPose.second
            val sourceHeading = intersection.approaches.firstOrNull { it.id == state.fromApproachId }?.headingDegrees
                ?: return false
            val sourceLane = approachLanes[state.fromApproachId]?.get(state.fromLaneId) ?: return false
            val lateralLimit = sourceLane.widthMeters * 1.6 + vehicle.waiting.model.widthMeters
            val opposingApproaches = intersection.approaches.filter { approach ->
                val difference = ((approach.headingDegrees - sourceHeading + 540.0) % 360.0) - 180.0
                kotlin.math.abs(kotlin.math.abs(difference) - 180.0) <= 25.0
            }.map { it.id }.toSet()
            val opposingStates = states.values.filter { it.fromApproachId in opposingApproaches }
            if (opposingStates.isEmpty()) return false
            val requiredClearance = passDistanceMeters +
                vehicle.speedMetersPerSecond * 2.0 + vehicle.waiting.model.lengthMeters + 5.0
            return opposingStates.all { opposingState ->
                // A crashed oncoming vehicle remains a physical obstruction until
                // its incident is cleared; it must never be ignored by the gap check.
                opposingState.vehicles.all { oncoming ->
                    val oncomingPose = worldPose(opposingState, oncoming)
                    val dx = oncomingPose.first.xMeters - ownPose.first.xMeters
                    val dy = oncomingPose.first.yMeters - ownPose.first.yMeters
                    val along = dx * tangent.xMeters + dy * tangent.yMeters
                    val lateral = kotlin.math.abs(-dx * tangent.yMeters + dy * tangent.xMeters)
                    if (along <= 0.0 || lateral > lateralLimit) true
                    else {
                        val required = requiredClearance +
                            (vehicle.speedMetersPerSecond + oncoming.speedMetersPerSecond) * 2.0 +
                            oncoming.waiting.model.lengthMeters
                        along >= required
                    }
                }
            }
        }

        fun rectanglesOverlap(
            firstCenter: Point2D,
            firstDirection: Point2D,
            firstLength: Double,
            firstWidth: Double,
            secondCenter: Point2D,
            secondDirection: Point2D,
            secondLength: Double,
            secondWidth: Double
        ): Boolean {
            fun corners(center: Point2D, direction: Point2D, length: Double, width: Double): List<Point2D> {
                val halfLength = length / 2.0
                val halfWidth = width / 2.0
                val normalX = -direction.yMeters
                val normalY = direction.xMeters
                return listOf(
                    Point2D(center.xMeters + direction.xMeters * halfLength + normalX * halfWidth,
                        center.yMeters + direction.yMeters * halfLength + normalY * halfWidth),
                    Point2D(center.xMeters + direction.xMeters * halfLength - normalX * halfWidth,
                        center.yMeters + direction.yMeters * halfLength - normalY * halfWidth),
                    Point2D(center.xMeters - direction.xMeters * halfLength + normalX * halfWidth,
                        center.yMeters - direction.yMeters * halfLength + normalY * halfWidth),
                    Point2D(center.xMeters - direction.xMeters * halfLength - normalX * halfWidth,
                        center.yMeters - direction.yMeters * halfLength - normalY * halfWidth)
                )
            }
            val first = corners(firstCenter, firstDirection, firstLength, firstWidth)
            val second = corners(secondCenter, secondDirection, secondLength, secondWidth)
            val firstNormal = Point2D(-firstDirection.yMeters, firstDirection.xMeters)
            val secondNormal = Point2D(-secondDirection.yMeters, secondDirection.xMeters)
            val axes = listOf(firstDirection, firstNormal, secondDirection, secondNormal)
            return axes.all { axis ->
                val firstProjection = first.map { it.xMeters * axis.xMeters + it.yMeters * axis.yMeters }
                val secondProjection = second.map { it.xMeters * axis.xMeters + it.yMeters * axis.yMeters }
                firstProjection.max() >= secondProjection.min() - 0.02 &&
                    secondProjection.max() >= firstProjection.min() - 0.02
            }
        }

        /**
         * Predicts near-future body-envelope conflicts with vehicles on other
         * movements. The vehicle with the later simulation ID yields, giving
         * every conflict a deterministic priority and preventing both drivers
         * from stopping forever at the same conflict point.
         */
        fun predictiveCollisionBrake(state: MovementState, vehicle: VehicleAgent): Double? {
            if (vehicle.speedMetersPerSecond < 0.5) return null
            val serviceDeceleration = vehicle.waiting.model.serviceDecelerationMetersPerSecondSquared
                .coerceAtLeast(0.5)
            // Look far enough ahead for a vehicle travelling at 50 km/h to
            // brake before reaching the conflict area. Fine sampling avoids
            // stepping over a short overlap between perpendicular envelopes.
            val sampleSeconds = 0.1
            val horizonSeconds = 8.0
            val conflictingIds = conflictingConnectionsById[state.connectionId].orEmpty()
            if (conflictingIds.isEmpty()) return null
            val possibleConflicts = states.values.asSequence()
                .filter { it.connectionId in conflictingIds }
                .flatMap { otherState ->
                    otherState.vehicles.asSequence()
                        .filter { other ->
                            other.waiting.id < vehicle.waiting.id &&
                                other.crashedUntilSeconds?.let { time < it } != true &&
                                other.speedMetersPerSecond > 0.25
                        }
                        .map { otherState to it }
                }
            var earliestContact: Double? = null
            for ((otherState, other) in possibleConflicts) {
                var futureTime = sampleSeconds
                while (futureTime <= horizonSeconds + 1e-9) {
                    val ownFuturePosition = vehicle.positionMeters +
                        vehicle.speedMetersPerSecond * futureTime
                    val otherFuturePosition = other.positionMeters +
                        other.speedMetersPerSecond * futureTime
                    val ownPose = worldPose(
                        state, vehicle, ownFuturePosition, vehicle.passingOffsetMeters
                    )
                    val otherPose = worldPose(
                        otherState, other, otherFuturePosition, other.passingOffsetMeters
                    )
                    if (rectanglesOverlap(
                            ownPose.first, ownPose.second,
                            vehicle.waiting.model.lengthMeters, vehicle.waiting.model.widthMeters,
                            otherPose.first, otherPose.second,
                            other.waiting.model.lengthMeters, other.waiting.model.widthMeters
                        )) {
                        if (earliestContact == null || futureTime < earliestContact) {
                            earliestContact = futureTime
                        }
                        break
                    }
                    futureTime += sampleSeconds
                }
            }
            val timeToContact = earliestContact ?: return null
            // The previous half-rate estimate allowed the vehicle to cover
            // almost the entire predicted gap before stopping. Use the full
            // time-to-contact requirement and reserve a reaction/safety margin.
            val usableTime = (timeToContact - 0.4).coerceAtLeast(0.2)
            val requiredDeceleration = (vehicle.speedMetersPerSecond / usableTime)
                .coerceIn(0.8, serviceDeceleration)
            return -requiredDeceleration
        }

        fun detectPhysicalContactCollisions() {
            // Spatial hash keeps envelope checks local instead of comparing every
            // vehicle with every other vehicle on every simulation tick.
            val cellSizeMeters = 20.0
            val active = states.values.flatMap { state ->
                state.vehicles.filter { it.crashedUntilSeconds == null }.map { vehicle ->
                    Triple(state, vehicle, worldPose(state, vehicle))
                }
            }
            val buckets = active.groupBy { entry ->
                kotlin.math.floor(entry.third.first.xMeters / cellSizeMeters).toInt() to
                    kotlin.math.floor(entry.third.first.yMeters / cellSizeMeters).toInt()
            }
            for ((cell, firstEntries) in buckets) {
                for (offsetX in -1..1) for (offsetY in -1..1) {
                    val secondEntries = buckets[(cell.first + offsetX) to (cell.second + offsetY)] ?: continue
                    for ((firstState, firstVehicle, firstPose) in firstEntries) {
                        if (firstVehicle.crashedUntilSeconds != null) continue
                        for ((secondState, secondVehicle, secondPose) in secondEntries) {
                            if (firstVehicle.waiting.id >= secondVehicle.waiting.id ||
                                secondVehicle.crashedUntilSeconds != null) continue
                            if (!rectanglesOverlap(
                                firstPose.first, firstPose.second,
                                firstVehicle.waiting.model.lengthMeters, firstVehicle.waiting.model.widthMeters,
                                secondPose.first, secondPose.second,
                                secondVehicle.waiting.model.lengthMeters, secondVehicle.waiting.model.widthMeters
                            )) continue

                            val incidentId = incidents.size + 1
                            val clearedAt = time + random.nextDouble(45.0, 121.0)
                            firstVehicle.crashedUntilSeconds = clearedAt
                            secondVehicle.crashedUntilSeconds = clearedAt
                            firstVehicle.crashIncidentId = incidentId
                            secondVehicle.crashIncidentId = incidentId
                            firstVehicle.speedMetersPerSecond = 0.0
                            secondVehicle.speedMetersPerSecond = 0.0
                            val affected = setOf(firstState.connectionId, secondState.connectionId)
                            affected.forEach { blockedUntilByConnection[it] = clearedAt }
                            incidents += CrashIncident(
                                incidentId, time, clearedAt,
                                if (riskAssessment.band == CrashRiskBand.NO_CRASH)
                                    CrashRiskBand.INTERSECTION_CONFLICT else riskAssessment.band,
                                2, affected,
                                Point2D(
                                    (firstPose.first.xMeters + secondPose.first.xMeters) / 2.0,
                                    (firstPose.first.yMeters + secondPose.first.yMeters) / 2.0
                                ),
                                CrashIncidentCause.PHYSICAL_CONTACT
                            )
                            diagnostics += "ДТП зарегистрировано по фактическому соприкосновению габаритов автомобилей; затронутые траектории временно заблокированы."
                        }
                    }
                }
            }
        }

        fun tryCreateIncident() {
            val scheduledTime = scheduledIncidentTimes.getOrNull(nextScheduledIncidentIndex) ?: return
            if (time + 1e-9 < scheduledTime) return
            val selected: List<Pair<MovementState, VehicleAgent>>
            val incidentPoint: Point2D
            val affected: Set<String>
            when (riskAssessment.band) {
                CrashRiskBand.NO_CRASH -> return
                CrashRiskBand.FOLLOWING_DISTANCE -> {
                    val pair = laneGroups.values.firstNotNullOfOrNull { group ->
                        val candidates = group.flatMap { state ->
                            state.vehicles.filter { it.crashedUntilSeconds == null && it.positionMeters in -35.0..25.0 }
                                .map { state to it }
                        }.sortedByDescending { it.second.positionMeters }
                        candidates.take(2).takeIf { it.size == 2 }
                    } ?: return
                    selected = pair
                    affected = pair.map { it.first.connectionId }.toSet()
                    incidentPoint = trajectories[pair.first().first.connectionId]?.points?.firstOrNull()
                        ?: Point2D(0.0, 0.0)
                }
                CrashRiskBand.INTERSECTION_CONFLICT, CrashRiskBand.MULTI_VEHICLE -> {
                    val candidate = movementConflicts.firstNotNullOfOrNull { conflict ->
                        val firstState = states[conflict.firstConnectionId] ?: return@firstNotNullOfOrNull null
                        val secondState = states[conflict.secondConnectionId] ?: return@firstNotNullOfOrNull null
                        val firstVehicle = firstState.vehicles.firstOrNull {
                            it.crashedUntilSeconds == null && it.positionMeters in 0.0..(firstState.pathLengthMeters * 0.8)
                        } ?: return@firstNotNullOfOrNull null
                        val secondVehicle = secondState.vehicles.firstOrNull {
                            it.crashedUntilSeconds == null && it.positionMeters in 0.0..(secondState.pathLengthMeters * 0.8)
                        } ?: return@firstNotNullOfOrNull null
                        Triple(conflict, firstState to firstVehicle, secondState to secondVehicle)
                    } ?: return
                    val desired = if (riskAssessment.band == CrashRiskBand.INTERSECTION_CONFLICT)
                        random.nextInt(2, 4) else random.nextInt(1, 11)
                    val connectionIds = setOf(candidate.second.first.connectionId, candidate.third.first.connectionId)
                    val pool = connectionIds.flatMap { id ->
                        val state = states.getValue(id)
                        state.vehicles.filter {
                            it.crashedUntilSeconds == null && it.positionMeters in 0.0..(state.pathLengthMeters * 0.8)
                        }.map { state to it }
                    }.distinctBy { it.second.waiting.id }
                    selected = pool.take(desired).takeIf { it.isNotEmpty() } ?: return
                    affected = connectionIds
                    incidentPoint = candidate.first.point
                }
            }
            val incidentId = incidents.size + 1
            val clearedAt = time + random.nextDouble(45.0, 121.0)
            selected.forEach { (_, vehicle) ->
                vehicle.crashedUntilSeconds = clearedAt
                vehicle.crashIncidentId = incidentId
                vehicle.speedMetersPerSecond = 0.0
            }
            affected.forEach { id -> blockedUntilByConnection[id] = clearedAt }
            incidents += CrashIncident(incidentId, time, clearedAt, riskAssessment.band, selected.size, affected, incidentPoint)
            nextScheduledIncidentIndex++
            diagnostics += when (riskAssessment.band) {
                CrashRiskBand.NO_CRASH -> ""
                CrashRiskBand.FOLLOWING_DISTANCE -> "Смоделировано ДТП из-за несоблюдения дистанции; полоса временно заблокирована."
                CrashRiskBand.INTERSECTION_CONFLICT -> "Смоделировано столкновение 2–3 автомобилей в конфликтной зоне перекрёстка."
                CrashRiskBand.MULTI_VEHICLE -> "Смоделировано групповое столкновение (до 10 участников); движение по затронутым траекториям ограничено."
            }
        }

        val step = demand.timeStepSeconds
        while (time <= demand.durationSeconds + 1e-9) {
            states.values.forEach { state ->
                state.vehicles.removeAll { it.crashedUntilSeconds?.let { until -> until <= time } == true }
            }
            blockedUntilByConnection.entries.removeAll { it.value <= time }
            tryCreateIncident()
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
                        if (vehicle.crashedUntilSeconds != null || vehicle.positionMeters !in -55.0..-5.0 ||
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
                                targetLane.widthMeters >= vehicle.waiting.model.widthMeters + 0.2 &&
                                !isBlocked(target.connectionId, time)
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
                        if (best != null && (isBlocked(sourceState.connectionId, time) || sourceGap < 24.0 || sourceLoad > best.second + 1)) {
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


            // Detour around a stopped crash obstacle by temporarily using the opposing lane.
            // A vehicle may enter it only when the full passing distance plus an oncoming safety
            // margin is available; otherwise it remains queued behind the incident.
            laneGroups.values.forEach { group ->
                val laneAgents = group.flatMap { it.vehicles }
                group.forEach stateLoop@{ sourceState ->
                    val sourceLane = approachLanes[sourceState.fromApproachId]?.get(sourceState.fromLaneId)
                        ?: return@stateLoop
                    sourceState.vehicles.forEach vehicleLoop@{ vehicle ->
                        if (vehicle.crashedUntilSeconds != null) return@vehicleLoop
                        val alreadyPassing = vehicle.passingOffsetMeters > 0.25
                        if (vehicle.positionMeters > 0.0 && !alreadyPassing) return@vehicleLoop
                        val obstacle = laneAgents.filter {
                            it !== vehicle && it.crashedUntilSeconds?.let { until -> time < until } == true &&
                                it.positionMeters > vehicle.positionMeters
                        }.minByOrNull { it.positionMeters }
                        val clearOfObstacle = obstacle == null ||
                            vehicle.positionMeters > obstacle.positionMeters + vehicle.waiting.model.lengthMeters + 5.0
                        val desiredOffset = when {
                            alreadyPassing && !clearOfObstacle -> sourceLane.widthMeters
                            obstacle != null -> {
                                // The crash may belong to another turning movement
                                // that shares this physical lane. Its own connection
                                // being blocked is sufficient reason to pass it.
                                val passDistance = (obstacle.positionMeters - vehicle.positionMeters +
                                    vehicle.waiting.model.lengthMeters + 5.0).coerceAtLeast(0.0)
                                if (opposingGapIsSafe(sourceState, vehicle, passDistance, sourceLane.widthMeters))
                                    sourceLane.widthMeters else 0.0
                            }
                            else -> 0.0
                        }
                        val maxLateralStep = sourceLane.widthMeters * step / 2.5
                        vehicle.passingOffsetMeters += (desiredOffset - vehicle.passingOffsetMeters)
                            .coerceIn(-maxLateralStep, maxLateralStep)
                    }
                }
            }

            // Update vehicles front-to-back on each physical approach lane. On
            // the approach, a vehicle follows any leader sharing its current lane;
            // after crossing, it follows only vehicles on its own movement path.
            laneGroups.values.forEach { group ->
                val allAgents = group.flatMap { state -> state.vehicles.map { state to it } }
                val ordered = allAgents.sortedByDescending { it.second.positionMeters }
                for ((state, vehicle) in ordered) {
                    if (vehicle.crashedUntilSeconds?.let { time < it } == true) {
                        vehicle.speedMetersPerSecond = 0.0
                        continue
                    }
                    val model = vehicle.waiting.model
                    val onApproach = vehicle.positionMeters < 0.0
                    val leaders = if (onApproach) {
                        ordered.filter { candidate ->
                            val candidateState = candidate.first
                            val candidateVehicle = candidate.second
                            candidateVehicle !== vehicle &&
                                candidateVehicle.positionMeters > vehicle.positionMeters &&
                                !(vehicle.passingOffsetMeters > 0.5 && candidateVehicle.crashedUntilSeconds?.let { time < it } == true) &&
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

                    val mustStopAtSignal = onApproach && !greenFor(state.connectionId, time)
                    val mustStopAtBlockedLine = onApproach && isBlocked(state.connectionId, time) &&
                        vehicle.passingOffsetMeters <= 0.5
                    if (mustStopAtSignal || mustStopAtBlockedLine) {
                        val stopLineGap = -vehicle.positionMeters
                        if (obstacleGap == null || stopLineGap < obstacleGap) {
                            obstacleGap = stopLineGap
                            obstacleSpeed = 0.0
                        }
                    }

                    val desiredSpeed = min(demand.approachSpeedKmh, model.maximumSpeedKmh) / 3.6
                    val followingAcceleration = idmAcceleration(vehicle, obstacleGap, obstacleSpeed, desiredSpeed)
                    val zoneControl = conflictZoneControl(state, vehicle)
                    val predictiveAcceleration = predictiveCollisionBrake(state, vehicle)
                    val safetyAcceleration = zoneControl?.accelerationMetersPerSecondSquared
                        ?: predictiveAcceleration
                    val acceleration = if (safetyAcceleration == null) followingAcceleration
                        else min(followingAcceleration, safetyAcceleration)
                    val oldPosition = vehicle.positionMeters
                    val nextSpeed = (vehicle.speedMetersPerSecond + acceleration * step)
                        .coerceIn(0.0, desiredSpeed.coerceAtLeast(0.1))
                    var nextPosition = oldPosition +
                        (vehicle.speedMetersPerSecond + nextSpeed) * 0.5 * step

                    // Enforce the reservation boundary kinematically as well as
                    // through braking, so a large integration step cannot jump
                    // a vehicle into an occupied conflict zone.
                    if (zoneControl != null &&
                        oldPosition <= zoneControl.stopAtFrontPositionMeters &&
                        nextPosition >= zoneControl.stopAtFrontPositionMeters) {
                        nextPosition = zoneControl.stopAtFrontPositionMeters
                        vehicle.speedMetersPerSecond = 0.0
                    }

                    if (onApproach && (!greenFor(state.connectionId, time) ||
                            (isBlocked(state.connectionId, time) && vehicle.passingOffsetMeters <= 0.5)) &&
                        nextPosition >= 0.0) {
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

            // Any overlap of the rendered vehicle envelopes is an actual collision, regardless of risk score.
            detectPhysicalContactCollisions()

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
                            laneChangeProgress = vehicle.laneChangeProgress,
                            isCrashed = vehicle.crashedUntilSeconds?.let { time < it } == true,
                            passingOffsetMeters = vehicle.passingOffsetMeters
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
                    spillbackVehicles = spillbackTotal,
                    activeIncidents = incidents.filter { time < it.clearedAtSeconds }
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
            maximumLaneStorageUtilizationPercent = (maximumQueueLengthMeters / demand.approachStorageLengthMeters * 100.0).coerceIn(0.0, 100.0),
            crashRiskPercent = riskAssessment.riskPercent,
            geometricConflictCount = riskAssessment.geometricConflictCount,
            crashRiskBand = riskAssessment.band,
            crashIncidents = incidents.toList()
        )
    }

    private fun polylineLength(points: List<Point2D>): Double =
        points.zipWithNext().sumOf { (a, b) -> hypot(b.xMeters - a.xMeters, b.yMeters - a.yMeters) }
}
