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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import ru.trafficsignalstudio.geometry.IntersectionGeometryEditor
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.MovementKind
import ru.trafficsignalstudio.model.LaneFlowDirection
import ru.trafficsignalstudio.model.ProjectDocument
import ru.trafficsignalstudio.model.Point2D
import ru.trafficsignalstudio.model.RoadSegment
import ru.trafficsignalstudio.model.RoadSegmentKind
import ru.trafficsignalstudio.model.RoadJunctionNode
import ru.trafficsignalstudio.model.SafetyIsland
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
    onProjectChange: (ProjectDocument) -> Unit
) {
    var selectedApproachIndex by remember(intersection?.id) { mutableIntStateOf(0) }
    var drawingTool by remember(intersection?.id) { mutableStateOf(DrawingTool.LINE) }
    var editMode by remember(intersection?.id) { mutableStateOf(false) }
    var viewportZoom by remember(intersection?.id) { mutableStateOf(1f) }
    var viewportPan by remember(intersection?.id) { mutableStateOf(Offset.Zero) }
    var selectedRoadSegmentId by remember(intersection?.id) { mutableStateOf<String?>(null) }
    var islandDrawingMode by remember(intersection?.id) { mutableStateOf(false) }
    val pendingPoints = remember(intersection?.id) { mutableStateListOf<Point2D>() }
    val safeIndex = selectedApproachIndex.coerceIn(0, max(0, (intersection?.approaches?.size ?: 1) - 1))
    val selectedApproach = intersection?.approaches?.getOrNull(safeIndex)
    val validationErrors = intersection?.let(IntersectionGeometryEditor::validate).orEmpty()

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
            OutlinedButton(onClick = onBack) { Text("Назад") }
        }

        // Keep a real, visible drawing surface on short phone screens.
        // The surrounding screen scrolls instead of squeezing this weighted card to zero height.
        Card(modifier = Modifier.fillMaxWidth().height(320.dp)) {
            Box(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                if (intersection != null) {
                    IntersectionGeometryPreview(
                        modifier = Modifier.fillMaxSize(),
                        intersection = intersection,
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
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Редактор геометрии", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!editMode) Button(onClick = { editMode = true; pendingPoints.clear(); islandDrawingMode = false }) { Text("Редактировать") }
                        else Button(onClick = { editMode = false }) { Text("Рисовать") }
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
                                val v = selectedSegment.copy(laneCount = (selectedSegment.laneCount - 1).coerceAtLeast(1))
                                updateIntersection(intersection.copy(roadSegments = intersection.roadSegments.map { if (it.id == selectedSegment.id) v else it }))
                            }, enabled = selectedSegment.laneCount > 1) { Text("− полоса") }
                            OutlinedButton(onClick = {
                                val v = selectedSegment.copy(laneCount = (selectedSegment.laneCount + 1).coerceAtMost(16))
                                updateIntersection(intersection.copy(roadSegments = intersection.roadSegments.map { if (it.id == selectedSegment.id) v else it }))
                            }, enabled = selectedSegment.laneCount < 16) { Text("+ полоса") }
                            OutlinedButton(onClick = {
                                val v = selectedSegment.copy(widthMeters = (selectedSegment.widthMeters - 0.5).coerceAtLeast(2.5))
                                updateIntersection(intersection.copy(roadSegments = intersection.roadSegments.map { if (it.id == selectedSegment.id) v else it }))
                            }, enabled = selectedSegment.widthMeters > 2.5) { Text("− ширина") }
                            OutlinedButton(onClick = {
                                val v = selectedSegment.copy(widthMeters = (selectedSegment.widthMeters + 0.5).coerceAtMost(60.0))
                                updateIntersection(intersection.copy(roadSegments = intersection.roadSegments.map { if (it.id == selectedSegment.id) v else it }))
                            }, enabled = selectedSegment.widthMeters < 60.0) { Text("+ ширина") }
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
                val factor = (minOf(size.width, size.height) * 0.28f / 14.0f * zoom * 0.35f).coerceAtLeast(0.0001f)
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
                    val factor = minOf(size.width, size.height) * 0.28f / 14.0f * zoom * 0.35f
                    val center = Offset(size.width / 2f + pan.x, size.height / 2f + pan.y)
                    draggedIndex = segment?.centerline?.indices?.minByOrNull { index ->
                        val p = segment.centerline[index]
                        (Offset(center.x + p.xMeters.toFloat() * factor, center.y - p.yMeters.toFloat() * factor) - start).getDistance()
                    } ?: -1
                }, onDrag = { change, _ ->
                    val segment = intersection.roadSegments.firstOrNull { it.id == selectedSegmentId }
                    if (segment != null && draggedIndex in segment.centerline.indices) {
                        val factor = (minOf(size.width, size.height) * 0.28f / 14.0f * zoom * 0.35f).coerceAtLeast(0.0001f)
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
                    moveTo(center.x + first.xMeters.toFloat() * scale * 0.35f, center.y - first.yMeters.toFloat() * scale * 0.35f)
                    island.boundary.drop(1).forEach { p -> lineTo(center.x + p.xMeters.toFloat() * scale * 0.35f, center.y - p.yMeters.toFloat() * scale * 0.35f) }
                    close()
                }
                drawPath(islandPath, Color(0xFFB7C9A5))
                drawPath(islandPath, Color(0xFF4F6B45), style = Stroke(width = 2f))
            }
        }
        intersection.junctionNodes.forEach { node ->
            drawCircle(Color(0xFFFFC107), 5f, Offset(center.x + node.point.xMeters.toFloat() * scale * 0.35f, center.y - node.point.yMeters.toFloat() * scale * 0.35f))
        }
        intersection.roadSegments.sortedBy { it.elevationLevel }.forEach { segment ->
            if (segment.centerline.size >= 2) {
                val screenPoints = segment.centerline.map { point ->
                    Offset(center.x + point.xMeters.toFloat() * scale * 0.35f,
                        center.y - point.yMeters.toFloat() * scale * 0.35f)
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
                val width = (segment.widthMeters.toFloat() * scale * 0.35f).coerceAtLeast(10f)
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
                if (segment.laneCount > 1) {
                    drawPath(path, Color.White.copy(alpha = 0.8f),
                        style = Stroke(width = 1.5f, pathEffect = dash, cap = StrokeCap.Round))
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
                        drawLine(Color.White, Offset(laneCenterX, tailY), Offset(laneCenterX, tipY), 3f, cap = StrokeCap.Round)
                        val wing = min(6f, lanePixelWidth * 0.22f)
                        drawLine(Color.White, Offset(laneCenterX, tipY), Offset(laneCenterX - wing, headBaseY), 3f, cap = StrokeCap.Round)
                        drawLine(Color.White, Offset(laneCenterX, tipY), Offset(laneCenterX + wing, headBaseY), 3f, cap = StrokeCap.Round)
                    }
                    laneOffset += lane.widthMeters.toFloat() * scale
                }
                drawLine(Color.White,
                    Offset(center.x - half, center.y - junctionHalf - 16f),
                    Offset(center.x + half, center.y - junctionHalf - 16f), 4f)
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
                moveTo(center.x + first.xMeters.toFloat() * scale * 0.35f,
                    center.y - first.yMeters.toFloat() * scale * 0.35f)
                pendingPoints.drop(1).forEach { point ->
                    lineTo(center.x + point.xMeters.toFloat() * scale * 0.35f,
                        center.y - point.yMeters.toFloat() * scale * 0.35f)
                }
            }
            drawPath(draft, Color(0xFF1976D2),
                style = Stroke(width = 4f, cap = StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    pathEffect = if (drawingTool == DrawingTool.ARC) dash else null))
            pendingPoints.forEach { point ->
                drawCircle(Color(0xFF1976D2), radius = 6f,
                    center = Offset(center.x + point.xMeters.toFloat() * scale * 0.35f,
                        center.y - point.yMeters.toFloat() * scale * 0.35f))
            }
        }

        // A small center marker is retained for orientation while editing.
        if (editMode && selectedSegmentId != null) {
            intersection.roadSegments.firstOrNull { it.id == selectedSegmentId }?.centerline?.forEach { point ->
                drawCircle(Color(0xFFFF9800), radius = 8f,
                    center = Offset(center.x + point.xMeters.toFloat() * scale * 0.35f,
                        center.y - point.yMeters.toFloat() * scale * 0.35f))
            }
        }
        drawCircle(Color(0xFF20262D), radius = 4f, center = center, style = Stroke(width = 2f))
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
