package ru.trafficsignalstudio.feature.editor

import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.trafficsignalstudio.geometry.MovementTrafficDemand
import ru.trafficsignalstudio.geometry.NetworkCoordinationCalculator
import ru.trafficsignalstudio.geometry.TrafficSimulationDemand
import ru.trafficsignalstudio.geometry.TrafficSimulationEngine
import ru.trafficsignalstudio.geometry.TrafficSimulationResult
import ru.trafficsignalstudio.geometry.TrajectoryConflictAnalyzer
import ru.trafficsignalstudio.model.CoordinationDirection
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.NetworkCoordinationSettings
import ru.trafficsignalstudio.model.NetworkTrafficLink
import ru.trafficsignalstudio.model.ProjectDocument
import ru.trafficsignalstudio.model.RoadNetwork
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private data class NetworkChoice(val id: String, val title: String)

@Composable
fun NetworkTrafficSimulationScreen(
    project: ProjectDocument,
    onProjectChange: (ProjectDocument) -> Unit,
    onBack: () -> Unit
) {
    val network = project.network
    val intersections = network.intersections
    val scope = rememberCoroutineScope()
    var intensityValues by remember(project.id) {
        mutableStateOf(intersections.flatMap { node ->
            node.laneConnections.map { node.id + "|" + it.id to "600" }
        }.toMap())
    }
    var durationMinutes by remember(project.id) { mutableStateOf(10) }
    var results by remember(project.id) { mutableStateOf<Map<String, TrafficSimulationResult>>(emptyMap()) }
    var isSimulating by remember { mutableStateOf(false) }
    var selectedSnapshotIndex by remember { mutableStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var showCoordinationDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isPlaying, results) {
        val lastIndex = results.values.firstOrNull()?.snapshots?.lastIndex ?: 0
        if (isPlaying && lastIndex > 0) {
            while (selectedSnapshotIndex < lastIndex) {
                delay(250)
                selectedSnapshotIndex += 1
            }
            isPlaying = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Микросимуляция сети", style = MaterialTheme.typography.titleLarge)
                Text("${intersections.size} перекрёстков · единое модельное время",
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onBack) { Text("Назад") }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showCoordinationDialog = true }, modifier = Modifier.weight(1f)) {
                Text("Координация")
            }
            OutlinedButton(onClick = { showLinkDialog = true }, modifier = Modifier.weight(1f),
                enabled = intersections.size > 1 && intersections.any { it.laneConnections.isNotEmpty() }) {
                Text("Связать потоки")
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Параметры координации", style = MaterialTheme.typography.titleMedium)
                val coordination = network.coordination
                Text(if (coordination.enabled) {
                    "Включена · цикл ${coordination.cycleLengthSeconds} с · скорость ${coordination.progressionSpeedKmh.toInt()} км/ч"
                } else "Выключена · сигнальные планы работают независимо")
                Text("Смещения: " + intersections.joinToString { node ->
                    "${node.name}: ${coordination.offsetsSeconds[node.id] ?: 0} с"
                }, style = MaterialTheme.typography.bodySmall)
                Text("Связей потоков: ${network.trafficLinks.count { it.enabled }}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("Единая схема сети", style = MaterialTheme.typography.titleLarge)
        if (results.isEmpty()) {
            NetworkOverviewCanvas(project, emptyMap(), 0)
        } else {
            NetworkOverviewCanvas(project, results, selectedSnapshotIndex)
            val snapshotCount = results.values.minOfOrNull { it.snapshots.size } ?: 1
            Text("Модельное время: ${results.values.first().snapshots.getOrNull(selectedSnapshotIndex)?.timeSeconds?.toInt() ?: 0} с")
            Slider(
                value = selectedSnapshotIndex.toFloat(),
                onValueChange = { selectedSnapshotIndex = it.toInt().coerceIn(0, max(0, snapshotCount - 1)); isPlaying = false },
                valueRange = 0f..(snapshotCount - 1).coerceAtLeast(1).toFloat()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { isPlaying = !isPlaying }) { Text(if (isPlaying) "Пауза" else "Воспроизвести") }
                OutlinedButton(onClick = { selectedSnapshotIndex = 0; isPlaying = false }) { Text("В начало") }
            }
        }
        Text("Интенсивность внешнего потока на входе каждого движения, авт./ч",
            style = MaterialTheme.typography.titleMedium)
        intersections.forEachIndexed { index, node ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${index + 1}. ${node.name}", style = MaterialTheme.typography.titleMedium)
                    if (node.laneConnections.isEmpty()) {
                        Text("Нет настроенных движений. Добавьте полосы и соединения в редакторе.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    node.laneConnections.forEachIndexed { movementIndex, movement ->
                        val key = node.id + "|" + movement.id
                        OutlinedTextField(
                            value = intensityValues[key] ?: "600",
                            onValueChange = { raw ->
                                intensityValues = intensityValues + (key to raw.filter { it.isDigit() || it == '.' || it == ',' })
                                results = emptyMap()
                            },
                            label = { Text("Движение ${movementIndex + 1}, авт./ч") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }
        }

        if (network.trafficLinks.isNotEmpty()) {
            Text("Связи между перекрёстками", style = MaterialTheme.typography.titleMedium)
            network.trafficLinks.forEach { link ->
                val from = intersections.firstOrNull { it.id == link.fromIntersectionId }
                val to = intersections.firstOrNull { it.id == link.toIntersectionId }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${from?.name ?: "Удалённый узел"} → ${to?.name ?: "Удалённый узел"}")
                            Text("${link.flowSharePercent.toInt()}% потока · ${link.distanceMeters.toInt()} м",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            onProjectChange(project.copy(updatedAtEpochMs = System.currentTimeMillis(),
                                network = network.copy(trafficLinks = network.trafficLinks.filterNot { it.id == link.id })))
                            results = emptyMap()
                        }) { Text("Удалить") }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 10, 15, 30).forEach { minutes ->
                if (durationMinutes == minutes) Button(onClick = { durationMinutes = minutes }) { Text("$minutes мин") }
                else OutlinedButton(onClick = { durationMinutes = minutes }) { Text("$minutes мин") }
            }
        }
        Button(
            onClick = {
                if (isSimulating) return@Button
                val base = intersections.associate { node ->
                    node.id to node.laneConnections.map { movement ->
                        val value = intensityValues[node.id + "|" + movement.id]?.replace(',', '.')?.toDoubleOrNull()
                            ?: throw IllegalArgumentException("Проверьте интенсивность: ${node.name}")
                        require(value.isFinite() && value >= 0.0) { "Интенсивность не может быть отрицательной" }
                        MovementTrafficDemand(movement.id, value)
                    }
                }
                inputError = null
                isSimulating = true
                isPlaying = false
                scope.launch {
                    try {
                        val computed = withContext(Dispatchers.Default) {
                            val coordination = network.coordination
                            fun runNode(node: Intersection, demands: List<MovementTrafficDemand>): TrafficSimulationResult {
                                val offset = if (coordination.enabled) {
                                    coordination.offsetsSeconds[node.id]?.toDouble() ?: 0.0
                                } else 0.0
                                return TrafficSimulationEngine.run(node, TrafficSimulationDemand(
                                    movements = demands,
                                    durationSeconds = durationMinutes * 60,
                                    signalOffsetSeconds = offset
                                ))
                            }
                            val baseline = intersections.associate { node ->
                                node.id to runNode(node, base[node.id].orEmpty())
                            }
                            val incoming = mutableMapOf<Pair<String, String>, Double>()
                            network.trafficLinks.filter { it.enabled }.forEach { link ->
                                val source = baseline[link.fromIntersectionId] ?: return@forEach
                                val metric = source.movementMetrics.firstOrNull { it.connectionId == link.fromMovementConnectionId }
                                    ?: return@forEach
                                val rate = metric.departures * 3600.0 / source.durationSeconds.coerceAtLeast(1)
                                val key = link.toIntersectionId to link.toMovementConnectionId
                                incoming[key] = (incoming[key] ?: 0.0) + rate * link.flowSharePercent / 100.0
                            }
                            intersections.associate { node ->
                                val adjusted = base[node.id].orEmpty().map { demand ->
                                    demand.copy(totalVehiclesPerHour = demand.totalVehiclesPerHour +
                                        (incoming[node.id to demand.connectionId] ?: 0.0))
                                }
                                node.id to runNode(node, adjusted)
                            }
                        }
                        results = computed
                        selectedSnapshotIndex = 0
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        inputError = error.message ?: "Не удалось выполнить сетевое моделирование"
                    } finally {
                        isSimulating = false
                    }
                }
            },
            enabled = !isSimulating && intersections.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSimulating) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                Text("  Расчёт всей сети…")
            } else Text("Запустить моделирование сети")
        }
        inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (results.isNotEmpty()) {
            Text("Итоги по узлам", style = MaterialTheme.typography.titleLarge)
            intersections.forEach { node ->
                val result = results[node.id] ?: return@forEach
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(node.name, style = MaterialTheme.typography.titleMedium)
                        Text("Поступило: ${result.totalArrivals} · прошло: ${result.totalDepartures} · очередь: ${result.totalQueueAtEnd}")
                        Text("Средняя задержка: ${"%.1f".format(result.averageDelaySeconds)} с/авт. · ДТП: ${result.crashIncidents.size}")
                    }
                }
            }
            Text("Связанные потоки рассчитываются по фактическому выпуску исходного движения и доле, заданной в связи. В этой версии передаётся средняя интенсивность на следующий узел; физический перенос конкретного автомобиля по межузловому участку с накоплением в пути будет следующим этапом.",
                style = MaterialTheme.typography.labelSmall)
        }
    }

    if (showLinkDialog) {
        NetworkLinkDialog(
            intersections = intersections,
            onDismiss = { showLinkDialog = false },
            onAdd = { link ->
                onProjectChange(project.copy(updatedAtEpochMs = System.currentTimeMillis(),
                    network = network.copy(trafficLinks = network.trafficLinks + link)))
                results = emptyMap()
                showLinkDialog = false
            }
        )
    }
    if (showCoordinationDialog) {
        NetworkCoordinationDialog(
            network = network,
            onDismiss = { showCoordinationDialog = false },
            onSave = { settings ->
                val offsets = NetworkCoordinationCalculator.calculateOffsets(network, settings)
                onProjectChange(project.copy(updatedAtEpochMs = System.currentTimeMillis(),
                    network = network.copy(coordination = settings.copy(offsetsSeconds = offsets))))
                results = emptyMap()
                showCoordinationDialog = false
            }
        )
    }
}

@Composable
private fun NetworkLinkDialog(
    intersections: List<Intersection>,
    onDismiss: () -> Unit,
    onAdd: (NetworkTrafficLink) -> Unit
) {
    var fromNode by remember { mutableStateOf(intersections.firstOrNull()?.id.orEmpty()) }
    var toNode by remember { mutableStateOf(intersections.getOrNull(1)?.id.orEmpty()) }
    var fromMovement by remember { mutableStateOf(intersections.firstOrNull()?.laneConnections?.firstOrNull()?.id.orEmpty()) }
    var toMovement by remember { mutableStateOf(intersections.getOrNull(1)?.laneConnections?.firstOrNull()?.id.orEmpty()) }
    var share by remember { mutableStateOf("100") }
    var distance by remember { mutableStateOf("500") }
    val source = intersections.firstOrNull { it.id == fromNode }
    val target = intersections.firstOrNull { it.id == toNode }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Связь транспортных потоков") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NetworkDropdown("От перекрёстка", intersections.map { NetworkChoice(it.id, it.name) }, fromNode) {
                    fromNode = it
                    fromMovement = intersections.firstOrNull { node -> node.id == it }?.laneConnections?.firstOrNull()?.id.orEmpty()
                }
                NetworkDropdown("К перекрёстку", intersections.filter { it.id != fromNode }.map { NetworkChoice(it.id, it.name) }, toNode) {
                    toNode = it
                    toMovement = intersections.firstOrNull { node -> node.id == it }?.laneConnections?.firstOrNull()?.id.orEmpty()
                }
                NetworkDropdown("Выходное движение", source?.laneConnections.orEmpty().mapIndexed { i, m ->
                    NetworkChoice(m.id, "Движение ${i + 1} · ${m.id.take(6)}")
                }, fromMovement) { fromMovement = it }
                NetworkDropdown("Входное движение", target?.laneConnections.orEmpty().mapIndexed { i, m ->
                    NetworkChoice(m.id, "Движение ${i + 1} · ${m.id.take(6)}")
                }, toMovement) { toMovement = it }
                OutlinedTextField(value = share, onValueChange = { share = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    label = { Text("Доля передаваемого потока, %") }, singleLine = true)
                OutlinedTextField(value = distance, onValueChange = { distance = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    label = { Text("Расстояние между узлами, м") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val shareValue = share.replace(',', '.').toDoubleOrNull()
                val distanceValue = distance.replace(',', '.').toDoubleOrNull()
                if (source != null && target != null && fromMovement.isNotBlank() && toMovement.isNotBlank() &&
                    shareValue != null && shareValue in 0.0..100.0 && distanceValue != null && distanceValue in 1.0..100000.0) {
                    onAdd(NetworkTrafficLink(
                        fromIntersectionId = fromNode, fromMovementConnectionId = fromMovement,
                        toIntersectionId = toNode, toMovementConnectionId = toMovement,
                        flowSharePercent = shareValue, distanceMeters = distanceValue
                    ))
                }
            }) { Text("Добавить связь") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun NetworkCoordinationDialog(
    network: RoadNetwork,
    onDismiss: () -> Unit,
    onSave: (NetworkCoordinationSettings) -> Unit
) {
    val current = network.coordination
    var enabled by remember { mutableStateOf(current.enabled) }
    var cycle by remember { mutableStateOf(current.cycleLengthSeconds.toString()) }
    var speed by remember { mutableStateOf(current.progressionSpeedKmh.toString()) }
    var reference by remember {
        mutableStateOf(current.referenceIntersectionId?.takeIf { id -> network.intersections.any { it.id == id } }
            ?: network.intersections.firstOrNull()?.id.orEmpty())
    }
    var direction by remember { mutableStateOf(current.direction) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки координации") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Включить координацию")
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(value = cycle, onValueChange = { cycle = it.filter(Char::isDigit) },
                    label = { Text("Общий цикл, с (30–240)") }, singleLine = true)
                OutlinedTextField(value = speed, onValueChange = { speed = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    label = { Text("Скорость сообщения, км/ч") }, singleLine = true)
                NetworkDropdown("Опорный перекрёсток", network.intersections.map { NetworkChoice(it.id, it.name) },
                    reference) { reference = it }
                Text("Направление координации", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(CoordinationDirection.FORWARD, CoordinationDirection.REVERSE, CoordinationDirection.BIDIRECTIONAL).forEach { option ->
                        val title = when (option) {
                            CoordinationDirection.FORWARD -> "Прямое"
                            CoordinationDirection.REVERSE -> "Обратное"
                            CoordinationDirection.BIDIRECTIONAL -> "Оба"
                        }
                        if (direction == option) Button(onClick = { direction = option }) { Text(title) }
                        else OutlinedButton(onClick = { direction = option }) { Text(title) }
                    }
                }
                Text("Смещения рассчитываются по длине связей и скорости сообщения. Для узлов без маршрута от опорного смещение будет 0 с.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cycleValue = cycle.toIntOrNull()
                val speedValue = speed.replace(',', '.').toDoubleOrNull()
                if (cycleValue != null && cycleValue in 30..240 && speedValue != null &&
                    speedValue.isFinite() && speedValue in 5.0..120.0 && reference.isNotBlank()) {
                    onSave(NetworkCoordinationSettings(
                        enabled = enabled, cycleLengthSeconds = cycleValue,
                        progressionSpeedKmh = speedValue, referenceIntersectionId = reference,
                        direction = direction
                    ))
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun NetworkDropdown(
    label: String,
    options: List<NetworkChoice>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember(label, options, selectedId) { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId }?.title ?: "Не выбрано"
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(),
            enabled = options.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(selected, maxLines = 1)
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(option.title) },
                    onClick = { expanded = false; onSelect(option.id) }
                )
            }
        }
    }
}

@Composable
private fun NetworkOverviewCanvas(
    project: ProjectDocument,
    results: Map<String, TrafficSimulationResult>,
    snapshotIndex: Int
) {
    val nodes = project.network.intersections
    val columns = 2
    val rows = (nodes.size + columns - 1) / columns
    val trajectories = remember(nodes) {
        nodes.associate { node -> node.id to TrajectoryConflictAnalyzer.buildMovementTrajectories(node) }
    }
    Canvas(modifier = Modifier.fillMaxWidth().height((rows.coerceAtLeast(1) * 205).dp)
        .background(Color(0xFF263238))) {
        if (nodes.isEmpty()) return@Canvas
        val tileWidth = size.width / columns
        val tileHeight = size.height / rows.coerceAtLeast(1)
        val centers = nodes.mapIndexed { index, node ->
            val col = index % columns
            val row = index / columns
            node.id to Offset(tileWidth * (col + 0.5f), tileHeight * (row + 0.5f))
        }.toMap()
        project.network.trafficLinks.filter { it.enabled }.forEach { link ->
            val a = centers[link.fromIntersectionId] ?: return@forEach
            val b = centers[link.toIntersectionId] ?: return@forEach
            drawLine(Color(0xFFFFC107), a, b, strokeWidth = 3f)
            drawCircle(Color(0xFFFFC107), radius = 5f, center = b)
        }
        nodes.forEachIndexed { index, node ->
            val col = index % columns
            val row = index / columns
            val left = col * tileWidth
            val top = row * tileHeight
            val center = centers.getValue(node.id)
            drawRect(Color(0xFF37474F), topLeft = Offset(left + 3f, top + 3f),
                size = androidx.compose.ui.geometry.Size(tileWidth - 6f, tileHeight - 6f))
            drawCircle(Color(0xFF90A4AE), radius = 4f, center = center)
            val paths = trajectories[node.id].orEmpty()
            val allPoints = paths.flatMap { it.points }
            val minX = allPoints.minOfOrNull { it.xMeters } ?: -30.0
            val maxX = allPoints.maxOfOrNull { it.xMeters } ?: 30.0
            val minY = allPoints.minOfOrNull { it.yMeters } ?: -30.0
            val maxY = allPoints.maxOfOrNull { it.yMeters } ?: 30.0
            val worldWidth = (maxX - minX).coerceAtLeast(40.0)
            val worldHeight = (maxY - minY).coerceAtLeast(40.0)
            val scale = min((tileWidth * 0.72f / worldWidth).toFloat(),
                (tileHeight * 0.68f / worldHeight).toFloat()).coerceAtLeast(0.05f)
            fun mapPoint(x: Double, y: Double): Offset = Offset(
                center.x + ((x - (minX + maxX) / 2.0) * scale).toFloat(),
                center.y - ((y - (minY + maxY) / 2.0) * scale).toFloat()
            )
            paths.forEach { trajectory ->
                if (trajectory.points.size >= 2) {
                    val path = Path()
                    trajectory.points.forEachIndexed { pointIndex, point ->
                        val mapped = mapPoint(point.xMeters, point.yMeters)
                        if (pointIndex == 0) path.moveTo(mapped.x, mapped.y) else path.lineTo(mapped.x, mapped.y)
                    }
                    drawPath(path, Color(0xFFB0BEC5), style = Stroke(width = 5f))
                }
            }
            val snapshot = results[node.id]?.snapshots?.getOrNull(snapshotIndex)
            snapshot?.activeVehicles?.forEach { vehicle ->
                val trajectory = paths.firstOrNull { it.connectionId == vehicle.connectionId } ?: return@forEach
                val points = trajectory.points
                if (points.size < 2) return@forEach
                val progress = vehicle.progress.coerceIn(0.0, 1.0)
                val scaled = progress * (points.size - 1)
                val segment = scaled.toInt().coerceIn(0, points.lastIndex - 1)
                val fraction = scaled - segment
                val a = points[segment]
                val b = points[segment + 1]
                val p = mapPoint(a.xMeters + (b.xMeters - a.xMeters) * fraction,
                    a.yMeters + (b.yMeters - a.yMeters) * fraction)
                drawCircle(if (vehicle.isCrashed) Color.Red else Color(0xFF29B6F6),
                    radius = 3.5f, center = p)
            }
            drawContext.canvas.nativeCanvas.drawText(
                "${index + 1}. ${node.name.take(22)}",
                left + 10f, top + 20f,
                AndroidPaint().apply { color = android.graphics.Color.WHITE; textSize = 13f; isFakeBoldText = true }
            )
        }
    }
}
