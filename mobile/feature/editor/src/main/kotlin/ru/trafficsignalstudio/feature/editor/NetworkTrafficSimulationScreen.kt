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
import ru.trafficsignalstudio.geometry.CrashRiskBand
import ru.trafficsignalstudio.geometry.CrashSimulationMode
import ru.trafficsignalstudio.geometry.ExternalVehicleArrival
import ru.trafficsignalstudio.geometry.ManualCrashEvent
import ru.trafficsignalstudio.geometry.MovementTrafficDemand
import ru.trafficsignalstudio.geometry.NetworkCoordinationCalculator
import ru.trafficsignalstudio.geometry.NetworkLinkTrafficModel
import ru.trafficsignalstudio.geometry.NetworkTrafficNetworkSolver
import ru.trafficsignalstudio.geometry.SignalPlanTimingAnalyzer
import ru.trafficsignalstudio.geometry.TrafficSimulationDemand
import ru.trafficsignalstudio.geometry.TrafficSimulationEngine
import ru.trafficsignalstudio.geometry.TrafficSimulationResult
import ru.trafficsignalstudio.geometry.TrajectoryConflictAnalyzer
import ru.trafficsignalstudio.model.CoordinationDirection
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.NetworkCoordinationSettings
import ru.trafficsignalstudio.model.NetworkTrafficLink
import ru.trafficsignalstudio.model.Point2D
import ru.trafficsignalstudio.model.ProjectDocument
import ru.trafficsignalstudio.model.RoadNetwork
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private data class NetworkChoice(val id: String, val title: String)

private data class NetworkSimulationOutput(
    val nodeResults: Map<String, TrafficSimulationResult>,
    val linkMetrics: List<NetworkLinkTrafficModel.LinkMetrics>,
    val vehicleSchedules: List<NetworkLinkTrafficModel.VehicleSchedule>,
    val propagationIterations: Int,
    val converged: Boolean
)

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
    var manualInputKeys by remember(project.id, network.trafficLinks) {
        mutableStateOf(emptySet<String>())
    }
    var durationMinutes by remember(project.id) { mutableStateOf(10) }
    var results by remember(project.id) { mutableStateOf<Map<String, TrafficSimulationResult>>(emptyMap()) }
    var linkMetrics by remember(project.id) { mutableStateOf<List<NetworkLinkTrafficModel.LinkMetrics>>(emptyList()) }
    var linkVehicleSchedules by remember(project.id) { mutableStateOf<List<NetworkLinkTrafficModel.VehicleSchedule>>(emptyList()) }
    var networkPropagationIterations by remember(project.id) { mutableStateOf(0) }
    var networkPropagationConverged by remember(project.id) { mutableStateOf(true) }
    var isSimulating by remember { mutableStateOf(false) }
    var selectedSnapshotIndex by remember { mutableStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var showCoordinationDialog by remember { mutableStateOf(false) }
    var crashMode by remember(project.id) { mutableStateOf(CrashSimulationMode.REALISTIC) }
    var manualCrashTime by remember(project.id) { mutableStateOf("300") }
    var manualCrashBand by remember(project.id) { mutableStateOf(CrashRiskBand.INTERSECTION_CONFLICT) }
    var manualCrashTarget by remember(project.id) { mutableStateOf(intersections.firstOrNull()?.id.orEmpty()) }
    var manualCrashEvents by remember(project.id) { mutableStateOf(emptyList<ManualCrashEvent>()) }
    val movementLabelsByIntersection = remember(intersections) {
        intersections.associate { node -> node.id to movementDisplayLabels(node) }
    }

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
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Моделирование ДТП", style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        CrashSimulationMode.REALISTIC to "Реализм",
                        CrashSimulationMode.DISABLED to "Без ДТП",
                        CrashSimulationMode.EXPERIMENTAL to "Эксперимент"
                    ).forEach { (mode, title) ->
                        if (crashMode == mode) Button(onClick = { crashMode = mode; results = emptyMap() }, modifier = Modifier.weight(1f)) {
                            Text(title)
                        } else OutlinedButton(onClick = { crashMode = mode; results = emptyMap() }, modifier = Modifier.weight(1f)) {
                            Text(title)
                        }
                    }
                }
                Text(
                    when (crashMode) {
                        CrashSimulationMode.REALISTIC -> "Автоматические сценарии и фактические столкновения."
                        CrashSimulationMode.DISABLED -> "ДТП отключены; алгоритмы предотвращения столкновений остаются активными."
                        CrashSimulationMode.EXPERIMENTAL -> "Автоматические сценарии отключены. Фактический контакт и заданные события учитываются."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                if (crashMode == CrashSimulationMode.EXPERIMENTAL) {
                    NetworkDropdown("Перекрёсток для события", intersections.map { NetworkChoice(it.id, it.name) },
                        manualCrashTarget) { manualCrashTarget = it }
                    OutlinedTextField(
                        value = manualCrashTime,
                        onValueChange = { manualCrashTime = it.filter(Char::isDigit) },
                        label = { Text("Время события от начала, с") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            CrashRiskBand.FOLLOWING_DISTANCE to "Дистанция",
                            CrashRiskBand.INTERSECTION_CONFLICT to "Конфликт",
                            CrashRiskBand.MULTI_VEHICLE to "Групповое"
                        ).forEach { (band, title) ->
                            if (manualCrashBand == band) Button(onClick = { manualCrashBand = band }, modifier = Modifier.weight(1f)) {
                                Text(title)
                            } else OutlinedButton(onClick = { manualCrashBand = band }, modifier = Modifier.weight(1f)) {
                                Text(title)
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val seconds = manualCrashTime.toDoubleOrNull()
                            if (manualCrashTarget.isNotBlank() && seconds != null && seconds in 0.0..(durationMinutes * 60.0)) {
                                manualCrashEvents = manualCrashEvents + ManualCrashEvent(seconds, manualCrashBand, manualCrashTarget)
                                results = emptyMap()
                            } else inputError = "Выберите перекрёсток и укажите время внутри моделирования"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Добавить аварийное событие") }
                    manualCrashEvents.forEachIndexed { eventIndex, event ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val targetName = intersections.firstOrNull { it.id == event.targetIntersectionId }?.name ?: "Неизвестный узел"
                            Text("$targetName · ${event.timeSeconds.toInt()} с · ${event.band.name}",
                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = {
                                manualCrashEvents = manualCrashEvents.filterIndexed { index, _ -> index != eventIndex }
                                results = emptyMap()
                            }) { Text("Удалить") }
                        }
                    }
                }
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
        CoordinationOffsetDiagram(network, network.coordination)

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
                    node.laneConnections.forEach { movement ->
                        val key = node.id + "|" + movement.id
                        val hasIncomingLink = network.trafficLinks.any {
                            it.enabled && it.toIntersectionId == node.id &&
                                it.toMovementConnectionId == movement.id
                        }
                        val manual = !hasIncomingLink || key in manualInputKeys
                        if (hasIncomingLink) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(movementLabelsByIntersection[node.id]?.get(movement.id)
                                        ?: "Траектория не определена")
                                    Text(if (manual) "Ручной ввод интенсивности" else
                                        "Автоматически: автомобили поступают по связанным дорогам",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(
                                    checked = manual,
                                    onCheckedChange = {
                                        manualInputKeys = if (it) manualInputKeys + key else manualInputKeys - key
                                        results = emptyMap()
                                    }
                                )
                            }
                        }
                        if (manual) {
                            OutlinedTextField(
                                value = intensityValues[key] ?: "600",
                                onValueChange = { raw ->
                                    intensityValues = intensityValues + (key to raw.filter {
                                        it.isDigit() || it == '.' || it == ','
                                    })
                                    results = emptyMap()
                                },
                                label = { Text("Внешний поток, авт./ч") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
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
                            Text("${movementLabelsByIntersection[link.fromIntersectionId]?.get(link.fromMovementConnectionId) ?: "Исходная траектория не найдена"} → ${movementLabelsByIntersection[link.toIntersectionId]?.get(link.toMovementConnectionId) ?: "Конечная траектория не найдена"}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("${link.flowSharePercent.toInt()}% потока · ${link.distanceMeters.toInt()} м · ${link.travelSpeedKmh.toInt()} км/ч · полос: ${link.laneCount} · ${if (link.centerline.isEmpty()) "прямая геометрия" else "кривая: ${link.centerline.size} точек"} · перестроения: ${if (link.laneChangesEnabled) "вкл." else "выкл."}",
                                style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = {
                            val updatedNetwork = network.copy(trafficLinks = network.trafficLinks.filterNot { it.id == link.id })
                            val settings = updatedNetwork.coordination
                            val coordinatedNetwork = if (settings.enabled) updatedNetwork.copy(
                                coordination = settings.copy(offsetsSeconds =
                                    NetworkCoordinationCalculator.calculateOffsets(updatedNetwork, settings))
                            ) else updatedNetwork
                            onProjectChange(project.copy(updatedAtEpochMs = System.currentTimeMillis(),
                                network = coordinatedNetwork))
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
                val base = try {
                    intersections.associate { node ->
                        node.id to node.laneConnections.map { movement ->
                            val key = node.id + "|" + movement.id
                            val hasIncomingLink = network.trafficLinks.any {
                                it.enabled && it.toIntersectionId == node.id &&
                                    it.toMovementConnectionId == movement.id
                            }
                            val manual = !hasIncomingLink || key in manualInputKeys
                            val value = if (!manual) 0.0 else
                                intensityValues[key]?.replace(',', '.')?.toDoubleOrNull()
                                    ?: throw IllegalArgumentException("Проверьте интенсивность: ${node.name}")
                            require(value.isFinite() && value >= 0.0) { "Интенсивность не может быть отрицательной" }
                            MovementTrafficDemand(movement.id, value)
                        }
                    }
                } catch (error: Exception) {
                    inputError = error.message ?: "Проверьте значения интенсивности"
                    return@Button
                }
                inputError = null
                isSimulating = true
                isPlaying = false
                scope.launch {
                    try {
                        val computed = withContext(Dispatchers.Default) {
                            val coordination = network.coordination
                            fun runNode(
                                node: Intersection,
                                demands: List<MovementTrafficDemand>,
                                externalArrivals: List<ExternalVehicleArrival> = emptyList()
                            ): TrafficSimulationResult {
                                val offset = if (coordination.enabled) {
                                    coordination.offsetsSeconds[node.id]?.toDouble() ?: 0.0
                                } else 0.0
                                return TrafficSimulationEngine.run(node, TrafficSimulationDemand(
                                    movements = demands,
                                    externalArrivals = externalArrivals,
                                    durationSeconds = durationMinutes * 60,
                                    signalOffsetSeconds = offset,
                                    signalCycleLengthSeconds = if (coordination.enabled) coordination.cycleLengthSeconds else null,
                                    crashMode = crashMode,
                                    manualCrashEvents = if (crashMode == CrashSimulationMode.EXPERIMENTAL) manualCrashEvents else emptyList()
                                ))
                            }
                            val networkRun = NetworkTrafficNetworkSolver.run(
                                intersectionIds = intersections.map { it.id },
                                links = network.trafficLinks,
                                durationSeconds = durationMinutes * 60,
                                simulateIntersection = { nodeId, incoming ->
                                    val node = intersections.first { it.id == nodeId }
                                    runNode(node, base[nodeId].orEmpty(), incoming)
                                },
                                departuresOf = { it.departureEvents }
                            )
                            val nodeResults = if (networkRun.converged) networkRun.nodeResults
                                else networkRun.nodeResults.mapValues { (_, result) ->
                                    result.copy(diagnostics = result.diagnostics +
                                        "Сетевая передача достигла предела ${networkRun.propagationIterations} итераций до полного установления потоков в замкнутом контуре.")
                                }
                            NetworkSimulationOutput(
                                nodeResults = nodeResults,
                                linkMetrics = networkRun.linkMetrics,
                                vehicleSchedules = networkRun.vehicleSchedules,
                                propagationIterations = networkRun.propagationIterations,
                                converged = networkRun.converged
                            )
                        }
                        results = computed.nodeResults
                        linkMetrics = computed.linkMetrics
                        linkVehicleSchedules = computed.vehicleSchedules
                        networkPropagationIterations = computed.propagationIterations
                        networkPropagationConverged = computed.converged
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
            val networkModelTimeSeconds = results.values.firstOrNull()?.snapshots
                ?.getOrNull(selectedSnapshotIndex)?.timeSeconds ?: 0.0
            val activeLinkVehicles = NetworkLinkTrafficModel.vehiclesAt(
                linkVehicleSchedules, networkModelTimeSeconds
            )
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
            Text(
                if (networkPropagationConverged) "Передача между узлами установилась за $networkPropagationIterations итераций. Замкнутые маршруты поддерживаются."
                else "Передача рассчитана за $networkPropagationIterations итераций; проверьте диагностику узлов: предел итераций достигнут.",
                style = MaterialTheme.typography.labelSmall
            )
            Text("Автомобили проходят конечные межузловые участки с учётом числа полос, безопасного интервала и очереди на въезде. Потоки распределяются по связям без двойного учёта.",
                style = MaterialTheme.typography.labelSmall)
            if (linkMetrics.isNotEmpty()) {
                Text("Межузловые участки", style = MaterialTheme.typography.titleMedium)
                linkMetrics.forEach { metric ->
                    val link = network.trafficLinks.firstOrNull { it.id == metric.linkId } ?: return@forEach
                    val fromName = intersections.firstOrNull { it.id == link.fromIntersectionId }?.name ?: link.fromIntersectionId
                    val toName = intersections.firstOrNull { it.id == link.toIntersectionId }?.name ?: link.toIntersectionId
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("$fromName → $toName", style = MaterialTheme.typography.titleSmall)
                            Text("Полос: $link.laneCount · хранение очереди: ${link.queueStorageLengthMeters.toInt()} м · въехало: $metric.vehiclesEntered · ожидает: $metric.vehiclesWaitingAtEnd · прибыло: $metric.vehiclesArrivedWithinHorizon")
                            Text("Макс. очередь на въезде: $metric.maximumEntryQueue авт. · средняя задержка: %.1f с · макс.: %.1f с".format(
                                metric.averageEntryDelaySeconds, metric.maximumEntryDelaySeconds))
                            Text("Макс. длина очереди: %.1f м · вышло за вместимость участка: %d авт.".format(
                                metric.maximumEntryQueueLengthMeters, metric.spillbackVehicles))
                            val vehiclesOnLink = activeLinkVehicles.filter { it.linkId == link.id }
                            Text("На участке в момент %.0f с: %d авт.".format(networkModelTimeSeconds, vehiclesOnLink.size))
                            NetworkLinkTrafficDiagram(link, vehiclesOnLink)
                        }
                    }
                }
            }
        }
    }

    if (showLinkDialog) {
        NetworkLinkDialog(
            intersections = intersections,
            movementLabelsByIntersection = movementLabelsByIntersection,
            existingLinks = network.trafficLinks,
            onDismiss = { showLinkDialog = false },
            onAdd = { link ->
                val updatedNetwork = network.copy(trafficLinks = network.trafficLinks + link)
                val settings = updatedNetwork.coordination
                val coordinatedNetwork = if (settings.enabled) updatedNetwork.copy(
                    coordination = settings.copy(offsetsSeconds =
                        NetworkCoordinationCalculator.calculateOffsets(updatedNetwork, settings))
                ) else updatedNetwork
                onProjectChange(project.copy(updatedAtEpochMs = System.currentTimeMillis(),
                    network = coordinatedNetwork))
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
    movementLabelsByIntersection: Map<String, Map<String, String>>,
    existingLinks: List<NetworkTrafficLink>,
    onDismiss: () -> Unit,
    onAdd: (NetworkTrafficLink) -> Unit
) {
    var fromNode by remember { mutableStateOf(intersections.firstOrNull()?.id.orEmpty()) }
    var toNode by remember { mutableStateOf(intersections.getOrNull(1)?.id.orEmpty()) }
    var fromMovement by remember { mutableStateOf(intersections.firstOrNull()?.laneConnections?.firstOrNull()?.id.orEmpty()) }
    var toMovement by remember { mutableStateOf(intersections.getOrNull(1)?.laneConnections?.firstOrNull()?.id.orEmpty()) }
    var share by remember { mutableStateOf("100") }
    var distance by remember { mutableStateOf("500") }
    var travelSpeed by remember { mutableStateOf("30") }
    var laneCount by remember { mutableStateOf("1") }
    var queueStorage by remember { mutableStateOf("120") }
    var laneWidth by remember { mutableStateOf("3.5") }
    var centerlineText by remember { mutableStateOf("") }
    var laneChangesEnabled by remember { mutableStateOf(true) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val source = intersections.firstOrNull { it.id == fromNode }
    val target = intersections.firstOrNull { it.id == toNode }
    val alreadyAllocatedPercent = existingLinks
        .filter { it.enabled && it.fromIntersectionId == fromNode && it.fromMovementConnectionId == fromMovement }
        .sumOf { it.flowSharePercent }
    val availableSharePercent = (100.0 - alreadyAllocatedPercent).coerceAtLeast(0.0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Связь транспортных потоков") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NetworkDropdown("От перекрёстка", intersections.map { NetworkChoice(it.id, it.name) }, fromNode) {
                    fromNode = it
                    if (toNode == it) {
                        toNode = intersections.firstOrNull { node -> node.id != it }?.id.orEmpty()
                        toMovement = intersections.firstOrNull { node -> node.id == toNode }?.laneConnections?.firstOrNull()?.id.orEmpty()
                    }
                    fromMovement = intersections.firstOrNull { node -> node.id == it }?.laneConnections?.firstOrNull()?.id.orEmpty()
                    validationError = null
                }
                NetworkDropdown("К перекрёстку", intersections.filter { it.id != fromNode }.map { NetworkChoice(it.id, it.name) }, toNode) {
                    toNode = it
                    toMovement = intersections.firstOrNull { node -> node.id == it }?.laneConnections?.firstOrNull()?.id.orEmpty()
                }
                NetworkDropdown("Выходное движение", source?.laneConnections.orEmpty().mapIndexed { i, m ->
                    NetworkChoice(m.id, movementLabelsByIntersection[fromNode]?.get(m.id) ?: "Траектория не определена")
                }, fromMovement) { fromMovement = it; validationError = null }
                NetworkDropdown("Входное движение", target?.laneConnections.orEmpty().mapIndexed { i, m ->
                    NetworkChoice(m.id, movementLabelsByIntersection[toNode]?.get(m.id) ?: "Траектория не определена")
                }, toMovement) { toMovement = it }
                Text("Уже распределено: ${"%.1f".format(alreadyAllocatedPercent)}% · доступно: ${"%.1f".format(availableSharePercent)}%",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = share, onValueChange = {
                    share = it.filter { c -> c.isDigit() || c == '.' || c == ',' }
                    validationError = null
                }, label = { Text("Доля передаваемого потока, %") }, singleLine = true)
                OutlinedTextField(value = distance, onValueChange = { distance = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    label = { Text("Расстояние между узлами, м") }, singleLine = true)
                OutlinedTextField(value = travelSpeed, onValueChange = { travelSpeed = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    label = { Text("Расчётная скорость на участке, км/ч") }, singleLine = true)
                OutlinedTextField(value = laneCount, onValueChange = { laneCount = it.filter(Char::isDigit) },
                    label = { Text("Количество полос на участке (1–8)") }, singleLine = true)
                OutlinedTextField(value = queueStorage, onValueChange = { queueStorage = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    label = { Text("Длина хранения очереди на участке, м") }, singleLine = true)
                OutlinedTextField(value = laneWidth, onValueChange = { laneWidth = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    label = { Text("Ширина полосы, м") }, singleLine = true)
                OutlinedTextField(
                    value = centerlineText,
                    onValueChange = { centerlineText = it },
                    label = { Text("Геометрия: x,y; x,y; x,y (метры)") },
                    supportingText = { Text("Оставьте пустым для прямой дороги. Не менее двух точек для кривой.") },
                    minLines = 2
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Разрешить динамические перестроения")
                    Switch(checked = laneChangesEnabled, onCheckedChange = { laneChangesEnabled = it })
                }
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val shareValue = share.replace(',', '.').toDoubleOrNull()
                val distanceValue = distance.replace(',', '.').toDoubleOrNull()
                val speedValue = travelSpeed.replace(',', '.').toDoubleOrNull()
                val laneValue = laneCount.toIntOrNull()
                val queueStorageValue = queueStorage.replace(',', '.').toDoubleOrNull()
                val laneWidthValue = laneWidth.replace(',', '.').toDoubleOrNull()
                val centerlinePoints = runCatching {
                    if (centerlineText.isBlank()) emptyList() else centerlineText.split(';').map { token ->
                        val coordinates = token.trim().split(',')
                        require(coordinates.size == 2)
                        Point2D(
                            coordinates[0].trim().replace(',', '.').toDouble(),
                            coordinates[1].trim().replace(',', '.').toDouble()
                        )
                    }
                }.getOrNull()
                if (source != null && target != null && source.id != target.id &&
                    fromMovement.isNotBlank() && toMovement.isNotBlank() &&
                    shareValue != null && shareValue > 0.0 && shareValue <= availableSharePercent + 1e-6 &&
                    distanceValue != null && distanceValue in 1.0..100000.0 &&
                    speedValue != null && speedValue in 5.0..120.0 && laneValue != null && laneValue in 1..8 &&
                    queueStorageValue != null && queueStorageValue in 1.0..5000.0 &&
                    laneWidthValue != null && laneWidthValue in 2.5..5.0 &&
                    centerlinePoints != null && (centerlinePoints.isEmpty() || centerlinePoints.size >= 2) &&
                    centerlinePoints.all { it.xMeters.isFinite() && it.yMeters.isFinite() }) {
                    onAdd(NetworkTrafficLink(
                        fromIntersectionId = fromNode, fromMovementConnectionId = fromMovement,
                        toIntersectionId = toNode, toMovementConnectionId = toMovement,
                        flowSharePercent = shareValue, distanceMeters = distanceValue,
                        travelSpeedKmh = speedValue, laneCount = laneValue,
                        queueStorageLengthMeters = queueStorageValue,
                        laneWidthMeters = laneWidthValue,
                        centerline = centerlinePoints,
                        laneChangesEnabled = laneChangesEnabled
                    ))
                } else {
                    validationError = if (shareValue != null && shareValue > availableSharePercent + 1e-6)
                        "Для этого выходного движения осталось распределить не более ${"%.1f".format(availableSharePercent)}% потока."
                    else "Проверьте перекрёстки, движения, долю (больше 0%) и расстояние."
                }
            }) { Text("Добавить связь") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun NetworkLinkTrafficDiagram(
    link: NetworkTrafficLink,
    vehicles: List<NetworkLinkTrafficModel.VehicleSnapshot>
) {
    Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
        val centerline = link.centerline.ifEmpty {
            listOf(Point2D(0.0, 0.0), Point2D(link.distanceMeters, 0.0))
        }
        val laneMargin = link.laneCount * link.laneWidthMeters / 2.0 + 4.0
        val minX = centerline.minOf { it.xMeters } - laneMargin
        val maxX = centerline.maxOf { it.xMeters } + laneMargin
        val minY = centerline.minOf { it.yMeters } - laneMargin
        val maxY = centerline.maxOf { it.yMeters } + laneMargin
        val worldWidth = (maxX - minX).coerceAtLeast(1.0)
        val worldHeight = (maxY - minY).coerceAtLeast(1.0)
        val scale = min(size.width / worldWidth.toFloat(), size.height / worldHeight.toFloat())
        val offsetX = (size.width - worldWidth.toFloat() * scale) / 2f
        val offsetY = (size.height - worldHeight.toFloat() * scale) / 2f
        fun mapPoint(point: Point2D): Offset = Offset(
            offsetX + ((point.xMeters - minX).toFloat() * scale),
            size.height - offsetY - ((point.yMeters - minY).toFloat() * scale)
        )
        val roadPath = Path().apply {
            val first = mapPoint(centerline.first())
            moveTo(first.x, first.y)
            centerline.drop(1).forEach { point ->
                val mapped = mapPoint(point)
                lineTo(mapped.x, mapped.y)
            }
        }
        drawPath(
            path = roadPath,
            color = Color(0xFF42464D),
            style = Stroke(width = (link.laneCount * 13f).coerceAtLeast(16f))
        )
        drawPath(path = roadPath, color = Color.White.copy(alpha = 0.55f),
            style = Stroke(width = 1.2f))
        vehicles.forEach { vehicle ->
            val center = mapPoint(vehicle.worldPoint)
            val color = when (vehicle.vehicleClass) {
                ru.trafficsignalstudio.model.VehicleClass.CITY_BUS,
                ru.trafficsignalstudio.model.VehicleClass.ARTICULATED_BUS,
                ru.trafficsignalstudio.model.VehicleClass.TROLLEYBUS,
                ru.trafficsignalstudio.model.VehicleClass.ARTICULATED_TROLLEYBUS -> Color(0xFF35B7A5)
                ru.trafficsignalstudio.model.VehicleClass.RIGID_TRUCK,
                ru.trafficsignalstudio.model.VehicleClass.ARTICULATED_TRUCK -> Color(0xFFE6A23C)
                else -> Color(0xFF90CAF9)
            }
            drawCircle(color = color, radius = 5.5f, center = center)
            val headingEnd = Offset(
                center.x + kotlin.math.cos(vehicle.headingRadians).toFloat() * 9f,
                center.y - kotlin.math.sin(vehicle.headingRadians).toFloat() * 9f
            )
            drawLine(color = Color.White, start = center, end = headingEnd, strokeWidth = 2f)
        }
    }
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
    val previewCycle = cycle.toIntOrNull()?.coerceIn(30, 240) ?: current.cycleLengthSeconds
    val previewSpeed = speed.replace(',', '.').toDoubleOrNull()
        ?.takeIf { it.isFinite() }?.coerceIn(5.0, 120.0) ?: current.progressionSpeedKmh
    val previewSettings = NetworkCoordinationSettings(
        enabled = enabled,
        cycleLengthSeconds = previewCycle,
        progressionSpeedKmh = previewSpeed,
        referenceIntersectionId = reference,
        direction = direction,
        offsetsSeconds = NetworkCoordinationCalculator.calculateOffsets(
            network,
            NetworkCoordinationSettings(
                enabled = enabled, cycleLengthSeconds = previewCycle,
                progressionSpeedKmh = previewSpeed, referenceIntersectionId = reference,
                direction = direction
            )
        )
    )
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
                Text("Предварительный просмотр: шкала показывает два общих цикла. Зелёные участки — разрешающий сигнал, жёлтые — переходный интервал, красные — закрытый интервал. Сдвиг каждой строки относительно опорного узла показывает, когда начинается её цикл.",
                    style = MaterialTheme.typography.bodySmall)
                CoordinationOffsetDiagram(network, previewSettings)
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
private fun CoordinationOffsetDiagram(
    network: RoadNetwork,
    settings: NetworkCoordinationSettings
) {
    val nodes = network.intersections
    val cycle = settings.cycleLengthSeconds.coerceIn(30, 240)
    var previewTime by remember(nodes, settings) { mutableStateOf(0f) }
    val reports = remember(nodes) {
        nodes.associate { it.id to SignalPlanTimingAnalyzer.analyze(it) }
    }
    val rowHeight = 38
    val chartHeight = (nodes.size.coerceAtLeast(1) * rowHeight + 30).dp
    Text("Диаграмма сдвига циклов", style = MaterialTheme.typography.titleSmall)
    Canvas(modifier = Modifier.fillMaxWidth().height(chartHeight).background(Color(0xFF263238))) {
        val labelWidth = 108f
        val rightPad = 10f
        val chartLeft = labelWidth
        val chartWidth = (size.width - labelWidth - rightPad).coerceAtLeast(1f)
        val totalSeconds = cycle * 2f
        val pxPerSecond = chartWidth / totalSeconds
        val top = 22f
        val axisPaint = AndroidPaint().apply {
            color = android.graphics.Color.LTGRAY
            textSize = 10f
            isAntiAlias = true
        }
        val labelPaint = AndroidPaint().apply {
            color = android.graphics.Color.WHITE
            textSize = 11f
            isAntiAlias = true
        }
        for (tick in 0..8) {
            val second = totalSeconds * tick / 8f
            val x = chartLeft + second * pxPerSecond
            drawLine(Color(0xFF455A64), Offset(x, top - 10f), Offset(x, size.height - 5f), 1f)
            drawContext.canvas.nativeCanvas.drawText(second.toInt().toString(), x - 7f, 13f, axisPaint)
        }
        drawLine(Color(0xFF90A4AE), Offset(chartLeft, top - 10f),
            Offset(chartLeft + chartWidth, top - 10f), 1f)
        nodes.forEachIndexed { index, node ->
            val y = top + index * rowHeight
            drawContext.canvas.nativeCanvas.drawText(node.name.take(15), 6f, y + 14f, labelPaint)
            val offset = (settings.offsetsSeconds[node.id] ?: 0).mod(cycle)
            drawContext.canvas.nativeCanvas.drawText("+${offset}с", 6f, y + 27f, axisPaint)
            drawRect(Color(0xFF37474F), Offset(chartLeft, y),
                androidx.compose.ui.geometry.Size(chartWidth, 24f))
            val report = reports[node.id]
            val originalCycle = report?.cycleLengthSeconds ?: 0
            if (report != null && report.isValid && originalCycle > 0) {
                val scale = cycle.toFloat() / originalCycle.toFloat()
                for (repeat in -1..2) {
                    report.phaseWindows.forEach { window ->
                        val phaseStart = offset + repeat * cycle + window.startSeconds * scale
                        val greenEnd = offset + repeat * cycle + window.flashingGreenEndSeconds * scale
                        val yellowEnd = offset + repeat * cycle + window.yellowEndSeconds * scale
                        val redEnd = offset + repeat * cycle + window.allRedEndSeconds * scale
                        fun segment(start: Float, end: Float, color: Color) {
                            val clippedStart = start.coerceAtLeast(0f)
                            val clippedEnd = end.coerceAtMost(totalSeconds)
                            if (clippedEnd > clippedStart) {
                                drawRect(color,
                                    Offset(chartLeft + clippedStart * pxPerSecond, y + 3f),
                                    androidx.compose.ui.geometry.Size((clippedEnd - clippedStart) * pxPerSecond, 18f))
                            }
                        }
                        segment(phaseStart, greenEnd, Color(0xFF43A047))
                        segment(greenEnd, yellowEnd, Color(0xFFFFC107))
                        segment(yellowEnd, redEnd, Color(0xFFE53935))
                    }
                }
            } else {
                drawContext.canvas.nativeCanvas.drawText("нет корректного плана", chartLeft + 6f, y + 16f, axisPaint)
            }
        }
        val cursorX = chartLeft + previewTime.coerceIn(0f, totalSeconds) * pxPerSecond
        drawLine(Color.White, Offset(cursorX, top - 10f), Offset(cursorX, size.height - 4f), 2f)
    }
    Text("Модельное время: ${previewTime.toInt()} с · курсор можно перемещать по шкале",
        style = MaterialTheme.typography.bodySmall)
    Slider(
        value = previewTime,
        onValueChange = { previewTime = it },
        valueRange = 0f..(cycle * 2f),
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("■ Зелёный", color = Color(0xFF43A047), style = MaterialTheme.typography.labelSmall)
        Text("■ Жёлтый", color = Color(0xFFFFC107), style = MaterialTheme.typography.labelSmall)
        Text("■ Красный", color = Color(0xFFE53935), style = MaterialTheme.typography.labelSmall)
    }
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
