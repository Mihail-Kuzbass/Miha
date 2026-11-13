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
import ru.trafficsignalstudio.model.ProjectDocument
import ru.trafficsignalstudio.model.Point2D
import ru.trafficsignalstudio.model.RoadSegment
import ru.trafficsignalstudio.model.RoadSegmentKind
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
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp),
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

        Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                if (intersection != null) {
                    IntersectionGeometryPreview(
                        modifier = Modifier.fillMaxSize(),
                        intersection = intersection,
                        pendingPoints = pendingPoints,
                        drawingTool = drawingTool,
                        onAddPoint = { point -> pendingPoints.add(point) },
                        zoom = viewportZoom, pan = viewportPan, editMode = editMode,
                        selectedSegmentId = selectedRoadSegmentId,
                        onViewportChange = { z, p -> viewportZoom = z; viewportPan = p },
                        onSelectSegment = { selectedRoadSegmentId = it },
                        onMovePoint = { segmentId, pointIndex, point ->
                            val current = intersection.roadSegments.firstOrNull { it.id == segmentId }
                            if (current != null && pointIndex in current.centerline.indices) {
                                val changed = current.copy(centerline = current.centerline.toMutableList().also { it[pointIndex] = point })
                                updateIntersection(intersection.copy(roadSegments = intersection.roadSegments.map { if (it.id == segmentId) changed else it }))
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
                        if (!editMode) Button(onClick = { editMode = true; pendingPoints.clear() }) { Text("Редактировать") }
                        else Button(onClick = { editMode = false }) { Text("Рисовать") }
                        OutlinedButton(onClick = { viewportZoom = 1f; viewportPan = Offset.Zero }) { Text("Сброс вида") }
                    }
                    if (!editMode) Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DrawingTool.values().forEach { tool ->
                            if (drawingTool == tool) {
                                Button(onClick = { drawingTool = tool; pendingPoints.clear() }) { Text(tool.title) }
                            } else {
                                OutlinedButton(onClick = { drawingTool = tool; pendingPoints.clear() }) { Text(tool.title) }
                            }
                        }
                    }
                    if (!editMode) Text("Точки: ${pendingPoints.size}. Нажимайте на поле, чтобы задать трассу.")
                    else Text("Коснитесь дороги для выбора. Перетаскивайте узлы; жест двумя пальцами — масштаб и панорамирование.")
                    if (!editMode) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { if (pendingPoints.isNotEmpty()) pendingPoints.removeAt(pendingPoints.lastIndex) },
                            enabled = pendingPoints.isNotEmpty()) { Text("Отменить точку") }
                        OutlinedButton(onClick = { pendingPoints.clear() }, enabled = pendingPoints.isNotEmpty()) {
                            Text("Очистить")
                        }
                        Button(onClick = {
                            if (pendingPoints.size >= 2) {
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
                                    kind = kind
                                )
                                updateIntersection(intersection.copy(
                                    type = ru.trafficsignalstudio.model.IntersectionType.CUSTOM,
                                    roadSegments = intersection.roadSegments + segment
                                ))
                                pendingPoints.clear()
                            }
                        }, enabled = pendingPoints.size >= 2) { Text("Завершить линию") }
                    }
                    if (!editMode) Text("Для дуги задайте 3 точки: начало, контрольную и конец. Мост рисуется на верхнем уровне.")
                    val selectedSegment = intersection.roadSegments.firstOrNull { it.id == selectedRoadSegmentId }
                    if (editMode && selectedSegment != null) {
                        Text("Выбрано: ${selectedSegment.name}", style = MaterialTheme.typography.titleSmall)
                        Text("Полос: ${selectedSegment.laneCount} · ширина: ${"%.2f".format(selectedSegment.widthMeters)} м")
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
                            updateIntersection(intersection.copy(roadSegments = intersection.roadSegments.filterNot { it.id == selectedSegment.id }))
                            selectedRoadSegmentId = null
                        }) { Text("Удалить дорогу") }
                    } else if (editMode) Text("Выберите дорожный сегмент касанием на поле.")
                }
            }
        }

        if (intersection != null && selectedApproach != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState()),
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
                    Text("Ширина полос (м)", style = MaterialTheme.typography.titleSmall)
                    selectedApproach.lanes.forEach { lane ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Полоса ${lane.index}: ${"%.2f".format(lane.widthMeters)} м")
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
                    }
                    Text("Размеры хранятся в метрах. Допустимая ширина полосы: 2,50–5,00 м.")
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
                val factor = (size.minDimension * 0.28f / 14.0f * zoom * 0.35f).coerceAtLeast(0.0001f)
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
                    val factor = size.minDimension * 0.28f / 14.0f * zoom * 0.35f
                    val center = Offset(size.width / 2f + pan.x, size.height / 2f + pan.y)
                    draggedIndex = segment?.centerline?.indices?.minByOrNull { index ->
                        val p = segment.centerline[index]
                        (Offset(center.x + p.xMeters.toFloat() * factor, center.y - p.yMeters.toFloat() * factor) - start).getDistance()
                    } ?: -1
                }, onDrag = { change, _ ->
                    val segment = intersection.roadSegments.firstOrNull { it.id == selectedSegmentId }
                    if (segment != null && draggedIndex in segment.centerline.indices) {
                        val factor = (size.minDimension * 0.28f / 14.0f * zoom * 0.35f).coerceAtLeast(0.0001f)
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
        val scale = size.minDimension * 0.28f / 14.0f * zoom
        val roadWidths = intersection.approaches.associate { approach ->
            approach.id to (approach.lanes.sumOf { it.widthMeters }.toFloat() * scale).coerceAtLeast(24f)
        }
        val junctionHalf = (roadWidths.values.maxOrNull() ?: 24f) / 2f
        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))

        // Draw independent centerline segments first. Their elevation level is
        // visual metadata; conflict analysis will use it in a later engine step.
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
