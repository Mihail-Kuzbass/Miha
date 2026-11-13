package ru.trafficsignalstudio.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.trafficsignalstudio.data.local.ProjectJsonStore
import ru.trafficsignalstudio.feature.editor.IntersectionEditorScreen
import ru.trafficsignalstudio.geometry.IntersectionTemplates
import ru.trafficsignalstudio.model.ProjectDocument
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.RoadNetwork

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TrafficSignalStudioApp() } }
    }
}

@Composable
private fun TrafficSignalStudioApp() {
    val context = LocalContext.current
    val store = remember { ProjectJsonStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf(emptyList<ProjectDocument>()) }
    var selectedProject by remember { mutableStateOf<ProjectDocument?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    suspend fun refreshProjects() {
        projects = withContext(Dispatchers.IO) { store.loadAll() }
        isLoading = false
    }

    LaunchedEffect(Unit) { refreshProjects() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val project = selectedProject
        if (project != null) {
            IntersectionEditorScreen(
                project = project,
                intersection = project.network.intersections.firstOrNull(),
                onBack = { selectedProject = null },
                onProjectChange = { selectedProject = it },
                onSave = {
                    scope.launch {
                        val current = selectedProject ?: return@launch
                        withContext(Dispatchers.IO) { store.save(current) }
                        refreshProjects()
                        selectedProject = null
                    }
                }
            )
        } else {
            HomeScreen(
                projects = projects,
                isLoading = isLoading,
                onCreate = { selectedTemplate ->
                    scope.launch {
                        val newProject = ProjectDocument.newProject("Новый проект").copy(
                            network = RoadNetwork(
                                intersections = listOf(selectedTemplate)
                            )
                        )
                        withContext(Dispatchers.IO) { store.save(newProject) }
                        refreshProjects()
                        selectedProject = newProject
                    }
                },
                onOpen = { selectedProject = it }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    projects: List<ProjectDocument>,
    isLoading: Boolean,
    onCreate: (Intersection) -> Unit,
    onOpen: (ProjectDocument) -> Unit
) {
    var showTemplatePicker by remember { mutableStateOf(false) }
    val templates = listOf(
        "Четырёхсторонний перекрёсток" to { IntersectionTemplates.fourLeg() },
        "Т-образный перекрёсток" to { IntersectionTemplates.tJunction() },
        "Y-образный перекрёсток" to { IntersectionTemplates.yJunction() },
        "Пятисторонний перекрёсток" to { IntersectionTemplates.fiveLeg() },
        "Шестисторонний перекрёсток" to { IntersectionTemplates.sixLeg() },
        "Круговое движение" to { IntersectionTemplates.roundabout() },
        "Ромбовидная развязка" to { IntersectionTemplates.diamondInterchange() },
        "Клеверная развязка" to { IntersectionTemplates.cloverleafInterchange() }
    )
    if (showTemplatePicker) {
        AlertDialog(
            onDismissRequest = { showTemplatePicker = false },
            title = { Text("Выберите тип дорожного узла") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    templates.forEach { (title, factory) ->
                        TextButton(onClick = {
                            showTemplatePicker = false
                            onCreate(factory())
                        }, modifier = Modifier.fillMaxWidth()) { Text(title) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTemplatePicker = false }) { Text("Отмена") }
            }
        )
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("TrafficSignalStudio", style = MaterialTheme.typography.headlineMedium)
        Text("Мобильная инженерная среда проектирования и моделирования")
        Button(onClick = { showTemplatePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Создать проект")
        }
        Text("Последние проекты", style = MaterialTheme.typography.titleLarge)
        if (isLoading) {
            Text("Загрузка проектов…")
        } else if (projects.isEmpty()) {
            Text("Проектов пока нет. Создайте первый проект.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(projects, key = { it.id }) { project ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(project.name, style = MaterialTheme.typography.titleMedium)
                                Text("Перекрёстков: ${project.network.intersections.size}")
                            }
                            OutlinedButton(onClick = { onOpen(project) }) { Text("Открыть") }
                        }
                    }
                }
            }
        }
        Text("Версия 0.2.0 · Работа без обязательного подключения к интернету")
    }
}
