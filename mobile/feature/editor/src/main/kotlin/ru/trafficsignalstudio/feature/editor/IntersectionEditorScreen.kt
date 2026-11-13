package ru.trafficsignalstudio.feature.editor

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ru.trafficsignalstudio.geometry.IntersectionGeometryEditor
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.ProjectDocument
import kotlin.math.max

@Composable
fun IntersectionEditorScreen(
    project: ProjectDocument,
    intersection: Intersection?,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onProjectChange: (ProjectDocument) -> Unit
) {
    var selectedApproachIndex by remember(intersection?.id) { mutableIntStateOf(0) }
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
                        intersection = intersection
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
    intersection: Intersection
) {
    Canvas(modifier = modifier.background(Color(0xFFE8EEE8), RoundedCornerShape(12.dp))) {
        val center = Offset(size.width / 2f, size.height / 2f)
        // Fixed scale keeps lane-count changes visible and gives custom layouts
        // a stable drawing coordinate system.
        val scale = size.minDimension * 0.28f / 14.0f
        val roadWidths = intersection.approaches.associate { approach ->
            approach.id to (approach.lanes.sumOf { it.widthMeters }.toFloat() * scale).coerceAtLeast(24f)
        }
        val junctionHalf = (roadWidths.values.maxOrNull() ?: 24f) / 2f
        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))

        // Draw independent centerline segments first. Their elevation level is
        // visual metadata; conflict analysis will use it in a later engine step.
        intersection.roadSegments.sortedBy { it.elevationLevel }.forEach { segment ->
            if (segment.centerline.size >= 2) {
                val path = Path().apply {
                    val first = segment.centerline.first()
                    moveTo(center.x + first.xMeters.toFloat() * scale * 0.35f,
                        center.y - first.yMeters.toFloat() * scale * 0.35f)
                    segment.centerline.drop(1).forEach { point ->
                        lineTo(center.x + point.xMeters.toFloat() * scale * 0.35f,
                            center.y - point.yMeters.toFloat() * scale * 0.35f)
                    }
                }
                val width = (segment.widthMeters.toFloat() * scale).coerceAtLeast(10f)
                if (segment.elevationLevel > 0) {
                    drawPath(path, Color(0xFFB7BEC5),
                        style = Stroke(width = width + 5f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
                drawPath(path, Color(0xFF59616A),
                    style = Stroke(width = width, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
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
            androidx.compose.ui.graphics.drawscope.rotate(heading.toFloat(), center) {
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
            val radius = junctionHalf * 0.82f
            drawCircle(Color(0xFF59616A), radius = radius + 17f, center = center)
            drawCircle(Color(0xFFE8EEE8), radius = radius - 15f, center = center)
            drawCircle(Color.White, radius = radius + 1f, center = center,
                style = Stroke(width = 2f, pathEffect = dash))
        } else if (intersection.roadSegments.isEmpty()) {
            drawRect(Color(0xFF59616A),
                topLeft = Offset(center.x - junctionHalf, center.y - junctionHalf),
                size = Size(junctionHalf * 2f, junctionHalf * 2f))
        }

        // A small center marker is retained for orientation while editing.
        drawCircle(Color(0xFF20262D), radius = 4f, center = center, style = Stroke(width = 2f))
    }
}
