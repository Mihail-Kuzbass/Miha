package ru.trafficsignalstudio.mobile

import android.os.Bundle
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    var workspaceMode by remember { mutableStateOf<WorkspaceMode?>(null) }
    var selectedIntersectionId by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    suspend fun refreshProjects() {
        projects = withContext(Dispatchers.IO) { store.loadAll() }
        isLoading = false
    }

    LaunchedEffect(Unit) { refreshProjects() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val project = selectedProject
        if (project != null) {
            val activeIntersection = project.network.intersections.firstOrNull { it.id == selectedIntersectionId }
                ?: project.network.intersections.firstOrNull()
            IntersectionEditorScreen(
                project = project,
                intersection = activeIntersection,
                onBack = { selectedProject = null; selectedIntersectionId = null },
                onSelectIntersection = { selectedIntersectionId = it },
                onAddIntersection = {
                    val next = IntersectionTemplates.fourLeg().copy(name = "Перекрёсток " + (project.network.intersections.size + 1))
                    selectedProject = project.copy(updatedAtEpochMs = System.currentTimeMillis(),
                        network = project.network.copy(intersections = project.network.intersections + next))
                    selectedIntersectionId = next.id
                },
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
            if (workspaceMode == null) {
                MainMenuScreen(
                    onSingle = { workspaceMode = WorkspaceMode.SINGLE },
                    onNetwork = { workspaceMode = WorkspaceMode.NETWORK },
                    onExit = { (context as? Activity)?.finish() }
                )
            } else {
            HomeScreen(
                mode = workspaceMode!!,
                projects = projects,
                isLoading = isLoading,
                onBack = { workspaceMode = null },
                onCreate = { selectedTemplate ->
                    scope.launch {
                        val title = if (workspaceMode == WorkspaceMode.NETWORK) "Новая сеть перекрёстков" else "Новый перекрёсток"
                        val newProject = ProjectDocument.newProject(title).copy(
                            network = RoadNetwork(intersections = listOf(selectedTemplate))
                        )
                        withContext(Dispatchers.IO) { store.save(newProject) }
                        refreshProjects()
                        selectedProject = newProject
                        selectedIntersectionId = selectedTemplate.id
                    }
                },
                onOpen = { selectedProject = it; selectedIntersectionId = it.network.intersections.firstOrNull()?.id }
            )
            }
        }
    }
}

private enum class WorkspaceMode { SINGLE, NETWORK }

@Composable
private fun MainMenuScreen(onSingle: () -> Unit, onNetwork: () -> Unit, onExit: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("TrafficSignalStudio", style = MaterialTheme.typography.headlineLarge)
        Text("Проектирование и микромоделирование дорожного движения", modifier = Modifier.padding(top = 8.dp, bottom = 28.dp))
        Button(onClick = onSingle, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Text("Одиночный перекрёсток") }
        Button(onClick = onNetwork, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Text("Сеть перекрёстков") }
        OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) { Text("Выход из программы") }
        Text("Версия 0.2.0", modifier = Modifier.padding(top = 28.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HomeScreen(
    mode: WorkspaceMode,
    projects: List<ProjectDocument>,
    isLoading: Boolean,
    onBack: () -> Unit,
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
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (mode == WorkspaceMode.SINGLE) "Одиночный перекрёсток" else "Сеть перекрёстков", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("В меню") }
        }
        Text("Мобильная инженерная среда проектирования и моделирования")
        Button(onClick = { showTemplatePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (mode == WorkspaceMode.SINGLE) "Создать перекрёсток" else "Создать сеть")
        }
        Text("Последние проекты", style = MaterialTheme.typography.titleLarge)
        if (isLoading) {
            Text("Загрузка проектов…")
        } else if (projects.isEmpty()) {
            Text("Проектов пока нет. Создайте первый проект.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
