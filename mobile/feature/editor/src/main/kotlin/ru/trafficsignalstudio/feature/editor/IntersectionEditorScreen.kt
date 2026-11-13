package ru.trafficsignalstudio.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import ru.trafficsignalstudio.geometry.IntersectionGeometryEditor
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.MovementKind
import ru.trafficsignalstudio.model.LaneFlowDirection
import ru.trafficsignalstudio.model.LaneConnection
import ru.trafficsignalstudio.model.LaneTravelDirection
import ru.trafficsignalstudio.geometry.TrajectoryConflictAnalyzer
import ru.trafficsignalstudio.geometry.TrajectoryConflictKind
import ru.trafficsignalstudio.geometry.ConflictSignalStatus
import ru.trafficsignalstudio.geometry.MovementTrajectory
import ru.trafficsignalstudio.geometry.VehicleEnvelopeConflictAnalyzer
import ru.trafficsignalstudio.geometry.SignalPlanTimingAnalyzer
import ru.trafficsignalstudio.model.SignalPhase
import ru.trafficsignalstudio.model.SignalPlan
import ru.trafficsignalstudio.model.ProjectDocument
import ru.trafficsignalstudio.model.Point2D
import ru.trafficsignalstudio.model.RoadSegment
import ru.trafficsignalstudio.model.RoadSegmentKind
import ru.trafficsignalstudio.model.RoadJunctionNode
import ru.trafficsignalstudio.model.SafetyIsland
import ru.trafficsignalstudio.model.VehicleModel
import ru.trafficsignalstudio.model.VehicleModelCatalog
import ru.trafficsignalstudio.model.VehicleClass
import kotlin.math.max
import kotlin.math.min

private enum class DrawingTool(val title: String, val kind: RoadSegmentKind) {
    LINE("Линия", RoadSegmentKind.STRAIGHT),
    FREE("Ломаная", RoadSegmentKind.POLYLINE),
    ARC("Дуга", RoadSegmentKind.ARC),
    BRIDGE("Мост", RoadSegmentKind.BRIDGE),
    RAMP("Съезд", RoadSegmentKind.RAMP)
}

@Composable
fun IntersectionEditorScreen(
    project: ProjectDocument,
    intersection: Intersection?,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onProjectChange: (ProjectDocument) -> Unit,
    onSelectIntersection: (String) -> Unit,
    onAddIntersection: () -> Unit
) {
    var showSimulation by remember(intersection?.id) { mutableStateOf(false) }
    var intersectionMenuExpanded by remember(intersection?.id) { mutableStateOf(false) }
    if (showSimulation && intersection != null) {
        TrafficSimulationScreen(intersection = intersection, onBack = { showSimulation = false })
        return
    }
    var selectedApproachIndex by remember(intersection?.id) { mutableIntStateOf(0) }
    var drawingTool by remember(intersection?.id) { mutableStateOf(DrawingTool.LINE) }
    var editMode by remember(intersection?.id) { mutableStateOf(false) }
    var viewportZoom by remember(intersection?.id) { mutableStateOf(1f) }
    var viewportPan by remember(intersection?.id) { mutableStateOf(Offset.Zero) }
    var selectedRoadSegmentId by remember(intersection?.id) { mutableStateOf<String?>(null) }
    var islandDrawingMode by remember(intersection?.id) { mutableStateOf(false) }
    var sourceLaneKey by remember(intersection?.id) { mutableStateOf<String?>(null) }
    var targetLaneKey by remember(intersection?.id) { mutableStateOf<String?>(null) }
    var sourceLaneMenuExpanded by remember(intersection?.id) { mutableStateOf(false) }
    var targetLaneMenuExpanded by remember(intersection?.id) { mutableStateOf(false) }
    var vehicleMenuExpanded by remember(intersection?.id) { mutableStateOf(false) }
    var selectedVehicleModelId by remember(intersection?.id) { mutableStateOf(VehicleModelCatalog.defaultPassengerCar.id) }
    var phaseTimingInputs by remember(intersection?.id) { mutableStateOf(emptyMap<String, String>()) }
    val pendingPoints = remember(intersection?.id) { mutableStateListOf<Point2D>() }
    val safeIndex = selectedApproachIndex.coerceIn(0, max(0, (intersection?.approaches?.size ?: 1) - 1))
    val selectedApproach = intersection?.approaches?.getOrNull(safeIndex)
    val selectedVehicleModel = VehicleModelCatalog.defaults.firstOrNull { it.id == selectedVehicleModelId }
        ?: VehicleModelCatalog.defaultPassengerCar
    val validationErrors = intersection?.let(IntersectionGeometryEditor::validate).orEmpty()
    val trajectoryConflicts = remember(intersection?.id, intersection?.roadSegments) {
        intersection?.let(TrajectoryConflictAnalyzer::analyze).orEmpty()
    }
    val movementTrajectories = remember(intersection?.id, intersection?.laneConnections, intersection?.approaches, selectedVehicleModel) {
        intersection?.let { TrajectoryConflictAnalyzer.buildMovementTrajectories(it, selectedVehicleModel) }.orEmpty()
    }
    val movementConflicts = remember(intersection?.id, intersection?.laneConnections, intersection?.approaches, intersection?.signalPlan) {
        intersection?.let(TrajectoryConflictAnalyzer::analyzeMovementConflicts).orEmpty()
    }
    val envelopeConflicts = remember(movementTrajectories) {
        VehicleEnvelopeConflictAnalyzer.analyze(movementTrajectories)
    }
    val timingReport = remember(intersection?.id, intersection?.signalPlan, intersection?.laneConnections) {
        intersection?.let(SignalPlanTimingAnalyzer::analyze)
    }

    fun updateIntersection(updated: Intersection) {
        onProjectChange(
            project.copy(
                updatedAtEpochMs = System.currentTimeMillis(),
                network = project.network.copy(
                    intersections = project.network.intersections.map {
                        if (it.id == updated.id) updated else it
                    }
                )
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Геометрический редактор", style = MaterialTheme.typography.titleLarge)
                Text(project.name, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (project.network.intersections.size > 1) {
                    Box {
                        OutlinedButton(onClick = { intersectionMenuExpanded = true }) { Text("Узлы ▾") }
                        DropdownMenu(expanded = intersectionMenuExpanded, onDismissRequest = { intersectionMenuExpanded = false }) {
                            project.network.intersections.forEachIndexed { index, item ->
                                DropdownMenuItem(text = { Text((index + 1).toString() + ". " + item.name) },
                                    onClick = { intersectionMenuExpanded = false; onSelectIntersection(item.id) })
                            }
                        }
                    }
                }
                if (project.network.intersections.isNotEmpty()) OutlinedButton(onClick = onAddIntersection) { Text("+ Узел") }
                OutlinedButton(onClick = { showSimulation = true }, enabled = intersection != null) { Text("Моделирование") }
                OutlinedButton(onClick = onBack) { Text("Назад") }
            }
        }

        // Keep a real, visible drawing surface on short phone screens.
        // The surrounding screen scrolls instead of squeezing this weighted card to zero height.
        Card(modifier = Modifier.fillMaxWidth().height(320.dp)) {
            Box(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                if (intersection != null) {
                    IntersectionGeometryPreview(
                        modifier = Modifier.fillMaxSize(),
                        intersection = intersection,
                        movementTrajectories = movementTrajectories,
                        movementConflicts = movementConflicts,
                        vehicleModel = selectedVehicleModel,
                        pendingPoints = pendingPoints,
                        drawingTool = drawingTool,
                        onAddPoint = { point -> pendingPoints.add(snapToNetwork(point, intersection)) },
                        zoom = viewportZoom, pan = viewportPan, editMode = editMode,
                        selectedSegmentId = selectedRoadSegmentId,
                        onViewportChange = { z, p -> viewportZoom = z; viewportPan = p },
                        onSelectSegment = { selectedRoadSegmentId = it },
                        onMovePoint = { segmentId, pointIndex, point ->
                            val current = intersection.roadSegments.firstOrNull { it.id == segmentId }
                            if (current != null && pointIndex in current.centerline.indices) {
                                val changed = current.copy(centerline = current.centerline.toMutableList().also { it[pointIndex] = point })
                                val changedSegments = intersection.roadSegments.map { if (it.id == segmentId) changed else it }
                                updateIntersection(intersection.copy(roadSegments = changedSegments, junctionNodes = rebuildJunctionNodes(changedSegments)))
                            }
                        }
                    )
                    Text(
                        text = "Линейный масштаб: 5 м",
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                    Text(
                        text = intersection.name,
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                } else {
                    Text("Нет геометрии перекрёстка", modifier = Modifier.align(Alignment.Center))
                }
            }
        }

        if (intersection != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Расчётная модель транспортного средства", style = MaterialTheme.typography.titleMedium)
                    Box {
                        OutlinedButton(onClick = { vehicleMenuExpanded = true }) {
                            Text(selectedVehicleModel.name)
                        }
                        DropdownMenu(expanded = vehicleMenuExpanded, onDismissRequest = { vehicleMenuExpanded = false }) {
                            VehicleModelCatalog.defaults.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.name) },
                                    onClick = { selectedVehicleModelId = model.id; vehicleMenuExpanded = false }
                                )
                            }
                        }
                    }
                    Text("Габариты: ${"%.2f".format(selectedVehicleModel.lengthMeters)} × " +
                        "${"%.2f".format(selectedVehicleModel.widthMeters)} × " +
                        "${"%.2f".format(selectedVehicleModel.heightMeters)} м",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Колёсная база: ${"%.2f".format(selectedVehicleModel.wheelbaseMeters)} м · " +
                        "минимальный радиус поворота: ${"%.2f".format(selectedVehicleModel.minimumTurningRadiusMeters)} м",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Максимальная скорость: ${selectedVehicleModel.maximumSpeedKmh.toInt()} км/ч · " +
                        "разгон: ${"%.2f".format(selectedVehicleModel.accelerationMetersPerSecondSquared)} м/с² · " +
                        "служебное замедление: ${"%.2f".format(selectedVehicleModel.serviceDecelerationMetersPerSecondSquared)} м/с²",
                        style = MaterialTheme.typography.bodySmall)
                    Text("На схеме модель показана в масштабе на траекториях. Значения каталога — базовые инженерные параметры; уточняйте их по конкретной модели транспорта.",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (intersection != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Редактор геометрии", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!editMode) Button(onClick = { editMode = true; pendingPoints.clear(); islandDrawingMode = false }) { Text("Редактировать") }
                        else Button(onClick = { editMode = false }) { Text("Рисовать") }
                        OutlinedButton(onClick = { viewportZoom = (viewportZoom / 1.25f).coerceAtLeast(0.35f) }) { Text("−") }
                        Text("${(viewportZoom * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        OutlinedButton(onClick = { viewportZoom = (viewportZoom * 1.25f).coerceAtMost(4f) }) { Text("+") }
                        OutlinedButton(onClick = { viewportZoom = 1f; viewportPan = Offset.Zero }) { Text("Сброс вида") }
                    }
                    if (!editMode) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { islandDrawingMode = !islandDrawingMode; pendingPoints.clear() }) { Text(if (islandDrawingMode) "Рисуем островок" else "Добавить островок") }
                    }
                    if (!editMode && !islandDrawingMode) Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DrawingTool.values().forEach { tool ->
                            if (drawingTool == tool) {
                                Button(onClick = { drawingTool = tool; pendingPoints.clear() }) { Text(tool.title) }
                            } else {
                                OutlinedButton(onClick = { drawingTool = tool; pendingPoints.clear() }) { Text(tool.title) }
                            }
                        }
                    }
                    if (!editMode) Text(if (islandDrawingMode) "Поставьте минимум 3 точки контура островка." else "Точки: ${pendingPoints.size}. Нажимайте на поле, чтобы задать трассу.")
                    else Text("Коснитесь дороги для выбора. Перетаскивайте узлы; жест двумя пальцами — масштаб и панорамирование.")
                    if (!editMode) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { if (pendingPoints.isNotEmpty()) pendingPoints.removeAt(pendingPoints.lastIndex) },
                            enabled = pendingPoints.isNotEmpty()) { Text("Отменить точку") }
                        OutlinedButton(onClick = { pendingPoints.clear() }, enabled = pendingPoints.isNotEmpty()) {
                            Text("Очистить")
                        }
                        Button(onClick = {
                            if (islandDrawingMode && pendingPoints.size >= 3) {
                                val island = SafetyIsland(name = "Островок ${intersection.safetyIslands.size + 1}", boundary = pendingPoints.toList())
                                updateIntersection(intersection.copy(safetyIslands = intersection.safetyIslands + island))
                                pendingPoints.clear()
                                islandDrawingMode = false
                            } else if (!islandDrawingMode && pendingPoints.size >= 2) {
                                val kind = drawingTool.kind
                                val level = if (kind == RoadSegmentKind.BRIDGE) 1 else 0
                                val segment = RoadSegment(
                                    name = when (kind) {
                                        RoadSegmentKind.STRAIGHT -> "Дорога ${intersection.roadSegments.size + 1}"
                                        RoadSegmentKind.POLYLINE -> "Линия ${intersection.roadSegments.size + 1}"
                                        RoadSegmentKind.ARC -> "Дуга ${intersection.roadSegments.size + 1}"
                                        RoadSegmentKind.BRIDGE -> "Мост ${intersection.roadSegments.size + 1}"
                                        RoadSegmentKind.RAMP -> "Съезд ${intersection.roadSegments.size + 1}"
                                    },
                                    centerline = pendingPoints.toList(),
                                    widthMeters = if (kind == RoadSegmentKind.BRIDGE) 10.5 else 7.0,
                                    laneCount = if (kind == RoadSegmentKind.BRIDGE) 3 else 2,
                                    elevationLevel = level,
                                    isRamp = kind == RoadSegmentKind.RAMP,
                                    kind = kind,
                                    arcRadiusMeters = if (kind == RoadSegmentKind.ARC) estimateArcRadius(pendingPoints) else null
                                )
                                val updatedSegments = intersection.roadSegments + segment
                                updateIntersection(intersection.copy(
                                    type = ru.trafficsignalstudio.model.IntersectionType.CUSTOM,
                                    roadSegments = updatedSegments,
                                    junctionNodes = rebuildJunctionNodes(updatedSegments)
                                ))
                                pendingPoints.clear()
                            }
                        }, enabled = if (islandDrawingMode || drawingTool == DrawingTool.ARC) pendingPoints.size >= 3 else pendingPoints.size >= 2) { Text(if (islandDrawingMode) "Завершить островок" else "Завершить линию") }
                    }
                    if (!editMode) Text("Для дуги задайте 3 точки: начало, контрольную и конец. Мост рисуется на верхнем уровне.")
                    val selectedSegment = intersection.roadSegments.firstOrNull { it.id == selectedRoadSegmentId }
                    if (editMode && selectedSegment != null) {
                        Text("Выбрано: ${selectedSegment.name}", style = MaterialTheme.typography.titleSmall)
                        Text("Полос: ${selectedSegment.laneCount} · ширина: ${"%.2f".format(selectedSegment.widthMeters)} м")
                        if (selectedSegment.kind == RoadSegmentKind.ARC && selectedSegment.centerline.size >= 3) {
                            Text("Радиус дуги: ${"%.1f".format(selectedSegment.arcRadiusMeters ?: estimateArcRadius(selectedSegment.centerline))} м")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = {
                                    val v = adjustArcRadius(selectedSegment, (selectedSegment.arcRadiusMeters ?: estimateArcRadius(selectedSegment.centerline)) - 1.0)
                                    updateIntersection(intersection.copy(roadSegments = intersection.roadSegments.map { if (it.id == selectedSegment.id) v else it }))
                                }) { Text("− 1 м") }
                                OutlinedButton(onClick = {
                                    val v = adjustArcRadius(selectedSegment, (selectedSegment.arcRadiusMeters ?: estimateArcRadius(selectedSegment.centerline)) + 1.0)
                                    updateIntersection(intersection.copy(roadSegments = intersection.roadSegments.map { if (it.id == selectedSegment.id) v else it }))
                                }) { Text("+ 1 м") }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = {
                                updateIntersection(IntersectionGeometryEditor.setRoadLaneCount(intersection, selectedSegment.id, (selectedSegment.laneCount - 1).coerceAtLeast(1)))
                            }, enabled = selectedSegment.laneCount > 1) { Text("− полоса") }
                            OutlinedButton(onClick = {
                                updateIntersection(IntersectionGeometryEditor.setRoadLaneCount(intersection, selectedSegment.id, (selectedSegment.laneCount + 1).coerceAtMost(16)))
                            }, enabled = selectedSegment.laneCount < 16) { Text("+ полоса") }
                            OutlinedButton(onClick = {
                                updateIntersection(IntersectionGeometryEditor.changeRoadSegmentWidth(intersection, selectedSegment.id, -0.5))
                            }, enabled = selectedSegment.widthMeters > 2.5) { Text("− ширина") }
                            OutlinedButton(onClick = {
                                updateIntersection(IntersectionGeometryEditor.changeRoadSegmentWidth(intersection, selectedSegment.id, 0.5))
                            }, enabled = selectedSegment.widthMeters < 60.0) { Text("+ ширина") }
                        }
                        Text("Индивидуальные полосы", style = MaterialTheme.typography.titleSmall)
                        IntersectionGeometryEditor.effectiveRoadLanes(selectedSegment).forEach { roadLane ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Полоса ${roadLane.index} · ${"%.2f".format(roadLane.widthMeters)} м",
                                        style = MaterialTheme.typography.bodyMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        OutlinedButton(onClick = {
                                            updateIntersection(IntersectionGeometryEditor.changeRoadLaneWidth(
                                                intersection, selectedSegment.id, roadLane.id, -0.25))
                                        }, enabled = roadLane.widthMeters > 2.5) { Text("−") }
                                        OutlinedButton(onClick = {
                                            updateIntersection(IntersectionGeometryEditor.changeRoadLaneWidth(
                                                intersection, selectedSegment.id, roadLane.id, 0.25))
                                        }, enabled = roadLane.widthMeters < 5.0) { Text("+") }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OutlinedButton(onClick = {
                                        val next = if (roadLane.travelDirection == LaneTravelDirection.ALONG_CENTERLINE)
                                            LaneTravelDirection.AGAINST_CENTERLINE else LaneTravelDirection.ALONG_CENTERLINE
                                        updateIntersection(IntersectionGeometryEditor.setRoadLaneDirection(
                                            intersection, selectedSegment.id, roadLane.id, next))
                                    }) {
                                        Text(if (roadLane.travelDirection == LaneTravelDirection.ALONG_CENTERLINE)
                                            "→ По оси" else "← Против оси")
                                    }
                                }
                                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(
                                        MovementKind.THROUGH to "Прямо",
                                        MovementKind.LEFT to "Налево",
                                        MovementKind.RIGHT to "Направо",
                                        MovementKind.U_TURN to "Разворот"
                                    ).forEach { (movement, title) ->
                                        val enabled = movement in roadLane.allowedMovements
                                        if (enabled) Button(onClick = {
                                            val next = if (roadLane.allowedMovements.size > 1)
                                                roadLane.allowedMovements - movement else roadLane.allowedMovements
                                            updateIntersection(IntersectionGeometryEditor.setRoadLaneMovements(
                                                intersection, selectedSegment.id, roadLane.id, next))
                                        }) { Text(title) }
                                        else OutlinedButton(onClick = {
                                            updateIntersection(IntersectionGeometryEditor.setRoadLaneMovements(
                                                intersection, selectedSegment.id, roadLane.id,
                                                roadLane.allowedMovements + movement))
                                        }) { Text(title) }
                                    }
                                }
                            }
                        }
                        OutlinedButton(onClick = {
                            val remaining = intersection.roadSegments.filterNot { it.id == selectedSegment.id }
                            updateIntersection(intersection.copy(roadSegments = remaining, junctionNodes = rebuildJunctionNodes(remaining)))
                            selectedRoadSegmentId = null
                        }) { Text("Удалить дорогу") }
                    } else if (editMode) Text("Выберите дорожный сегмент касанием на поле.")
                }
            }
        }

        if (intersection != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Пересечения и конфликты траекторий", style = MaterialTheme.typography.titleMedium)
                    Text("Найдено геометрических конфликтов: ${trajectoryConflicts.size}",
                        color = if (trajectoryConflicts.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    if (trajectoryConflicts.isEmpty()) {
                        Text("Между полосами свободно нарисованных сегментов конфликтов в плане не обнаружено.",
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        trajectoryConflicts.take(8).forEach { conflict ->
                            val firstName = intersection.roadSegments.firstOrNull { it.id == conflict.firstSegmentId }?.name ?: "Дорога"
                            val secondName = intersection.roadSegments.firstOrNull { it.id == conflict.secondSegmentId }?.name ?: "Дорога"
                            val kind = when (conflict.kind) {
                                TrajectoryConflictKind.CROSSING -> "Пересечение"
                                TrajectoryConflictKind.MERGING -> "Слияние"
                                TrajectoryConflictKind.SHARED_PATH -> "Общий участок"
                            }
                            Text("$kind: $firstName, полоса ${conflict.firstLaneIndex} ↔ $secondName, полоса ${conflict.secondLaneIndex} · (${"%.1f".format(conflict.point.xMeters)}, ${"%.1f".format(conflict.point.yMeters)}) м",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (trajectoryConflicts.size > 8) Text("И ещё ${trajectoryConflicts.size - 8} конфликтов…",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Проверка учитывает геометрические пересечения полос на одном высотном уровне. Это предварительный анализ, не нормативная экспертиза.",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (intersection != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Траектории поворотов и светофорные фазы", style = MaterialTheme.typography.titleMedium)
                    Text("Траекторий по связям полос: ${movementTrajectories.size}. Геометрических пар конфликтов: ${movementConflicts.size}.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = {
                            val current = intersection.signalPlan ?: SignalPlan()
                            val phase = SignalPhase(name = "Фаза ${current.phases.size + 1}", greenConnectionIds = emptySet())
                            updateIntersection(intersection.copy(signalPlan = current.copy(phases = current.phases + phase)))
                        }) { Text("Добавить фазу") }
                        OutlinedButton(onClick = {
                            val current = intersection.signalPlan ?: SignalPlan()
                            updateIntersection(intersection.copy(signalPlan = current.copy(enabled = !current.enabled)))
                        }) { Text(if (intersection.signalPlan?.enabled == false) "Включить план" else "Отключить план") }
                    }
                    if (intersection.laneConnections.isEmpty()) {
                        Text("Сначала создайте связи въездных и выездных полос — по ним строятся траектории поворотов.")
                    } else {
                        Text("Назначение движений фазам", style = MaterialTheme.typography.titleSmall)
                        movementTrajectories.forEach { trajectory ->
                            val from = intersection.approaches.firstOrNull { it.id == trajectory.fromApproachId }
                            val fromLane = from?.lanes?.firstOrNull { it.id == trajectory.fromLaneId }
                            val to = intersection.approaches.firstOrNull { it.id == trajectory.toApproachId }
                            val toLane = to?.lanes?.firstOrNull { it.id == trajectory.toLaneId }
                            val movementLabel = when (trajectory.movementKind) {
                                MovementKind.THROUGH -> "прямо"
                                MovementKind.LEFT -> "налево"
                                MovementKind.RIGHT -> "направо"
                                MovementKind.U_TURN -> "разворот"
                                MovementKind.TRANSIT_ONLY -> "транзит"
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${from?.name ?: "?"} №${fromLane?.index ?: "?"} → ${to?.name ?: "?"} №${toLane?.index ?: "?"} · $movementLabel",
                                    style = MaterialTheme.typography.bodySmall)
                                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    intersection.signalPlan?.phases.orEmpty().forEach { phase ->
                                        val assigned = trajectory.connectionId in phase.greenConnectionIds
                                        if (assigned) Button(onClick = {
                                            val plan = intersection.signalPlan ?: SignalPlan()
                                            updateIntersection(intersection.copy(signalPlan = plan.copy(phases = plan.phases.map {
                                                if (it.id == phase.id) it.copy(greenConnectionIds = it.greenConnectionIds - trajectory.connectionId) else it
                                            })))
                                        }) { Text("${phase.name} ✓") }
                                        else OutlinedButton(onClick = {
                                            val plan = intersection.signalPlan ?: SignalPlan()
                                            updateIntersection(intersection.copy(signalPlan = plan.copy(phases = plan.phases.map {
                                                it.copy(greenConnectionIds = if (it.id == phase.id) it.greenConnectionIds + trajectory.connectionId
                                                    else it.greenConnectionIds - trajectory.connectionId)
                                            })))
                                        }) { Text(phase.name) }
                                    }
                                }
                            }
                        }
                    }
                    intersection.signalPlan?.phases.orEmpty().forEach { phase ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(phase.name, style = MaterialTheme.typography.titleSmall)
                                OutlinedButton(onClick = {
                                    val plan = intersection.signalPlan ?: SignalPlan()
                                    updateIntersection(intersection.copy(signalPlan = plan.copy(phases = plan.phases.filterNot { it.id == phase.id })))
                                }) { Text("Удалить фазу") }
                            }
                            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    Triple("green", "Зелёный, с", phase.greenSeconds),
                                    Triple("flashing", "Мигающий зелёный, с", phase.flashingGreenSeconds),
                                    Triple("yellow", "Жёлтый, с", phase.yellowSeconds),
                                    Triple("allRed", "Красный всех, с", phase.allRedSeconds)
                                ).forEach { (key, label, currentValue) ->
                                    val inputKey = phase.id + ":" + key
                                    OutlinedTextField(
                                        value = phaseTimingInputs[inputKey] ?: currentValue.toString(),
                                        onValueChange = { raw ->
                                            phaseTimingInputs = phaseTimingInputs + (inputKey to raw)
                                            val parsed = raw.toIntOrNull()
                                            val minimum = if (key == "green") 1 else 0
                                            if (parsed != null && parsed >= minimum) {
                                                val plan = intersection.signalPlan ?: SignalPlan()
                                                updateIntersection(intersection.copy(signalPlan = plan.copy(phases = plan.phases.map { item ->
                                                    if (item.id != phase.id) item else when (key) {
                                                        "green" -> item.copy(greenSeconds = parsed)
                                                        "flashing" -> item.copy(flashingGreenSeconds = parsed)
                                                        "yellow" -> item.copy(yellowSeconds = parsed)
                                                        else -> item.copy(allRedSeconds = parsed)
                                                    }
                                                })))
                                            }
                                        },
                                        modifier = Modifier.width(150.dp),
                                        label = { Text(label) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                    )
                                }
                            }
                            Text("Полный интервал фазы: ${phase.greenSeconds + phase.flashingGreenSeconds + phase.yellowSeconds + phase.allRedSeconds} с",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    timingReport?.let { report ->
                        Text("Расчётный цикл: ${report.cycleLengthSeconds} с",
                            style = MaterialTheme.typography.titleSmall)
                        report.phaseWindows.forEach { window ->
                            Text("${window.phaseName}: зелёный ${window.startSeconds}–${window.greenEndSeconds} с; " +
                                "мигающий зелёный до ${window.flashingGreenEndSeconds} с; жёлтый до ${window.yellowEndSeconds} с; " +
                                "общий красный до ${window.allRedEndSeconds} с",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        report.diagnostics.forEach { diagnostic ->
                            Text("Проверка: ${diagnostic.message}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("Конфликтов габаритов ТС: ${envelopeConflicts.size}",
                        style = MaterialTheme.typography.bodySmall)
                    if (envelopeConflicts.isNotEmpty()) {
                        envelopeConflicts.take(5).forEach { conflict ->
                            Text("${conflict.firstConnectionId} ↔ ${conflict.secondConnectionId}: " +
                                "зазор между осевыми траекториями ${"%.2f".format(conflict.minimumCenterlineDistanceMeters)} м " +
                                "(требуется ${"%.2f".format(conflict.requiredClearanceMeters)} м)",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (movementConflicts.isNotEmpty()) {
                        Text("—", color = MaterialTheme.colorScheme.outline)
                        Text("Оценка конфликтов с учётом фаз", style = MaterialTheme.typography.titleSmall)
                        movementConflicts.take(10).forEach { conflict ->
                            val status = when (conflict.signalStatus) {
                                ConflictSignalStatus.SIMULTANEOUS_GREEN -> "ОДНОВРЕМЕННЫЙ ЗЕЛЁНЫЙ"
                                ConflictSignalStatus.SEPARATED_BY_PHASE -> "РАЗДЕЛЁН ФАЗАМИ, ЖЁЛТЫЙ + ОБЩИЙ КРАСНЫЙ"
                                ConflictSignalStatus.PHASES_SEPARATED_NO_CLEARANCE -> "РАЗНЫЕ ФАЗЫ, НЕТ ПОЛНОГО ИНТЕРВАЛА ОЧИСТКИ"
                                ConflictSignalStatus.UNASSIGNED -> "НЕ НАЗНАЧЕНЫ ФАЗЫ"
                                ConflictSignalStatus.NO_SIGNAL_PLAN -> "НЕТ ПЛАНА СВЕТОФОРА"
                            }
                            val firstKind = when (conflict.firstMovement) {
                                MovementKind.THROUGH -> "прямо"
                                MovementKind.LEFT -> "налево"
                                MovementKind.RIGHT -> "направо"
                                MovementKind.U_TURN -> "разворот"
                                MovementKind.TRANSIT_ONLY -> "транзит"
                            }
                            val secondKind = when (conflict.secondMovement) {
                                MovementKind.THROUGH -> "прямо"
                                MovementKind.LEFT -> "налево"
                                MovementKind.RIGHT -> "направо"
                                MovementKind.U_TURN -> "разворот"
                                MovementKind.TRANSIT_ONLY -> "транзит"
                            }
                            Text("$firstKind ↔ $secondKind: $status · точка (${"%.1f".format(conflict.point.xMeters)}, ${"%.1f".format(conflict.point.yMeters)}) м",
                                color = if (conflict.signalStatus == ConflictSignalStatus.SIMULTANEOUS_GREEN)
                                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (movementConflicts.size > 10) Text("И ещё ${movementConflicts.size - 10} пар…")
                    }
                    Text("Разные фазы считаются временным разделением только при однозначном назначении обеих траекторий. Жёлтый и общий красный хранятся как интервалы очистки между фазами; это не моделирует реальные циклы и задержки транспорта.",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (intersection != null && selectedApproach != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Дорожные подходы", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        intersection.approaches.forEachIndexed { index, approach ->
                            OutlinedButton(
                                onClick = { selectedApproachIndex = index },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    approach.name,
                                    color = if (index == safeIndex) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    Text("Подход: ${selectedApproach.name}", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Количество полос: ${selectedApproach.lanes.size}")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    if (selectedApproach.lanes.size > IntersectionGeometryEditor.MIN_LANE_COUNT) {
                                        updateIntersection(
                                            IntersectionGeometryEditor.setLaneCount(
                                                intersection, selectedApproach.id, selectedApproach.lanes.size - 1
                                            )
                                        )
                                    }
                                },
                                enabled = selectedApproach.lanes.size > IntersectionGeometryEditor.MIN_LANE_COUNT
                            ) { Text("−") }
                            OutlinedButton(
                                onClick = {
                                    if (selectedApproach.lanes.size < IntersectionGeometryEditor.MAX_LANE_COUNT) {
                                        updateIntersection(
                                            IntersectionGeometryEditor.setLaneCount(
                                                intersection, selectedApproach.id, selectedApproach.lanes.size + 1
                                            )
                                        )
                                    }
                                },
                                enabled = selectedApproach.lanes.size < IntersectionGeometryEditor.MAX_LANE_COUNT
                            ) { Text("+") }
                        }
                    }
                    Text("Настройка полос", style = MaterialTheme.typography.titleSmall)
                    Text("Стрелка на схеме показывает фактическое направление потока по каждой полосе.")
                    selectedApproach.lanes.sortedBy { it.index }.forEach { lane ->
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Полоса ${lane.index} · ${"%.2f".format(lane.widthMeters)} м")
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            updateIntersection(
                                                IntersectionGeometryEditor.changeLaneWidth(
                                                    intersection, selectedApproach.id, lane.id,
                                                    -IntersectionGeometryEditor.LANE_WIDTH_STEP_METERS
                                                )
                                            )
                                        },
                                        enabled = lane.widthMeters > IntersectionGeometryEditor.MIN_LANE_WIDTH_METERS
                                    ) { Text("−") }
                                    OutlinedButton(
                                        onClick = {
                                            updateIntersection(
                                                IntersectionGeometryEditor.changeLaneWidth(
                                                    intersection, selectedApproach.id, lane.id,
                                                    IntersectionGeometryEditor.LANE_WIDTH_STEP_METERS
                                                )
                                            )
                                        },
                                        enabled = lane.widthMeters < IntersectionGeometryEditor.MAX_LANE_WIDTH_METERS
                                    ) { Text("+") }
                                }
                            }
                            Text("Направление движения по полосе", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                val inbound = lane.flowDirection == LaneFlowDirection.INTO_INTERSECTION
                                if (inbound) {
                                    Button(onClick = { updateIntersection(IntersectionGeometryEditor.setLaneFlowDirection(intersection, selectedApproach.id, lane.id, LaneFlowDirection.INTO_INTERSECTION)) }) { Text("↓ К перекрёстку") }
                                } else {
                                    OutlinedButton(onClick = {
                                        updateIntersection(IntersectionGeometryEditor.setLaneFlowDirection(
                                            intersection, selectedApproach.id, lane.id, LaneFlowDirection.INTO_INTERSECTION
                                        ))
                                    }) { Text("↓ К перекрёстку") }
                                }
                                if (!inbound) {
                                    Button(onClick = { updateIntersection(IntersectionGeometryEditor.setLaneFlowDirection(intersection, selectedApproach.id, lane.id, LaneFlowDirection.OUT_OF_INTERSECTION)) }) { Text("↑ От перекрёстка") }
                                } else {
                                    OutlinedButton(onClick = {
                                        updateIntersection(IntersectionGeometryEditor.setLaneFlowDirection(
                                            intersection, selectedApproach.id, lane.id, LaneFlowDirection.OUT_OF_INTERSECTION
                                        ))
                                    }) { Text("↑ От перекрёстка") }
                                }
                            }
                            if (lane.flowDirection == LaneFlowDirection.INTO_INTERSECTION) {
                                Text("Разрешённые манёвры на въезде", style = MaterialTheme.typography.labelMedium)
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    MovementKind.values().forEach { movement ->
                                        val selected = movement in lane.allowedMovements
                                        val label = when (movement) {
                                            MovementKind.THROUGH -> "↑ Прямо"
                                            MovementKind.LEFT -> "↰ Налево"
                                            MovementKind.RIGHT -> "↱ Направо"
                                            MovementKind.U_TURN -> "↶ Разворот"
                                            MovementKind.TRANSIT_ONLY -> "Только ОТ"
                                        }
                                        if (selected) {
                                            Button(
                                                onClick = {
                                                    if (lane.allowedMovements.size > 1) {
                                                        updateIntersection(IntersectionGeometryEditor.setLaneMovements(
                                                            intersection, selectedApproach.id, lane.id,
                                                            lane.allowedMovements - movement
                                                        ))
                                                    }
                                                },
                                                enabled = lane.allowedMovements.size > 1
                                            ) { Text(label) }
                                        } else {
                                            OutlinedButton(onClick = {
                                                updateIntersection(IntersectionGeometryEditor.setLaneMovements(
                                                    intersection, selectedApproach.id, lane.id,
                                                    lane.allowedMovements + movement
                                                ))
                                            }) { Text(label) }
                                        }
                                    }
                                }
                            } else {
                                Text("Выездная полоса: поток направлен от перекрёстка. Манёвры въезда для неё не задаются.")
                            }
                        }
                    }
                    Text("Можно назначить несколько манёвров одной полосе. Хотя бы одно направление должно оставаться разрешённым. Ширина: 2,50–5,00 м.")
                }
            }
        }

        if (intersection != null) {
            val incomingLaneOptions = intersection.approaches.flatMap { approach ->
                approach.lanes.filter { it.flowDirection == LaneFlowDirection.INTO_INTERSECTION }
                    .map { lane -> Triple(approach, lane, "${approach.id}:${lane.id}") }
            }
            val outgoingLaneOptions = intersection.approaches.flatMap { approach ->
                approach.lanes.filter { it.flowDirection == LaneFlowDirection.OUT_OF_INTERSECTION }
                    .map { lane -> Triple(approach, lane, "${approach.id}:${lane.id}") }
            }
            val sourceOption = incomingLaneOptions.firstOrNull { it.third == sourceLaneKey } ?: incomingLaneOptions.firstOrNull()
            val targetOption = outgoingLaneOptions.firstOrNull { it.third == targetLaneKey } ?: outgoingLaneOptions.firstOrNull()
            val duplicateConnection = sourceOption != null && targetOption != null && intersection.laneConnections.any {
                it.fromApproachId == sourceOption.first.id && it.fromLaneId == sourceOption.second.id &&
                    it.toApproachId == targetOption.first.id && it.toLaneId == targetOption.second.id
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Связи между полосами", style = MaterialTheme.typography.titleMedium)
                    Text("Выберите въездную полосу, затем выездную. На схеме появится траектория движения.")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(onClick = { sourceLaneMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(sourceOption?.let { "${it.first.name} · №${it.second.index}" } ?: "Въездная полоса")
                            }
                            DropdownMenu(expanded = sourceLaneMenuExpanded, onDismissRequest = { sourceLaneMenuExpanded = false }) {
                                incomingLaneOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text("${option.first.name} · полоса ${option.second.index}") },
                                        onClick = { sourceLaneKey = option.third; sourceLaneMenuExpanded = false }
                                    )
                                }
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(onClick = { targetLaneMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(targetOption?.let { "${it.first.name} · №${it.second.index}" } ?: "Выездная полоса")
                            }
                            DropdownMenu(expanded = targetLaneMenuExpanded, onDismissRequest = { targetLaneMenuExpanded = false }) {
                                outgoingLaneOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text("${option.first.name} · полоса ${option.second.index}") },
                                        onClick = { targetLaneKey = option.third; targetLaneMenuExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                    Button(
                        onClick = {
                            if (sourceOption != null && targetOption != null) {
                                val updated = runCatching {
                                    IntersectionGeometryEditor.addLaneConnection(intersection, LaneConnection(
                                        fromApproachId = sourceOption.first.id,
                                        fromLaneId = sourceOption.second.id,
                                        toApproachId = targetOption.first.id,
                                        toLaneId = targetOption.second.id
                                    ))
                                }.getOrNull()
                                if (updated != null) updateIntersection(updated)
                            }
                        },
                        enabled = sourceOption != null && targetOption != null && !duplicateConnection
                    ) { Text("Добавить траекторию") }
                    if (incomingLaneOptions.isEmpty()) Text("Сначала назначьте хотя бы одну полосу как въездную.")
                    if (outgoingLaneOptions.isEmpty()) Text("Сначала назначьте хотя бы одну полосу как выездную.")
                    if (intersection.laneConnections.isNotEmpty()) {
                        Text("Созданные траектории", style = MaterialTheme.typography.labelLarge)
                        intersection.laneConnections.forEach { connection ->
                            val fromApproach = intersection.approaches.firstOrNull { it.id == connection.fromApproachId }
                            val fromLane = fromApproach?.lanes?.firstOrNull { it.id == connection.fromLaneId }
                            val toApproach = intersection.approaches.firstOrNull { it.id == connection.toApproachId }
                            val toLane = toApproach?.lanes?.firstOrNull { it.id == connection.toLaneId }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${fromApproach?.name ?: "?"} №${fromLane?.index ?: "?"} → ${toApproach?.name ?: "?"} №${toLane?.index ?: "?"}",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                OutlinedButton(onClick = {
                                    updateIntersection(IntersectionGeometryEditor.removeLaneConnection(intersection, connection.id))
                                }) { Text("Удалить") }
                            }
                        }
                    }
                }
            }
        }

        if (validationErrors.isNotEmpty()) {
            Text("Проверка геометрии: ${validationErrors.first()}", color = MaterialTheme.colorScheme.error)
        } else if (intersection != null) {
            Text("Проверка геометрии: ошибок базовой структуры нет", color = MaterialTheme.colorScheme.primary)
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Отмена") }
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                enabled = intersection != null && validationErrors.isEmpty()
            ) { Text("Сохранить") }
        }
    }
}

@Composable
private fun IntersectionGeometryPreview(
    modifier: Modifier = Modifier,
    intersection: Intersection,
    movementTrajectories: List<MovementTrajectory>,
    movementConflicts: List<ru.trafficsignalstudio.geometry.MovementTrajectoryConflict>,
    vehicleModel: VehicleModel,
    pendingPoints: List<Point2D>,
    drawingTool: DrawingTool, onAddPoint: (Point2D) -> Unit,
    zoom: Float, pan: Offset, editMode: Boolean, selectedSegmentId: String?,
    onViewportChange: (Float, Offset) -> Unit, onSelectSegment: (String?) -> Unit,
    onMovePoint: (String, Int, Point2D) -> Unit
) {
    Canvas(modifier = modifier
        .background(Color(0xFFE8EEE8), RoundedCornerShape(12.dp))
        .pointerInput(intersection.id, drawingTool, editMode, selectedSegmentId, zoom, pan) {
            detectTapGestures { offset ->
                val factor = (minOf(size.width, size.height) * 0.28f / 14.0f * zoom).coerceAtLeast(0.0001f)
                if (editMode) {
                    val center = Offset(size.width / 2f + pan.x, size.height / 2f + pan.y)
                    val nearest = intersection.roadSegments.mapNotNull { segment ->
                        val d = segment.centerline.zipWithNext().minOfOrNull { (a, b) ->
                            distanceToSegment(offset.x, offset.y, center.x + a.xMeters.toFloat() * factor,
                                center.y - a.yMeters.toFloat() * factor, center.x + b.xMeters.toFloat() * factor,
                                center.y - b.yMeters.toFloat() * factor)
                        } ?: Float.MAX_VALUE
                        if (d < 30f) segment.id to d else null
                    }.minByOrNull { it.second }
                    onSelectSegment(nearest?.first)
                } else onAddPoint(Point2D(
                    xMeters = ((offset.x - size.width / 2f - pan.x) / factor).toDouble(),
                    yMeters = ((size.height / 2f + pan.y - offset.y) / factor).toDouble()
                ))
            }
        }
        .pointerInput(intersection.id, editMode, selectedSegmentId, zoom, pan) {
            if (editMode && selectedSegmentId != null) {
                var draggedIndex = -1
                detectDragGestures(onDragStart = { start ->
                    val segment = intersection.roadSegments.firstOrNull { it.id == selectedSegmentId }
                    val factor = minOf(size.width, size.height) * 0.28f / 14.0f * zoom
                    val center = Offset(size.width / 2f + pan.x, size.height / 2f + pan.y)
                    draggedIndex = segment?.centerline?.indices?.minByOrNull { index ->
                        val p = segment.centerline[index]
                        (Offset(center.x + p.xMeters.toFloat() * factor, center.y - p.yMeters.toFloat() * factor) - start).getDistance()
                    } ?: -1
                }, onDrag = { change, _ ->
                    val segment = intersection.roadSegments.firstOrNull { it.id == selectedSegmentId }
                    if (segment != null && draggedIndex in segment.centerline.indices) {
                        val factor = (minOf(size.width, size.height) * 0.28f / 14.0f * zoom).coerceAtLeast(0.0001f)
                        val old = segment.centerline[draggedIndex]
                        onMovePoint(segment.id, draggedIndex, Point2D(old.xMeters + change.positionChange().x / factor,
                            old.yMeters - change.positionChange().y / factor))
                    }
                    change.consume()
                })
            }
        }
        .pointerInput(intersection.id, editMode) {
            detectTransformGestures { centroid, panChange, zoomChange, _ ->
                if (editMode) {
                    val nextZoom = (zoom * zoomChange).coerceIn(0.35f, 4f)
                    val ratio = nextZoom / zoom
                    onViewportChange(nextZoom, Offset(
                        centroid.x - size.width / 2f - (centroid.x - size.width / 2f - pan.x) * ratio + panChange.x,
                        centroid.y - size.height / 2f - (centroid.y - size.height / 2f - pan.y) * ratio + panChange.y
                    ))
                }
            }
        }) {
        val center = Offset(size.width / 2f + pan.x, size.height / 2f + pan.y)
        // Fixed scale keeps lane-count changes visible and gives custom layouts
        // a stable drawing coordinate system.
        val scale = minOf(size.width, size.height) * 0.28f / 14.0f * zoom
        val roadWidths = intersection.approaches.associate { approach ->
            approach.id to (approach.lanes.sumOf { it.widthMeters }.toFloat() * scale).coerceAtLeast(24f)
        }
        val junctionHalf = (roadWidths.values.maxOrNull() ?: 24f) / 2f
        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))

        // Draw independent centerline segments first. Their elevation level is
        // visual metadata; conflict analysis will use it in a later engine step.
        intersection.safetyIslands.forEach { island ->
            if (island.boundary.size >= 3) {
                val islandPath = Path().apply {
                    val first = island.boundary.first()
                    moveTo(center.x + first.xMeters.toFloat() * scale, center.y - first.yMeters.toFloat() * scale)
                    island.boundary.drop(1).forEach { p -> lineTo(center.x + p.xMeters.toFloat() * scale, center.y - p.yMeters.toFloat() * scale) }
                    close()
                }
                drawPath(islandPath, Color(0xFFB7C9A5))
                drawPath(islandPath, Color(0xFF4F6B45), style = Stroke(width = 2f))
            }
        }
        intersection.junctionNodes.forEach { node ->
            drawCircle(Color(0xFFFFC107), 5f, Offset(center.x + node.point.xMeters.toFloat() * scale, center.y - node.point.yMeters.toFloat() * scale))
        }
        intersection.roadSegments.sortedBy { it.elevationLevel }.forEach { segment ->
            if (segment.centerline.size >= 2) {
                val screenPoints = segment.centerline.map { point ->
                    Offset(center.x + point.xMeters.toFloat() * scale,
                        center.y - point.yMeters.toFloat() * scale)
                }
                val path = Path().apply {
                    moveTo(screenPoints.first().x, screenPoints.first().y)
                    if (segment.kind == RoadSegmentKind.ARC && screenPoints.size >= 3) {
                        quadraticTo(screenPoints[1].x, screenPoints[1].y,
                            screenPoints.last().x, screenPoints.last().y)
                    } else {
                        screenPoints.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                }
                val width = (segment.widthMeters.toFloat() * scale).coerceAtLeast(10f)
                val roundStroke = Stroke(width = width, cap = StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round)
                if (segment.elevationLevel > 0 || segment.kind == RoadSegmentKind.BRIDGE) {
                    drawPath(path, Color(0xFFB7BEC5),
                        style = Stroke(width = width + 9f, cap = StrokeCap.Round,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
                drawPath(path, if (segment.id == selectedSegmentId) Color(0xFF1565C0) else Color(0xFF59616A), style = roundStroke)
                if (segment.kind == RoadSegmentKind.BRIDGE) {
                    drawPath(path, Color(0xFFE0E5E8),
                        style = Stroke(width = 3f, cap = StrokeCap.Round))
                }
                val segmentLanes = IntersectionGeometryEditor.effectiveRoadLanes(segment)
                var laneOffset = -segmentLanes.sumOf { it.widthMeters } / 2.0
                segmentLanes.dropLast(1).forEach { lane ->
                    laneOffset += lane.widthMeters
                    val boundaryPoints = offsetRoadPolyline(segment.centerline, laneOffset).map { point ->
                        Offset(center.x + point.xMeters.toFloat() * scale,
                            center.y - point.yMeters.toFloat() * scale)
                    }
                    val boundaryPath = Path().apply {
                        moveTo(boundaryPoints.first().x, boundaryPoints.first().y)
                        boundaryPoints.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(boundaryPath, Color.White.copy(alpha = 0.85f),
                        style = Stroke(width = 1.5f, pathEffect = dash, cap = StrokeCap.Round))
                }
                var laneStart = -segmentLanes.sumOf { it.widthMeters } / 2.0
                segmentLanes.forEach { lane ->
                    val offset = laneStart + lane.widthMeters / 2.0
                    laneStart += lane.widthMeters
                    var lanePoints = offsetRoadPolyline(segment.centerline, offset)
                    if (lane.travelDirection == LaneTravelDirection.AGAINST_CENTERLINE) lanePoints = lanePoints.reversed()
                    if (lanePoints.size >= 2) {
                        val a = lanePoints[lanePoints.lastIndex / 2]
                        val b = lanePoints[(lanePoints.lastIndex / 2 + 1).coerceAtMost(lanePoints.lastIndex)]
                        val dx = (b.xMeters - a.xMeters).toFloat()
                        val dy = -(b.yMeters - a.yMeters).toFloat()
                        val length = kotlin.math.hypot(dx, dy).coerceAtLeast(0.001f)
                        val ux = dx / length
                        val uy = dy / length
                        val p = Offset(center.x + ((a.xMeters + b.xMeters) / 2.0).toFloat() * scale,
                            center.y - ((a.yMeters + b.yMeters) / 2.0).toFloat() * scale)
                        val lanePixelWidth = (lane.widthMeters * scale).toFloat()
                        val iconSize = min(15f, lanePixelWidth * 0.72f).coerceAtLeast(7f)
                        val movements = lane.allowedMovements
                            .filter { it == MovementKind.THROUGH || it == MovementKind.LEFT ||
                                it == MovementKind.RIGHT || it == MovementKind.U_TURN }
                            .sortedBy { it.ordinal }
                        if (movements.isEmpty()) {
                            drawDirectionArrow(p, ux, uy, min(6f, lanePixelWidth * 0.22f))
                        } else {
                            val spacing = if (movements.size > 1) iconSize * 0.78f else 0f
                            val rotation = Math.toDegrees(kotlin.math.atan2(uy.toDouble(), ux.toDouble())).toFloat() + 90f
                            movements.forEachIndexed { index, movement ->
                                val along = (index - (movements.size - 1) / 2f) * spacing
                                val iconCenter = Offset(p.x + ux * along, p.y + uy * along)
                                rotate(rotation, iconCenter) {
                                    drawMovementArrow(iconCenter.x, iconCenter.y, movement, Color.White, iconSize)
                                }
                            }
                        }
                    }
                }
            }
        }

        intersection.approaches.forEach { approach ->
            val heading = ((approach.headingDegrees % 360.0) + 360.0) % 360.0
            val roadWidth = roadWidths[approach.id] ?: 24f
            val half = roadWidth / 2f
            val laneDividerOffsets = approach.lanes.dropLast(1).runningFold(-half) { offset, lane ->
                offset + lane.widthMeters.toFloat() * scale
            }.drop(1)

            // Local geometry points north; rotating it supports any approach
            // azimuth, not just the four cardinal directions.
            rotate(heading.toFloat(), center) {
                drawRect(Color(0xFF59616A), Offset(center.x - half, 0f),
                    Size(roadWidth, center.y))
                laneDividerOffsets.forEach { offset ->
                    val x = center.x + offset
                    drawLine(Color.White, Offset(x, 0f),
                        Offset(x, center.y - junctionHalf), 2f, pathEffect = dash)
                }
                var laneOffset = -half
                approach.lanes.sortedBy { it.index }.forEach { lane ->
                    val laneCenterX = center.x + laneOffset + lane.widthMeters.toFloat() * scale / 2f
                    val arrowY = center.y * 0.58f
                    val towardCenter = lane.flowDirection == LaneFlowDirection.INTO_INTERSECTION
                    val tipY = if (towardCenter) arrowY + 13f else arrowY - 13f
                    val tailY = if (towardCenter) arrowY - 13f else arrowY + 13f
                    val headBaseY = if (towardCenter) tipY - 8f else tipY + 8f
                    val lanePixelWidth = lane.widthMeters.toFloat() * scale
                    if (lanePixelWidth >= 13f) {
                        if (towardCenter) {
                            // Render the configured lane manoeuvres as road markings.
                            // Multiple arrows are arranged longitudinally so they remain
                            // readable even on a narrow single lane.
                            val movements = lane.allowedMovements
                                .filter { it == MovementKind.THROUGH || it == MovementKind.LEFT ||
                                    it == MovementKind.RIGHT || it == MovementKind.U_TURN }
                                .sortedBy { it.ordinal }
                            val iconSize = min(16f, lanePixelWidth * 0.82f).coerceAtLeast(8f)
                            val spacing = if (movements.size > 1) iconSize * 0.78f else 0f
                            movements.forEachIndexed { index, movement ->
                                val offsetY = (index - (movements.size - 1) / 2f) * spacing
                                val iconCenter = Offset(laneCenterX, arrowY + offsetY)
                                // The movement glyph is authored facing upward. Incoming
                                // traffic on an approach travels toward the junction (down
                                // this local road), so flip the complete glyph around its
                                // center; this also keeps left/right relative to the driver.
                                rotate(180f, iconCenter) {
                                    drawMovementArrow(
                                        x = iconCenter.x,
                                        y = iconCenter.y,
                                        movement = movement,
                                        color = Color.White,
                                        size = iconSize
                                    )
                                }
                            }
                        } else {
                            // Outbound lanes retain a simple arrow pointing away from the node.
                            drawLine(Color.White, Offset(laneCenterX, tailY), Offset(laneCenterX, tipY), 3f, cap = StrokeCap.Round)
                            val wing = min(6f, lanePixelWidth * 0.22f)
                            drawLine(Color.White, Offset(laneCenterX, tipY), Offset(laneCenterX - wing, headBaseY), 3f, cap = StrokeCap.Round)
                            drawLine(Color.White, Offset(laneCenterX, tipY), Offset(laneCenterX + wing, headBaseY), 3f, cap = StrokeCap.Round)
                        }
                    }
                    laneOffset += lane.widthMeters.toFloat() * scale
                }
                drawLine(Color.White,
                    Offset(center.x - half, center.y - junctionHalf - 16f),
                    Offset(center.x + half, center.y - junctionHalf - 16f), 4f)
            }
        }

        movementTrajectories.forEach { trajectory ->
            val path = Path().apply {
                val first = trajectory.points.first()
                moveTo(center.x + first.xMeters.toFloat() * scale,
                    center.y - first.yMeters.toFloat() * scale)
                trajectory.points.drop(1).forEach { point ->
                    lineTo(center.x + point.xMeters.toFloat() * scale,
                        center.y - point.yMeters.toFloat() * scale)
                }
            }
            val hasSimultaneousConflict = movementConflicts.any {
                it.signalStatus == ConflictSignalStatus.SIMULTANEOUS_GREEN &&
                    (it.firstConnectionId == trajectory.connectionId || it.secondConnectionId == trajectory.connectionId)
            }
            val routeColor = if (hasSimultaneousConflict) Color(0xFFFF5252) else Color(0xFF80D8FF)
            drawPath(path, routeColor, style = Stroke(width = 3.5f, cap = StrokeCap.Round))
            if (trajectory.points.size >= 2) {
                val markerIndex = (trajectory.points.lastIndex * 0.62f).toInt().coerceIn(1, trajectory.points.lastIndex)
                val markerBefore = trajectory.points[markerIndex - 1]
                val markerAfter = trajectory.points[markerIndex]
                val markerPoint = trajectory.points[markerIndex]
                val markerHeading = Math.toDegrees(kotlin.math.atan2(
                    -(markerAfter.yMeters - markerBefore.yMeters),
                    markerAfter.xMeters - markerBefore.xMeters
                )).toFloat()
                val nextIndex = (markerIndex + 1).coerceAtMost(trajectory.points.lastIndex)
                val nextPoint = trajectory.points[nextIndex]
                val articulationHeading = if (nextIndex > markerIndex) {
                    Math.toDegrees(kotlin.math.atan2(
                        -(nextPoint.yMeters - markerAfter.yMeters),
                        nextPoint.xMeters - markerAfter.xMeters
                    )).toFloat()
                } else markerHeading
                val articulationAngle = ((articulationHeading - markerHeading + 540f) % 360f - 180f)
                    .coerceIn(-30f, 30f)
                val markerCenter = Offset(
                    center.x + markerPoint.xMeters.toFloat() * scale,
                    center.y - markerPoint.yMeters.toFloat() * scale
                )
                drawVehicleGlyph(
                    markerCenter, markerHeading, scale, vehicleModel,
                    articulationAngleDegrees = articulationAngle
                )
                val before = trajectory.points[trajectory.points.lastIndex - 1]
                val tip = trajectory.points.last()
                val dx = (tip.xMeters - before.xMeters).toFloat()
                val dy = -(tip.yMeters - before.yMeters).toFloat()
                val length = kotlin.math.hypot(dx, dy).coerceAtLeast(0.001f)
                val ux = dx / length
                val uy = dy / length
                val end = Offset(center.x + tip.xMeters.toFloat() * scale,
                    center.y - tip.yMeters.toFloat() * scale)
                val base = Offset(end.x - ux * 10f, end.y - uy * 10f)
                drawLine(routeColor, end, Offset(base.x - uy * 5f, base.y + ux * 5f), 3f, cap = StrokeCap.Round)
                drawLine(routeColor, end, Offset(base.x + uy * 5f, base.y - ux * 5f), 3f, cap = StrokeCap.Round)
            }
        }

        if (intersection.type == ru.trafficsignalstudio.model.IntersectionType.ROUNDABOUT) {
            val radius = max(junctionHalf * 0.82f, 34f)
            drawCircle(Color(0xFF59616A), radius = radius + 17f, center = center)
            drawCircle(Color(0xFFE8EEE8), radius = max(radius - 15f, 2f), center = center)
            drawCircle(Color.White, radius = radius + 1f, center = center,
                style = Stroke(width = 2f, pathEffect = dash))
        } else if (intersection.roadSegments.isEmpty()) {
            drawRect(Color(0xFF59616A),
                topLeft = Offset(center.x - junctionHalf, center.y - junctionHalf),
                size = Size(junctionHalf * 2f, junctionHalf * 2f))
        }

        if (pendingPoints.isNotEmpty()) {
            val draft = Path().apply {
                val first = pendingPoints.first()
                moveTo(center.x + first.xMeters.toFloat() * scale,
                    center.y - first.yMeters.toFloat() * scale)
                pendingPoints.drop(1).forEach { point ->
                    lineTo(center.x + point.xMeters.toFloat() * scale,
                        center.y - point.yMeters.toFloat() * scale)
                }
            }
            drawPath(draft, Color(0xFF1976D2),
                style = Stroke(width = 4f, cap = StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    pathEffect = if (drawingTool == DrawingTool.ARC) dash else null))
            pendingPoints.forEach { point ->
                drawCircle(Color(0xFF1976D2), radius = 6f,
                    center = Offset(center.x + point.xMeters.toFloat() * scale,
                        center.y - point.yMeters.toFloat() * scale))
            }
        }

        // A small center marker is retained for orientation while editing.
        if (editMode && selectedSegmentId != null) {
            intersection.roadSegments.firstOrNull { it.id == selectedSegmentId }?.centerline?.forEach { point ->
                drawCircle(Color(0xFFFF9800), radius = 8f,
                    center = Offset(center.x + point.xMeters.toFloat() * scale,
                        center.y - point.yMeters.toFloat() * scale))
            }
        }
        // One shared world-to-screen conversion: all geometry is authored in metres.
        // The ruler therefore remains tied to the same scale as lane widths and vehicle bodies.
        val rulerStart = Offset(16f, size.height - 18f)
        val rulerEnd = Offset(rulerStart.x + 5f * scale, rulerStart.y)
        drawLine(Color.White, rulerStart, rulerEnd, strokeWidth = 4f)
        drawLine(Color(0xFF263238), rulerStart, rulerEnd, strokeWidth = 1.5f)
        drawLine(Color.White, Offset(rulerStart.x, rulerStart.y - 5f), Offset(rulerStart.x, rulerStart.y + 5f), strokeWidth = 2f)
        drawLine(Color.White, Offset(rulerEnd.x, rulerEnd.y - 5f), Offset(rulerEnd.x, rulerEnd.y + 5f), strokeWidth = 2f)
        drawCircle(Color(0xFF20262D), radius = 4f, center = center, style = Stroke(width = 2f))
    }
}


private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVehicleGlyph(
    center: Offset,
    headingDegrees: Float,
    pixelsPerMeter: Float,
    model: VehicleModel,
    articulationAngleDegrees: Float = 0f
) {
    // Keep the model proportions intact while limiting its screen footprint.
    val rawLength = model.lengthMeters.toFloat() * pixelsPerMeter
    val fit = min(1f, 76f / rawLength.coerceAtLeast(1f))
    val length = (rawLength * fit).coerceAtLeast(14f)
    val width = (model.widthMeters.toFloat() * pixelsPerMeter * fit).coerceIn(7f, 29f)
    val left = center.x - width / 2f
    val top = center.y - length / 2f
    val bodyColor = when (model.vehicleClass) {
        VehicleClass.PASSENGER_CAR -> Color(0xFF1565C0)
        VehicleClass.LIGHT_COMMERCIAL -> Color(0xFF00897B)
        VehicleClass.RIGID_TRUCK, VehicleClass.ARTICULATED_TRUCK -> Color(0xFF8D6E63)
        VehicleClass.CITY_BUS, VehicleClass.ARTICULATED_BUS -> Color(0xFFF9A825)
        VehicleClass.TROLLEYBUS, VehicleClass.ARTICULATED_TROLLEYBUS -> Color(0xFF2E7D32)
        VehicleClass.MOTORCYCLE -> Color(0xFF6A1B9A)
        VehicleClass.BICYCLE -> Color(0xFF455A64)
    }
    val articulated = model.articulationPointFromFrontMeters
    val jointDistance = articulated?.let { (it.toFloat() / model.lengthMeters.toFloat() * length).coerceIn(length * 0.25f, length * 0.78f) }

    fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBodySection(
        sectionTop: Float,
        sectionLength: Float,
        sectionColor: Color,
        frontSection: Boolean,
        rearSection: Boolean
    ) {
        if (sectionLength <= 1f) return
        val corner = androidx.compose.ui.geometry.CornerRadius(width * 0.2f)
        drawRoundRect(sectionColor, Offset(left, sectionTop), Size(width, sectionLength), corner)
        // Windscreen / passenger windows are separate surfaces, not a single generic stripe.
        if (frontSection) {
            drawRoundRect(Color(0xFF172B3A), Offset(left + width * 0.15f, sectionTop + sectionLength * 0.07f),
                Size(width * 0.70f, sectionLength * 0.15f),
                androidx.compose.ui.geometry.CornerRadius(width * 0.1f))
            if (model.vehicleClass == VehicleClass.PASSENGER_CAR) {
                drawRoundRect(Color(0xFF90CAF9), Offset(left + width * 0.19f, sectionTop + sectionLength * 0.28f),
                    Size(width * 0.62f, sectionLength * 0.18f),
                    androidx.compose.ui.geometry.CornerRadius(width * 0.08f))
                drawRoundRect(Color(0xFF263238), Offset(left + width * 0.2f, sectionTop + sectionLength * 0.55f),
                    Size(width * 0.6f, sectionLength * 0.2f),
                    androidx.compose.ui.geometry.CornerRadius(width * 0.07f))
            } else {
                drawRoundRect(Color(0xFFB3E5FC), Offset(left + width * 0.14f, sectionTop + sectionLength * 0.3f),
                    Size(width * 0.72f, sectionLength * 0.16f),
                    androidx.compose.ui.geometry.CornerRadius(width * 0.06f))
            }
        }
        if (rearSection) {
            val windowCount = if (model.vehicleClass == VehicleClass.ARTICULATED_TRUCK) 1 else 3
            for (index in 0 until windowCount) {
                val segment = sectionLength * 0.52f / windowCount
                val windowTop = sectionTop + sectionLength * 0.12f + index * segment
                drawRoundRect(Color(0xFF263238).copy(alpha = 0.82f),
                    Offset(left + width * 0.13f, windowTop),
                    Size(width * 0.74f, segment * 0.58f),
                    androidx.compose.ui.geometry.CornerRadius(width * 0.05f))
            }
        }
        // Axles and tyres are visible at both sides of the body.
        val wheelY = listOf(sectionTop + sectionLength * 0.22f, sectionTop + sectionLength * 0.78f)
        wheelY.forEach { y ->
            val wheelHeight = (sectionLength * 0.095f).coerceIn(2f, 6f)
            drawRoundRect(Color(0xFF101820), Offset(left - width * 0.07f, y - wheelHeight / 2f),
                Size(width * 0.16f, wheelHeight), androidx.compose.ui.geometry.CornerRadius(wheelHeight / 3f))
            drawRoundRect(Color(0xFF101820), Offset(left + width * 0.91f, y - wheelHeight / 2f),
                Size(width * 0.16f, wheelHeight), androidx.compose.ui.geometry.CornerRadius(wheelHeight / 3f))
        }
        if (frontSection) {
            val lampY = sectionTop + sectionLength * 0.025f
            drawCircle(Color(0xFFFFF59D), radius = (width * 0.09f).coerceAtLeast(1.2f), center = Offset(left + width * 0.22f, lampY))
            drawCircle(Color(0xFFFFF59D), radius = (width * 0.09f).coerceAtLeast(1.2f), center = Offset(left + width * 0.78f, lampY))
        }
        if (rearSection) {
            val lampY = sectionTop + sectionLength * 0.97f
            drawCircle(Color(0xFFE53935), radius = (width * 0.08f).coerceAtLeast(1.1f), center = Offset(left + width * 0.22f, lampY))
            drawCircle(Color(0xFFE53935), radius = (width * 0.08f).coerceAtLeast(1.1f), center = Offset(left + width * 0.78f, lampY))
        }
        drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(left, sectionTop), Size(width, sectionLength),
            corner, style = Stroke(width = 1.1f))
    }

    rotate(headingDegrees, center) {
        if (jointDistance == null) {
            drawBodySection(top, length, bodyColor, frontSection = true, rearSection = true)
        } else {
            val jointY = top + jointDistance
            val rearLength = length - jointDistance
            // Front tractor / front bus section.
            drawBodySection(top, jointDistance, bodyColor, frontSection = true, rearSection = false)
            // The rear module rotates around the articulation pivot, exposing the bend angle.
            rotate(articulationAngleDegrees.coerceIn(-35f, 35f), Offset(center.x, jointY)) {
                drawBodySection(jointY, rearLength, bodyColor.copy(alpha = 0.94f),
                    frontSection = false, rearSection = true)
            }
            drawCircle(Color(0xFFFFEB3B), radius = (width * 0.17f).coerceAtLeast(2f),
                center = Offset(center.x, jointY))
            drawCircle(Color(0xFF263238), radius = (width * 0.07f).coerceAtLeast(1f),
                center = Offset(center.x, jointY))
        }
        // Distinct body details for powered two-wheelers.
        if (model.vehicleClass == VehicleClass.MOTORCYCLE || model.vehicleClass == VehicleClass.BICYCLE) {
            drawLine(Color(0xFFCFD8DC), Offset(center.x, top + length * 0.2f),
                Offset(center.x, top + length * 0.82f), width * 0.12f, cap = StrokeCap.Round)
            drawCircle(Color(0xFFCFD8DC), radius = width * 0.22f, center = Offset(center.x, top + length * 0.12f),
                style = Stroke(width = 1.3f))
            drawCircle(Color(0xFFCFD8DC), radius = width * 0.22f, center = Offset(center.x, top + length * 0.88f),
                style = Stroke(width = 1.3f))
        }
        if (model.vehicleClass == VehicleClass.TROLLEYBUS || model.vehicleClass == VehicleClass.ARTICULATED_TROLLEYBUS) {
            drawLine(Color(0xFF263238), Offset(center.x - width * 0.18f, top + length * 0.1f),
                Offset(center.x - width * 0.32f, top - length * 0.12f), 1.2f)
            drawLine(Color(0xFF263238), Offset(center.x + width * 0.18f, top + length * 0.1f),
                Offset(center.x + width * 0.32f, top - length * 0.12f), 1.2f)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMovementArrow(
    x: Float,
    y: Float,
    movement: MovementKind,
    color: Color,
    size: Float
) {
    val half = size / 2f
    val path = Path()
    when (movement) {
        MovementKind.THROUGH -> {
            path.moveTo(x, y + half)
            path.lineTo(x, y - half)
            drawPath(path, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
            drawLine(color, Offset(x, y - half), Offset(x - half * 0.48f, y - half * 0.25f), 2.5f, cap = StrokeCap.Round)
            drawLine(color, Offset(x, y - half), Offset(x + half * 0.48f, y - half * 0.25f), 2.5f, cap = StrokeCap.Round)
        }
        MovementKind.LEFT -> {
            path.moveTo(x, y + half)
            path.cubicTo(x, y, x - half, y, x - half, y - half * 0.15f)
            drawPath(path, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
            drawLine(color, Offset(x - half, y - half * 0.15f), Offset(x - half * 0.72f, y + half * 0.22f), 2.5f, cap = StrokeCap.Round)
            drawLine(color, Offset(x - half, y - half * 0.15f), Offset(x - half * 0.28f, y - half * 0.15f), 2.5f, cap = StrokeCap.Round)
        }
        MovementKind.RIGHT -> {
            path.moveTo(x, y + half)
            path.cubicTo(x, y, x + half, y, x + half, y - half * 0.15f)
            drawPath(path, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
            drawLine(color, Offset(x + half, y - half * 0.15f), Offset(x + half * 0.28f, y - half * 0.15f), 2.5f, cap = StrokeCap.Round)
            drawLine(color, Offset(x + half, y - half * 0.15f), Offset(x + half * 0.72f, y + half * 0.22f), 2.5f, cap = StrokeCap.Round)
        }
        MovementKind.U_TURN -> {
            path.moveTo(x, y + half)
            path.cubicTo(x - half * 0.9f, y + half * 0.1f, x - half * 0.9f, y - half, x, y - half * 0.45f)
            drawPath(path, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
            drawLine(color, Offset(x, y - half * 0.45f), Offset(x - half * 0.32f, y - half * 0.1f), 2.5f, cap = StrokeCap.Round)
            drawLine(color, Offset(x, y - half * 0.45f), Offset(x + half * 0.32f, y - half * 0.1f), 2.5f, cap = StrokeCap.Round)
        }
        MovementKind.TRANSIT_ONLY -> Unit
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDirectionArrow(
    center: Offset,
    directionX: Float,
    directionY: Float,
    wing: Float
) {
    val length = kotlin.math.hypot(directionX, directionY).coerceAtLeast(0.001f)
    val ux = directionX / length
    val uy = directionY / length
    val tip = Offset(center.x + ux * 7f, center.y + uy * 7f)
    val tail = Offset(center.x - ux * 7f, center.y - uy * 7f)
    val base = Offset(center.x + ux * 1f, center.y + uy * 1f)
    drawLine(Color.White, tail, tip, 2.5f, cap = StrokeCap.Round)
    drawLine(Color.White, tip, Offset(base.x - uy * wing, base.y + ux * wing), 2.5f, cap = StrokeCap.Round)
    drawLine(Color.White, tip, Offset(base.x + uy * wing, base.y - ux * wing), 2.5f, cap = StrokeCap.Round)
}

private fun offsetRoadPolyline(points: List<Point2D>, offsetMeters: Double): List<Point2D> {
    if (points.size < 2 || kotlin.math.abs(offsetMeters) < 1e-8) return points
    return points.mapIndexed { index, point ->
        val before = points[(index - 1).coerceAtLeast(0)]
        val after = points[(index + 1).coerceAtMost(points.lastIndex)]
        val dx = after.xMeters - before.xMeters
        val dy = after.yMeters - before.yMeters
        val length = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-8)
        Point2D(point.xMeters - dy / length * offsetMeters, point.yMeters + dx / length * offsetMeters)
    }
}

private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax; val dy = by - ay
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared <= 0.0001f) return kotlin.math.hypot(px - ax, py - ay)
    val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
    return kotlin.math.hypot(px - (ax + t * dx), py - (ay + t * dy))
}


private fun snapToNetwork(point: Point2D, intersection: Intersection): Point2D {
    val candidates = intersection.junctionNodes.map { it.point } +
        intersection.roadSegments.flatMap { listOfNotNull(it.centerline.firstOrNull(), it.centerline.lastOrNull()) }
    return candidates.minByOrNull { kotlin.math.hypot(it.xMeters - point.xMeters, it.yMeters - point.yMeters) }
        ?.takeIf { kotlin.math.hypot(it.xMeters - point.xMeters, it.yMeters - point.yMeters) <= 2.0 } ?: point
}

private fun rebuildJunctionNodes(segments: List<RoadSegment>): List<RoadJunctionNode> {
    data class Endpoint(val point: Point2D, val segmentId: String)
    val endpoints = segments.flatMap { segment ->
        listOfNotNull(segment.centerline.firstOrNull(), segment.centerline.lastOrNull()).map { Endpoint(it, segment.id) }
    }
    val groups = mutableListOf<MutableList<Endpoint>>()
    endpoints.forEach { endpoint ->
        val group = groups.firstOrNull { g -> g.any {
            kotlin.math.hypot(it.point.xMeters - endpoint.point.xMeters, it.point.yMeters - endpoint.point.yMeters) <= 1.0
        } }
        if (group == null) groups.add(mutableListOf(endpoint)) else group.add(endpoint)
    }
    return groups.filter { it.map(Endpoint::segmentId).distinct().size >= 2 }.map { group ->
        RoadJunctionNode(
            point = Point2D(group.map { it.point.xMeters }.average(), group.map { it.point.yMeters }.average()),
            connectedSegmentIds = group.map { it.segmentId }.toSet()
        )
    }
}

private fun estimateArcRadius(points: List<Point2D>): Double {
    if (points.size < 3) return 10.0
    val a = points.first(); val b = points[1]; val c = points.last()
    val ab = kotlin.math.hypot(b.xMeters - a.xMeters, b.yMeters - a.yMeters)
    val bc = kotlin.math.hypot(c.xMeters - b.xMeters, c.yMeters - b.yMeters)
    val ca = kotlin.math.hypot(a.xMeters - c.xMeters, a.yMeters - c.yMeters)
    val cross = kotlin.math.abs((b.xMeters - a.xMeters) * (c.yMeters - a.yMeters) - (b.yMeters - a.yMeters) * (c.xMeters - a.xMeters))
    return if (cross < 1e-6) 1000.0 else (ab * bc * ca / (2.0 * cross)).coerceAtLeast(1.0)
}

private fun adjustArcRadius(segment: RoadSegment, requestedRadius: Double): RoadSegment {
    if (segment.centerline.size < 3) return segment
    val radius = requestedRadius.coerceIn(2.0, 1000.0)
    val points = segment.centerline.toMutableList()
    val a = points.first(); val control = points[1]; val end = points.last()
    val midX = (a.xMeters + end.xMeters) / 2.0
    val midY = (a.yMeters + end.yMeters) / 2.0
    val oldRadius = segment.arcRadiusMeters ?: estimateArcRadius(points)
    val factor = (oldRadius / radius).coerceIn(0.05, 20.0)
    points[1] = Point2D(midX + (control.xMeters - midX) * factor, midY + (control.yMeters - midY) * factor)
    return segment.copy(centerline = points, arcRadiusMeters = radius)
}
