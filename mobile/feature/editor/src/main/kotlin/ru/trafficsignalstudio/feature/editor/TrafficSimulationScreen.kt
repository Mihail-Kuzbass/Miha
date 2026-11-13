package ru.trafficsignalstudio.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.trafficsignalstudio.geometry.MovementTrafficDemand
import ru.trafficsignalstudio.geometry.SignalPlanTimingAnalyzer
import ru.trafficsignalstudio.geometry.TrafficSimulationDemand
import ru.trafficsignalstudio.geometry.TrafficSimulationEngine
import ru.trafficsignalstudio.geometry.TrafficSimulationResult
import ru.trafficsignalstudio.geometry.TrafficSimulationSnapshot
import ru.trafficsignalstudio.geometry.TrajectoryConflictAnalyzer
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.VehicleClass
import ru.trafficsignalstudio.model.VehicleModelCatalog
import kotlin.math.hypot
import kotlin.math.max

private data class MovementDemandForm(
    val total: String,
    val categories: Map<VehicleClass, String>
)

@Composable
fun TrafficSimulationScreen(intersection: Intersection, onBack: () -> Unit) {
    val defaultCategories = mapOf(
        VehicleClass.PASSENGER_CAR to "420",
        VehicleClass.LIGHT_COMMERCIAL to "60",
        VehicleClass.RIGID_TRUCK to "35",
        VehicleClass.ARTICULATED_TRUCK to "10",
        VehicleClass.CITY_BUS to "35",
        VehicleClass.ARTICULATED_BUS to "10",
        VehicleClass.TROLLEYBUS to "10",
        VehicleClass.ARTICULATED_TROLLEYBUS to "0",
        VehicleClass.MOTORCYCLE to "15",
        VehicleClass.BICYCLE to "5"
    )
    var forms by remember(intersection.id) {
        mutableStateOf(intersection.laneConnections.associate { it.id to MovementDemandForm("600", defaultCategories) })
    }
    var durationMinutes by remember(intersection.id) { mutableStateOf(15) }
    var approachStorageLength by remember(intersection.id) { mutableStateOf("120") }
    var approachSpeed by remember(intersection.id) { mutableStateOf("30") }
    var result by remember(intersection.id) { mutableStateOf<TrafficSimulationResult?>(null) }
    var selectedSnapshotIndex by remember(intersection.id) { mutableStateOf(0) }
    var isPlaying by remember(intersection.id) { mutableStateOf(false) }
    var inputError by remember(intersection.id) { mutableStateOf<String?>(null) }
    val timingReport = remember(intersection.id, intersection.signalPlan, intersection.laneConnections) {
        SignalPlanTimingAnalyzer.analyze(intersection)
    }

    fun updateForm(connectionId: String, form: MovementDemandForm) {
        forms = forms + (connectionId to form)
        result = null
        isPlaying = false
    }

    LaunchedEffect(isPlaying, result) {
        val snapshots = result?.snapshots.orEmpty()
        if (isPlaying && snapshots.isNotEmpty()) {
            var index = selectedSnapshotIndex
            while (index < snapshots.lastIndex) {
                delay(250)
                index += 1
                selectedSnapshotIndex = index
            }
            isPlaying = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Микросимуляция светофорного объекта", style = MaterialTheme.typography.titleLarge)
                Text(intersection.name, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onBack) { Text("Назад") }
        }
        Text("Интенсивность задаётся отдельно для каждого разрешённого движения. Категориальные значения входят в общую интенсивность, а не прибавляются к ней.",
            style = MaterialTheme.typography.bodySmall)
        val totalObjectFlow = forms.values.sumOf { it.total.replace(',', '.').toDoubleOrNull() ?: 0.0 }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Суммарная интенсивность объекта", style = MaterialTheme.typography.titleMedium)
                Text("${"%.0f".format(totalObjectFlow)} авт./ч", style = MaterialTheme.typography.headlineSmall)
                VehicleClass.values().forEach { cls ->
                    val classFlow = forms.values.sumOf { form ->
                        form.categories[cls]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                    }
                    if (classFlow > 0.0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(cls.displayName(), style = MaterialTheme.typography.bodySmall)
                            Text("${"%.0f".format(classFlow)} авт./ч", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (intersection.signalPlan?.phases.isNullOrEmpty()) {
            Card { Text("Для моделирования выпуска транспорта настройте светофорный план и назначьте движения фазам.",
                modifier = Modifier.padding(12.dp)) }
        }
        intersection.laneConnections.forEachIndexed { index, connection ->
            val form = forms[connection.id] ?: MovementDemandForm("600", defaultCategories)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Движение ${index + 1}", style = MaterialTheme.typography.titleMedium)
                    Text("Полоса ${connection.fromLaneId.take(8)} → ${connection.toLaneId.take(8)}",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = form.total,
                        onValueChange = { updateForm(connection.id, form.copy(total = it.filter { c -> c.isDigit() || c == '.' || c == ',' })) },
                        label = { Text("Общая интенсивность, авт./ч") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text("Специальная интенсивность по категориям, авт./ч", style = MaterialTheme.typography.titleSmall)
                    VehicleClass.values().forEach { vehicleClass ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(vehicleClass.displayName(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = form.categories[vehicleClass] ?: "0",
                                onValueChange = { value ->
                                    updateForm(connection.id, form.copy(categories = form.categories + (vehicleClass to value.filter { c -> c.isDigit() || c == '.' || c == ',' })))
                                },
                                modifier = Modifier.weight(0.8f),
                                singleLine = true
                            )
                        }
                    }
                    val classified = form.categories.values.sumOf { it.replace(',', '.').toDoubleOrNull() ?: 0.0 }
                    val total = form.total.replace(',', '.').toDoubleOrNull() ?: 0.0
                    Text("Не распределено по категориям: ${(total - classified).coerceAtLeast(0.0).toInt()} авт./ч",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (classified > total) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Параметры физического подхода", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = approachStorageLength,
                onValueChange = { approachStorageLength = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }; result = null },
                label = { Text("Длина подхода, м") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = approachSpeed,
                onValueChange = { approachSpeed = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }; result = null },
                label = { Text("Скорость входа, км/ч") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        Text("Длина подхода задаёт конечную вместимость полосы до стоп-линии. Если свободного места нет, прибывающие ТС учитываются как подпор за пределами моделируемого участка.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 10, 15, 30).forEach { minutes ->
                if (durationMinutes == minutes) Button(onClick = { durationMinutes = minutes }) { Text("${minutes} мин") }
                else OutlinedButton(onClick = { durationMinutes = minutes }) { Text("${minutes} мин") }
            }
        }
        Button(
            onClick = {
                try {
                    val demands = intersection.laneConnections.map { connection ->
                        val form = forms[connection.id] ?: MovementDemandForm("600", defaultCategories)
                        MovementTrafficDemand(
                            connectionId = connection.id,
                            totalVehiclesPerHour = form.total.replace(',', '.').toDoubleOrNull() ?: 0.0,
                            categoryFlowsPerHour = VehicleClass.values().mapNotNull { cls ->
                                val value = form.categories[cls]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                                if (value > 0.0) cls to value else null
                            }.toMap()
                        )
                    }
                    inputError = null
                    val storageMeters = approachStorageLength.replace(',', '.').toDoubleOrNull()
                        ?: throw IllegalArgumentException("Укажите корректную длину подхода")
                    val entrySpeedKmh = approachSpeed.replace(',', '.').toDoubleOrNull()
                        ?: throw IllegalArgumentException("Укажите корректную скорость входа")
                    result = TrafficSimulationEngine.run(intersection, TrafficSimulationDemand(
                        movements = demands,
                        durationSeconds = durationMinutes * 60,
                        approachStorageLengthMeters = storageMeters,
                        approachSpeedKmh = entrySpeedKmh
                    ))
                    selectedSnapshotIndex = 0
                    isPlaying = false
                } catch (error: IllegalArgumentException) {
                    inputError = error.message ?: "Проверьте значения интенсивности"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Запустить моделирование") }
        inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        result?.let { simulation ->
            Text("Визуализация движения", style = MaterialTheme.typography.titleLarge)
            SignalPhaseStatusCard(
                intersection = intersection,
                timeSeconds = simulation.snapshots.getOrNull(selectedSnapshotIndex)?.timeSeconds ?: 0.0,
                cycleLengthSeconds = timingReport.cycleLengthSeconds,
                planIsValid = timingReport.isValid,
                phaseWindows = timingReport.phaseWindows
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TrafficSimulationCanvas(intersection, simulation.snapshots.getOrNull(selectedSnapshotIndex))
                    Text("Линейный масштаб: 10 м", style = MaterialTheme.typography.labelSmall)
                    Text("Модельное время: ${simulation.snapshots.getOrNull(selectedSnapshotIndex)?.timeSeconds?.toInt() ?: 0} с")
                    Slider(
                        value = selectedSnapshotIndex.toFloat(),
                        onValueChange = { selectedSnapshotIndex = it.toInt().coerceIn(0, max(0, simulation.snapshots.lastIndex)); isPlaying = false },
                        valueRange = 0f..simulation.snapshots.lastIndex.coerceAtLeast(1).toFloat()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { isPlaying = !isPlaying }) { Text(if (isPlaying) "Пауза" else "Воспроизвести") }
                        OutlinedButton(onClick = { selectedSnapshotIndex = 0; isPlaying = false }) { Text("В начало") }
                        Text("Шаг 1 с", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text("Итоги моделирования", style = MaterialTheme.typography.titleLarge)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MetricLine("Поступило", "${simulation.totalArrivals} авт.")
                    MetricLine("Проехало стоп-линию", "${simulation.totalDepartures} авт.")
                    MetricLine("Осталось на подходах и во внешнем заторе", "${simulation.totalQueueAtEnd} авт.")
                    MetricLine("Максимум ТС на подходах и в подпоре", "${simulation.maximumQueueVehicles} авт.")
                    MetricLine("Максимальная длина занятого подхода", "${"%.1f".format(simulation.maximumQueueLengthMeters)} м")
                    MetricLine("Ожидают за границей участка", "${simulation.spillbackVehiclesAtEnd} авт.")
                    MetricLine("Максимальный внешний затор", "${simulation.maximumSpillbackVehicles} авт.")
                    MetricLine("Средняя задержка", "${"%.1f".format(simulation.averageDelaySeconds)} с/авт.")
                    MetricLine("Пропуск за час (по факту)", "${"%.0f".format(simulation.throughputVehiclesPerHour)} авт./ч")
                    MetricLine("Суммарная задержка", "${"%.2f".format(simulation.totalDelayVehicleHours)} авт.-ч")
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Результаты по категориям", style = MaterialTheme.typography.titleMedium)
                    simulation.categoryMetrics.forEach { metric ->
                        MetricLine(metric.vehicleClass?.displayName() ?: "Не распределено",
                            "${metric.departures}/${metric.arrivals} · задержка ${"%.1f".format(metric.averageDelaySeconds)} с")
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Результаты по движениям", style = MaterialTheme.typography.titleMedium)
                    simulation.movementMetrics.forEachIndexed { index, metric ->
                        MetricLine("Движение ${index + 1}",
                            "${metric.departures}/${metric.arrivals} · на подходе ${metric.queueVehiclesAtEnd} авт. · занято ${"%.1f".format(metric.queueLengthMetersAtEnd)} м · внешний затор ${metric.spillbackVehiclesAtEnd}")
                    }
                }
            }
            simulation.diagnostics.forEach { Text("Предупреждение: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text("Движение рассчитывается по IDM с учётом длины кузова, безопасного зазора, временного интервала, ускорения и замедления. Автомобили автоматически выбирают менее загруженную полосу только при совпадении разрешённого манёвра и наличии безопасных интервалов спереди и сзади; перестроение длится 3 секунды и отображается на траектории. Подход имеет конечную вместимость; неразместившийся поток учитывается отдельно. Пока не моделируются перестроения между разными манёврами, пешеходы, блокирование выходных полос и калибровка по натурным наблюдениям.",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SignalPhaseStatusCard(
    intersection: Intersection,
    timeSeconds: Double,
    cycleLengthSeconds: Int,
    planIsValid: Boolean,
    phaseWindows: List<ru.trafficsignalstudio.geometry.SignalPhaseWindow>
) {
    val plan = intersection.signalPlan
    val cycleTime = if (cycleLengthSeconds > 0) timeSeconds % cycleLengthSeconds else 0.0
    val window = phaseWindows.firstOrNull {
        cycleTime >= it.startSeconds && cycleTime < it.allRedEndSeconds
    }
    val phase = window?.let { selected -> plan?.phases?.firstOrNull { it.id == selected.phaseId } }
    val activeWindow = window
    val state = when {
        plan?.enabled != true || !planIsValid -> "План не готов"
        phase == null || activeWindow == null -> "Нет активной фазы"
        cycleTime < activeWindow.greenEndSeconds -> "Зелёный"
        cycleTime < activeWindow.yellowEndSeconds -> "Жёлтый"
        else -> "Общий красный"
    }
    val stateColor = when (state) {
        "Зелёный" -> Color(0xFF43A047)
        "Жёлтый" -> Color(0xFFFFB300)
        "Общий красный" -> Color(0xFFE53935)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Текущее состояние светофора", style = MaterialTheme.typography.titleMedium)
            Text(state, color = stateColor, style = MaterialTheme.typography.titleLarge)
            Text(phase?.name ?: "План не содержит действующей фазы", style = MaterialTheme.typography.bodyMedium)
            if (phase != null) {
                Text(
                    if (state == "Зелёный") "Разрешено движений: ${phase.greenConnectionIds.size}"
                    else "Выпуск транспорта по этой фазе закрыт",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (cycleLengthSeconds > 0) {
                Text("Положение в цикле: ${cycleTime.toInt()} / $cycleLengthSeconds с",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TrafficSimulationCanvas(intersection: Intersection, snapshot: TrafficSimulationSnapshot?) {
    val trajectories = remember(intersection.id, intersection.laneConnections) {
        TrajectoryConflictAnalyzer.buildMovementTrajectories(intersection)
    }
    Canvas(modifier = Modifier.fillMaxWidth().height(280.dp).background(Color(0xFF263238))) {
        val trajectoryById = trajectories.associateBy { it.connectionId }
        val storageLength = snapshot?.approachStorageLengthMeters ?: 120.0
        val approachExtensions = trajectories.mapNotNull { trajectory ->
            val first = trajectory.points.getOrNull(0) ?: return@mapNotNull null
            val second = trajectory.points.getOrNull(1) ?: return@mapNotNull null
            val segmentLength = hypot(second.xMeters - first.xMeters, second.yMeters - first.yMeters).coerceAtLeast(1e-6)
            ru.trafficsignalstudio.model.Point2D(
                first.xMeters + (first.xMeters - second.xMeters) * storageLength / segmentLength,
                first.yMeters + (first.yMeters - second.yMeters) * storageLength / segmentLength
            )
        }
        val vehiclePoints = snapshot?.activeVehicles.orEmpty().mapNotNull { vehicle ->
            trajectoryById[vehicle.connectionId]?.let { target ->
                val targetPoint = pointAtProgress(target.points, vehicle.progress)
                val source = vehicle.laneChangeFromConnectionId?.let(trajectoryById::get)
                if (source == null) targetPoint else {
                    val sourcePoint = pointAtProgress(source.points, vehicle.progress)
                    val blend = vehicle.laneChangeProgress.coerceIn(0.0, 1.0)
                    ru.trafficsignalstudio.model.Point2D(
                        sourcePoint.xMeters + (targetPoint.xMeters - sourcePoint.xMeters) * blend,
                        sourcePoint.yMeters + (targetPoint.yMeters - sourcePoint.yMeters) * blend
                    )
                }
            }
        }
        val allPoints = trajectories.flatMap { it.points } + approachExtensions + vehiclePoints
        if (allPoints.isEmpty()) return@Canvas
        val minX = allPoints.minOf { it.xMeters }; val maxX = allPoints.maxOf { it.xMeters }
        val minY = allPoints.minOf { it.yMeters }; val maxY = allPoints.maxOf { it.yMeters }
        val spanX = (maxX - minX).coerceAtLeast(10.0)
        val spanY = (maxY - minY).coerceAtLeast(10.0)
        val scale = minOf(size.width / spanX.toFloat(), size.height / spanY.toFloat()) * 0.78f
        val origin = Offset(size.width / 2f - ((minX + maxX) / 2.0).toFloat() * scale,
            size.height / 2f + ((minY + maxY) / 2.0).toFloat() * scale)
        fun map(point: ru.trafficsignalstudio.model.Point2D) =
            Offset(origin.x + point.xMeters.toFloat() * scale, origin.y - point.yMeters.toFloat() * scale)
        trajectories.forEach { trajectory ->
            val path = Path()
            trajectory.points.forEachIndexed { index, point ->
                val p = map(point)
                if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, Color(0xFFB0BEC5).copy(alpha = 0.65f), style = Stroke(width = 5f))
            val first = trajectory.points.firstOrNull()
            val second = trajectory.points.getOrNull(1)
            if (first != null && second != null) {
                val segmentLength = hypot(second.xMeters - first.xMeters, second.yMeters - first.yMeters).coerceAtLeast(1e-6)
                val extension = ru.trafficsignalstudio.model.Point2D(
                    first.xMeters + (first.xMeters - second.xMeters) * storageLength / segmentLength,
                    first.yMeters + (first.yMeters - second.yMeters) * storageLength / segmentLength
                )
                drawLine(Color(0xFF78909C).copy(alpha = 0.45f), map(extension), map(first), strokeWidth = 2.5f)
            }
        }
        // The ruler uses the same pixels-per-metre factor as road geometry and vehicle bodies.
        val rulerStart = Offset(14f, size.height - 16f)
        val rulerEnd = Offset(rulerStart.x + 10f * scale, rulerStart.y)
        drawLine(Color.White, rulerStart, rulerEnd, strokeWidth = 4f)
        drawLine(Color(0xFF263238), rulerStart, rulerEnd, strokeWidth = 1.5f)
        drawLine(Color.White, Offset(rulerStart.x, rulerStart.y - 5f), Offset(rulerStart.x, rulerStart.y + 5f), strokeWidth = 2f)
        drawLine(Color.White, Offset(rulerEnd.x, rulerEnd.y - 5f), Offset(rulerEnd.x, rulerEnd.y + 5f), strokeWidth = 2f)
        snapshot?.activeVehicles?.forEach { vehicle ->
            val trajectory = trajectoryById[vehicle.connectionId] ?: return@forEach
            val targetPoint = pointAtProgress(trajectory.points, vehicle.progress)
            val sourceTrajectory = vehicle.laneChangeFromConnectionId?.let(trajectoryById::get)
            val point = if (sourceTrajectory == null) targetPoint else {
                val sourcePoint = pointAtProgress(sourceTrajectory.points, vehicle.progress)
                val blend = vehicle.laneChangeProgress.coerceIn(0.0, 1.0)
                ru.trafficsignalstudio.model.Point2D(
                    sourcePoint.xMeters + (targetPoint.xMeters - sourcePoint.xMeters) * blend,
                    sourcePoint.yMeters + (targetPoint.yMeters - sourcePoint.yMeters) * blend
                )
            }
            val center = map(point)
            val color = vehicle.vehicleClass?.displayColor() ?: Color(0xFF90A4AE)
            val heading = headingAtProgress(trajectory.points, vehicle.progress)
            // Preserve the real model dimensions at the current world-to-screen scale.
            val bodyLength = vehicle.lengthMeters.toFloat() * scale
            val bodyWidth = vehicle.widthMeters.toFloat() * scale
            rotate(heading, center) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(center.x - bodyLength / 2f, center.y - bodyWidth / 2f),
                    size = Size(bodyLength, bodyWidth),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(bodyWidth * 0.25f)
                )
                drawRoundRect(
                    color = Color(0xFF263238),
                    topLeft = Offset(center.x - bodyLength * 0.12f, center.y - bodyWidth * 0.36f),
                    size = Size(bodyLength * 0.22f, bodyWidth * 0.72f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(bodyWidth * 0.12f)
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.9f),
                    topLeft = Offset(center.x - bodyLength / 2f, center.y - bodyWidth / 2f),
                    size = Size(bodyLength, bodyWidth),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(bodyWidth * 0.25f),
                    style = Stroke(width = 0.8f)
                )
            }
        }
    }
}

private fun headingAtProgress(points: List<ru.trafficsignalstudio.model.Point2D>, progress: Double): Float {
    if (points.size < 2) return 0f
    val segmentIndex = if (progress < 0.0) 0 else {
        val lengths = points.zipWithNext().map { (a, b) -> hypot(b.xMeters - a.xMeters, b.yMeters - a.yMeters) }
        val target = progress.coerceIn(0.0, 1.0) * lengths.sum().coerceAtLeast(1e-6)
        var remaining = target
        lengths.indexOfFirst { length ->
            if (remaining <= length) true else { remaining -= length; false }
        }.let { if (it < 0) lengths.lastIndex else it }
    }
    val a = points[segmentIndex]
    val b = points[(segmentIndex + 1).coerceAtMost(points.lastIndex)]
    return Math.toDegrees(kotlin.math.atan2(-(b.yMeters - a.yMeters), b.xMeters - a.xMeters)).toFloat()
}

private fun pointAtProgress(points: List<ru.trafficsignalstudio.model.Point2D>, progress: Double): ru.trafficsignalstudio.model.Point2D {
    if (points.size < 2) return points.firstOrNull() ?: ru.trafficsignalstudio.model.Point2D(0.0, 0.0)
    val lengths = points.zipWithNext().map { (a, b) -> hypot(b.xMeters - a.xMeters, b.yMeters - a.yMeters) }
    val total = lengths.sum().coerceAtLeast(1e-6)
    var distance = progress * total
    if (distance < 0.0) {
        val first = points[0]; val second = points[1]
        val segment = lengths[0].coerceAtLeast(1e-6)
        val ratio = -distance / segment
        return ru.trafficsignalstudio.model.Point2D(
            first.xMeters + (first.xMeters - second.xMeters) * ratio,
            first.yMeters + (first.yMeters - second.yMeters) * ratio
        )
    }
    for (index in lengths.indices) {
        if (distance <= lengths[index]) {
            val ratio = (distance / lengths[index].coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
            val a = points[index]; val b = points[index + 1]
            return ru.trafficsignalstudio.model.Point2D(
                a.xMeters + (b.xMeters - a.xMeters) * ratio,
                a.yMeters + (b.yMeters - a.yMeters) * ratio
            )
        }
        distance -= lengths[index]
    }
    val last = points.last()
    val previous = points[points.lastIndex - 1]
    val lastSegment = lengths.last().coerceAtLeast(1e-6)
    val extensionRatio = distance / lastSegment
    return ru.trafficsignalstudio.model.Point2D(
        last.xMeters + (last.xMeters - previous.xMeters) * extensionRatio,
        last.yMeters + (last.yMeters - previous.yMeters) * extensionRatio
    )
}

private fun VehicleClass.displayName(): String = when (this) {
    VehicleClass.PASSENGER_CAR -> "Легковые"
    VehicleClass.LIGHT_COMMERCIAL -> "Малотоннажные"
    VehicleClass.RIGID_TRUCK -> "Грузовые"
    VehicleClass.ARTICULATED_TRUCK -> "Автопоезда"
    VehicleClass.CITY_BUS -> "Автобусы"
    VehicleClass.ARTICULATED_BUS -> "Сочленённые автобусы"
    VehicleClass.TROLLEYBUS -> "Троллейбусы"
    VehicleClass.ARTICULATED_TROLLEYBUS -> "Сочленённые троллейбусы"
    VehicleClass.MOTORCYCLE -> "Мотоциклы"
    VehicleClass.BICYCLE -> "Велосипеды"
}

private fun VehicleClass.displayColor(): Color = when (this) {
    VehicleClass.PASSENGER_CAR -> Color(0xFF42A5F5)
    VehicleClass.LIGHT_COMMERCIAL -> Color(0xFF26A69A)
    VehicleClass.RIGID_TRUCK, VehicleClass.ARTICULATED_TRUCK -> Color(0xFFFFA726)
    VehicleClass.CITY_BUS, VehicleClass.ARTICULATED_BUS -> Color(0xFFAB47BC)
    VehicleClass.TROLLEYBUS, VehicleClass.ARTICULATED_TROLLEYBUS -> Color(0xFFEC407A)
    VehicleClass.MOTORCYCLE -> Color(0xFFFFEE58)
    VehicleClass.BICYCLE -> Color(0xFF9CCC65)
}
