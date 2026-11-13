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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.ProjectDocument

@Composable
fun IntersectionEditorScreen(
    project: ProjectDocument,
    intersection: Intersection?,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Редактор перекрёстка", style = MaterialTheme.typography.titleLarge)
                Text(project.name, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onBack) { Text("Назад") }
        }

        Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                IntersectionPreview(
                    modifier = Modifier.fillMaxSize(),
                    approachCount = intersection?.approaches?.size ?: 4
                )
                Text(
                    text = intersection?.name ?: "Пустая схема — выберите шаблон",
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Свойства объекта", style = MaterialTheme.typography.titleMedium)
                Text("Подходы: ${intersection?.approaches?.size ?: 0}")
                Text("Единицы: метры · Схема: 2D сверху")
                Text("Эта версия показывает основу редактора; интерактивное изменение геометрии добавляется следующим шагом.")
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Отмена") }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Сохранить") }
        }
    }
}

@Composable
private fun IntersectionPreview(
    modifier: Modifier = Modifier,
    approachCount: Int
) {
    Canvas(modifier = modifier.background(Color(0xFFE8EEE8), RoundedCornerShape(12.dp))) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val roadWidth = size.minDimension * 0.34f
        val half = roadWidth / 2f

        drawRect(Color(0xFF59616A), topLeft = Offset(center.x - half, 0f), size = androidx.compose.ui.geometry.Size(roadWidth, size.height))
        drawRect(Color(0xFF59616A), topLeft = Offset(0f, center.y - half), size = androidx.compose.ui.geometry.Size(size.width, roadWidth))

        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))
        drawLine(Color.White, Offset(center.x, 0f), Offset(center.x, center.y - half), strokeWidth = 3f, pathEffect = dash)
        drawLine(Color.White, Offset(center.x, center.y + half), Offset(center.x, size.height), strokeWidth = 3f, pathEffect = dash)
        drawLine(Color.White, Offset(0f, center.y), Offset(center.x - half, center.y), strokeWidth = 3f, pathEffect = dash)
        drawLine(Color.White, Offset(center.x + half, center.y), Offset(size.width, center.y), strokeWidth = 3f, pathEffect = dash)

        val stopOffset = half + 18f
        drawLine(Color.White, Offset(center.x - half, center.y - stopOffset), Offset(center.x + half, center.y - stopOffset), strokeWidth = 5f)
        drawLine(Color.White, Offset(center.x - half, center.y + stopOffset), Offset(center.x + half, center.y + stopOffset), strokeWidth = 5f)
        drawLine(Color.White, Offset(center.x - stopOffset, center.y - half), Offset(center.x - stopOffset, center.y + half), strokeWidth = 5f)
        drawLine(Color.White, Offset(center.x + stopOffset, center.y - half), Offset(center.x + stopOffset, center.y + half), strokeWidth = 5f)

        if (approachCount == 3) {
            drawRect(Color(0xFFE8EEE8), topLeft = Offset(center.x - half, 0f), size = androidx.compose.ui.geometry.Size(roadWidth, center.y - half))
        }
        drawCircle(Color(0xFF20262D), radius = 5f, center = center, style = Stroke(width = 2f))
    }
}
