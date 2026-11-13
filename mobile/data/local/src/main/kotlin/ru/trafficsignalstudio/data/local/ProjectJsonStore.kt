package ru.trafficsignalstudio.data.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.trafficsignalstudio.model.Approach
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.IntersectionType
import ru.trafficsignalstudio.model.Lane
import ru.trafficsignalstudio.model.MovementKind
import ru.trafficsignalstudio.model.Point2D
import ru.trafficsignalstudio.model.ProjectDocument
import ru.trafficsignalstudio.model.RoadNetwork
import java.io.File

class ProjectJsonStore(context: Context) {
    private val directory = File(context.filesDir, "projects").apply { mkdirs() }

    fun save(project: ProjectDocument) {
        val file = File(directory, "${project.id}.tss.json")
        file.writeText(encode(project).toString(2), Charsets.UTF_8)
    }

    fun loadAll(): List<ProjectDocument> =
        directory.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { decode(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull()
            }
            .sortedByDescending { it.updatedAtEpochMs }

    fun delete(projectId: String) {
        File(directory, "${projectId}.tss.json").delete()
    }

    private fun encode(project: ProjectDocument): JSONObject = JSONObject().apply {
        put("schemaVersion", project.schemaVersion)
        put("id", project.id)
        put("name", project.name)
        put("createdAtEpochMs", project.createdAtEpochMs)
        put("updatedAtEpochMs", System.currentTimeMillis())
        put("network", JSONObject().put(
            "intersections",
            JSONArray().apply {
                project.network.intersections.forEach { intersection ->
                    put(JSONObject().apply {
                        put("id", intersection.id)
                        put("name", intersection.name)
                        put("type", intersection.type.name)
                        put("centerX", intersection.center.xMeters)
                        put("centerY", intersection.center.yMeters)
                        put("approaches", JSONArray().apply {
                            intersection.approaches.forEach { approach ->
                                put(JSONObject().apply {
                                    put("id", approach.id)
                                    put("name", approach.name)
                                    put("headingDegrees", approach.headingDegrees)
                                    put("lanes", JSONArray().apply {
                                        approach.lanes.forEach { lane ->
                                            put(JSONObject().apply {
                                                put("id", lane.id)
                                                put("index", lane.index)
                                                put("widthMeters", lane.widthMeters)
                                                put("allowedMovements", JSONArray(lane.allowedMovements.map { it.name }))
                                            })
                                        }
                                    })
                                })
                            }
                        })
                    })
                }
            }
        ))
    }

    private fun decode(json: JSONObject): ProjectDocument {
        require(json.optInt("schemaVersion") <= ProjectDocument.CURRENT_SCHEMA_VERSION) {
            "Версия проекта новее поддерживаемой"
        }
        val networkJson = json.optJSONObject("network") ?: JSONObject()
        val intersectionsJson = networkJson.optJSONArray("intersections") ?: JSONArray()
        val intersections = (0 until intersectionsJson.length()).map { index ->
            val item = intersectionsJson.getJSONObject(index)
            val approachesJson = item.optJSONArray("approaches") ?: JSONArray()
            val approaches = (0 until approachesJson.length()).map { approachIndex ->
                val approachJson = approachesJson.getJSONObject(approachIndex)
                val lanesJson = approachJson.optJSONArray("lanes") ?: JSONArray()
                val lanes = (0 until lanesJson.length()).map { laneIndex ->
                    val laneJson = lanesJson.getJSONObject(laneIndex)
                    val movementsJson = laneJson.optJSONArray("allowedMovements") ?: JSONArray()
                    val movements = (0 until movementsJson.length())
                        .mapNotNull { runCatching { MovementKind.valueOf(movementsJson.getString(it)) }.getOrNull() }
                        .toSet()
                    Lane(
                        id = laneJson.optString("id"),
                        index = laneJson.optInt("index"),
                        widthMeters = laneJson.optDouble("widthMeters", 3.5),
                        allowedMovements = movements
                    )
                }
                Approach(
                    id = approachJson.optString("id"),
                    name = approachJson.optString("name"),
                    headingDegrees = approachJson.optDouble("headingDegrees"),
                    lanes = lanes
                )
            }
            Intersection(
                id = item.optString("id"),
                name = item.optString("name"),
                type = runCatching { IntersectionType.valueOf(item.optString("type")) }
                    .getOrDefault(IntersectionType.CUSTOM),
                center = Point2D(item.optDouble("centerX"), item.optDouble("centerY")),
                approaches = approaches
            )
        }
        return ProjectDocument(
            schemaVersion = json.optInt("schemaVersion", 1),
            id = json.getString("id"),
            name = json.optString("name", "Проект"),
            createdAtEpochMs = json.optLong("createdAtEpochMs"),
            updatedAtEpochMs = json.optLong("updatedAtEpochMs"),
            network = RoadNetwork(intersections)
        )
    }
}
