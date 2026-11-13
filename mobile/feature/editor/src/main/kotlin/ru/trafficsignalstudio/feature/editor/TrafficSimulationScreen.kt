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
    var result by remember(intersection.id) { mutableStateOf<TrafficSimulationResult?>(null) }
    var selectedSnapshotIndex by remember(intersection.id) { mutableStateOf(0) }
    var isPlaying by remember(intersection.id) { mutableStateOf(false) }
    var inputError by remember(intersection.id) { mutableStateOf<String?>(null) }

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
        if (intersection.signalPlan == null || intersection.signalPlan.phases.isEmpty()) {
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
                    result = TrafficSimulationEngine.run(intersection, TrafficSimulationDemand(
                        movements = demands, durationSeconds = durationMinutes * 60
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TrafficSimulationCanvas(intersection, simulation.snapshots.getOrNull(selectedSnapshotIndex))
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
                    MetricLine("Осталось в очередях", "${simulation.totalQueueAtEnd} авт.")
                    MetricLine("Максимальная очередь", "${simulation.maximumQueueVehicles} авт.")
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
                            "${metric.departures}/${metric.arrivals} · очередь ${metric.queueVehiclesAtEnd} · макс. ${metric.maximumQueueVehicles}")
                    }
                }
            }
            simulation.diagnostics.forEach { Text("Предупреждение: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text("Расчётная модель использует дискретный шаг, равномерный поток прибытий и фазовый выпуск с упрощённым интервалом обслуживания. Результаты предварительные: пока нет калибровки по фактическим наблюдениям, полноценного следования за лидером, пешеходов и адаптивного поведения водителей.",
                style = MaterialTheme.typography.labelSmall)
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
        val allPoints = trajectories.flatMap { it.points }
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
        }
        snapshot?.activeVehicles?.forEach { vehicle ->
            val trajectory = trajectories.firstOrNull { it.connectionId == vehicle.connectionId } ?: return@forEach
            val point = pointAtProgress(trajectory.points, vehicle.progress)
            val center = map(point)
            val model = vehicle.vehicleClass?.let { cls ->
                VehicleModelCatalog.defaults.firstOrNull { it.vehicleClass == cls }
            } ?: VehicleModelCatalog.defaultPassengerCar
            val color = vehicle.vehicleClass?.displayColor() ?: Color(0xFF90A4AE)
            val heading = headingAtProgress(trajectory.points, vehicle.progress)
            val bodyLength = (model.lengthMeters.toFloat() * scale).coerceIn(7f, 22f)
            val bodyWidth = (model.widthMeters.toFloat() * scale).coerceIn(4f, 9f)
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
        if (distance <= lengths[index] || index == lengths.lastIndex) {
            val ratio = (distance / lengths[index].coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
            val a = points[index]; val b = points[index + 1]
            return ru.trafficsignalstudio.model.Point2D(
                a.xMeters + (b.xMeters - a.xMeters) * ratio,
                a.yMeters + (b.yMeters - a.yMeters) * ratio
            )
        }
        distance -= lengths[index]
    }
    return points.last()
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
