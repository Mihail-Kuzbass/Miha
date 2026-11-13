package ru.trafficsignalstudio.data.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.trafficsignalstudio.model.Approach
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.IntersectionType
import ru.trafficsignalstudio.model.Lane
import ru.trafficsignalstudio.model.LaneFlowDirection
import ru.trafficsignalstudio.model.MovementKind
import ru.trafficsignalstudio.model.Point2D
import ru.trafficsignalstudio.model.ProjectDocument
import ru.trafficsignalstudio.model.RoadNetwork
import ru.trafficsignalstudio.model.RoadSegment
import ru.trafficsignalstudio.model.RoadSegmentKind
import ru.trafficsignalstudio.model.RoadJunctionNode
import ru.trafficsignalstudio.model.SafetyIsland
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
                                                put("flowDirection", lane.flowDirection.name)
                                            })
                                        }
                                    })
                                })
                            }
                        })
                        put("roadSegments", JSONArray().apply {
                            intersection.roadSegments.forEach { segment ->
                                put(JSONObject().apply {
                                    put("id", segment.id)
                                    put("name", segment.name)
                                    put("widthMeters", segment.widthMeters)
                                    put("laneCount", segment.laneCount)
                                    put("elevationLevel", segment.elevationLevel)
                                    put("isRamp", segment.isRamp)
                                    put("kind", segment.kind.name)
                                    segment.arcRadiusMeters?.let { put("arcRadiusMeters", it) }
                                    put("centerline", JSONArray().apply {
                                        segment.centerline.forEach { point ->
                                            put(JSONObject().put("x", point.xMeters).put("y", point.yMeters))
                                        }
                                    })
                                })
                            }
                        })
                        put("junctionNodes", JSONArray().apply {
                            intersection.junctionNodes.forEach { node ->
                                put(JSONObject().apply {
                                    put("id", node.id); put("x", node.point.xMeters); put("y", node.point.yMeters)
                                    put("connectedSegmentIds", JSONArray(node.connectedSegmentIds.toList()))
                                })
                            }
                        })
                        put("safetyIslands", JSONArray().apply {
                            intersection.safetyIslands.forEach { island ->
                                put(JSONObject().apply {
                                    put("id", island.id); put("name", island.name); put("elevationLevel", island.elevationLevel)
                                    put("boundary", JSONArray().apply {
                                        island.boundary.forEach { point -> put(JSONObject().put("x", point.xMeters).put("y", point.yMeters)) }
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
                        .ifEmpty { setOf(MovementKind.THROUGH) }
                    val flowDirection = runCatching {
                        LaneFlowDirection.valueOf(laneJson.optString("flowDirection"))
                    }.getOrDefault(LaneFlowDirection.INTO_INTERSECTION)
                    Lane(
                        id = laneJson.optString("id"),
                        index = laneJson.optInt("index"),
                        widthMeters = laneJson.optDouble("widthMeters", 3.5),
                        allowedMovements = movements,
                        flowDirection = flowDirection
                    )
                }
                Approach(
                    id = approachJson.optString("id"),
                    name = approachJson.optString("name"),
                    headingDegrees = approachJson.optDouble("headingDegrees"),
                    lanes = lanes
                )
            }
            val segmentsJson = item.optJSONArray("roadSegments") ?: JSONArray()
            val segments = (0 until segmentsJson.length()).map { segmentIndex ->
                val segmentJson = segmentsJson.getJSONObject(segmentIndex)
                val pointsJson = segmentJson.optJSONArray("centerline") ?: JSONArray()
                val points = (0 until pointsJson.length()).map { pointIndex ->
                    val point = pointsJson.getJSONObject(pointIndex)
                    Point2D(point.optDouble("x"), point.optDouble("y"))
                }
                RoadSegment(
                    id = segmentJson.optString("id"),
                    name = segmentJson.optString("name"),
                    centerline = points,
                    widthMeters = segmentJson.optDouble("widthMeters", 7.0),
                    laneCount = segmentJson.optInt("laneCount", 2),
                    elevationLevel = segmentJson.optInt("elevationLevel", 0),
                    isRamp = segmentJson.optBoolean("isRamp", false),
                    kind = runCatching { RoadSegmentKind.valueOf(segmentJson.optString("kind")) }
                        .getOrDefault(if (segmentJson.optBoolean("isRamp", false)) RoadSegmentKind.RAMP else RoadSegmentKind.POLYLINE),
                    arcRadiusMeters = if (segmentJson.has("arcRadiusMeters")) segmentJson.optDouble("arcRadiusMeters") else null
                )
            }
            val nodesJson = item.optJSONArray("junctionNodes") ?: JSONArray()
            val nodes = (0 until nodesJson.length()).map { nodeIndex ->
                val node = nodesJson.getJSONObject(nodeIndex)
                val ids = node.optJSONArray("connectedSegmentIds") ?: JSONArray()
                RoadJunctionNode(
                    id = node.optString("id"),
                    point = Point2D(node.optDouble("x"), node.optDouble("y")),
                    connectedSegmentIds = (0 until ids.length()).map { ids.getString(it) }.toSet()
                )
            }
            val islandsJson = item.optJSONArray("safetyIslands") ?: JSONArray()
            val islands = (0 until islandsJson.length()).map { islandIndex ->
                val island = islandsJson.getJSONObject(islandIndex)
                val boundaryJson = island.optJSONArray("boundary") ?: JSONArray()
                SafetyIsland(
                    id = island.optString("id"),
                    name = island.optString("name", "Островок безопасности"),
                    elevationLevel = island.optInt("elevationLevel", 0),
                    boundary = (0 until boundaryJson.length()).map { pointIndex ->
                        val point = boundaryJson.getJSONObject(pointIndex)
                        Point2D(point.optDouble("x"), point.optDouble("y"))
                    }
                )
            }
            Intersection(
                id = item.optString("id"),
                name = item.optString("name"),
                type = runCatching { IntersectionType.valueOf(item.optString("type")) }
                    .getOrDefault(IntersectionType.CUSTOM),
                center = Point2D(item.optDouble("centerX"), item.optDouble("centerY")),
                approaches = approaches,
                roadSegments = segments,
                junctionNodes = nodes,
                safetyIslands = islands
            )
        }
        return ProjectDocument(
            schemaVersion = ProjectDocument.CURRENT_SCHEMA_VERSION,
            id = json.getString("id"),
            name = json.optString("name", "Проект"),
            createdAtEpochMs = json.optLong("createdAtEpochMs"),
            updatedAtEpochMs = json.optLong("updatedAtEpochMs"),
            network = RoadNetwork(intersections)
        )
    }
}
