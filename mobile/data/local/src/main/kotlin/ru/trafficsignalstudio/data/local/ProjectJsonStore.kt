package ru.trafficsignalstudio.data.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.trafficsignalstudio.model.Approach
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.IntersectionType
import ru.trafficsignalstudio.model.Lane
import ru.trafficsignalstudio.model.LaneFlowDirection
import ru.trafficsignalstudio.model.LaneConnection
import ru.trafficsignalstudio.model.MovementKind
import ru.trafficsignalstudio.model.Point2D
import ru.trafficsignalstudio.model.ProjectDocument
import ru.trafficsignalstudio.model.RoadNetwork
import ru.trafficsignalstudio.model.RoadSegment
import ru.trafficsignalstudio.model.RoadSegmentKind
import ru.trafficsignalstudio.model.RoadSegmentLane
import ru.trafficsignalstudio.model.LaneTravelDirection
import ru.trafficsignalstudio.model.SignalPhase
import ru.trafficsignalstudio.model.SignalPlan
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
                                    put("lanes", JSONArray().apply {
                                        segment.lanes.forEach { lane ->
                                            put(JSONObject().apply {
                                                put("id", lane.id)
                                                put("index", lane.index)
                                                put("widthMeters", lane.widthMeters)
                                                put("travelDirection", lane.travelDirection.name)
                                                put("allowedMovements", JSONArray(lane.allowedMovements.map { it.name }))
                                            })
                                        }
                                    })
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
                        put("laneConnections", JSONArray().apply {
                            intersection.laneConnections.forEach { connection ->
                                put(JSONObject().apply {
                                    put("id", connection.id)
                                    put("fromApproachId", connection.fromApproachId)
                                    put("fromLaneId", connection.fromLaneId)
                                    put("toApproachId", connection.toApproachId)
                                    put("toLaneId", connection.toLaneId)
                                })
                            }
                        })
                        intersection.signalPlan?.let { plan ->
                            put("signalPlan", JSONObject().apply {
                                put("enabled", plan.enabled)
                                put("phases", JSONArray().apply {
                                    plan.phases.forEach { phase ->
                                        put(JSONObject().apply {
                                            put("id", phase.id)
                                            put("name", phase.name)
                                            put("greenConnectionIds", JSONArray(phase.greenConnectionIds.toList()))
                                            put("greenSeconds", phase.greenSeconds)
                                            put("redYellowSeconds", phase.redYellowSeconds)
                                            put("flashingGreenSeconds", phase.flashingGreenSeconds)
                                            put("yellowSeconds", phase.yellowSeconds)
                                            put("allRedSeconds", phase.allRedSeconds)
                                        })
                                    }
                                })
                            })
                        }
                    })
                }
            }
        ).put("trafficLinks", JSONArray().apply {
            project.network.trafficLinks.forEach { link ->
                put(JSONObject().apply {
                    put("id", link.id)
                    put("fromIntersectionId", link.fromIntersectionId)
                    put("fromMovementConnectionId", link.fromMovementConnectionId)
                    put("toIntersectionId", link.toIntersectionId)
                    put("toMovementConnectionId", link.toMovementConnectionId)
                    put("flowSharePercent", link.flowSharePercent)
                    put("distanceMeters", link.distanceMeters)
                    put("enabled", link.enabled)
                    put("travelSpeedKmh", link.travelSpeedKmh)
                    put("laneCount", link.laneCount)
                    put("queueStorageLengthMeters", link.queueStorageLengthMeters)
                    put("laneWidthMeters", link.laneWidthMeters)
                    put("laneChangesEnabled", link.laneChangesEnabled)
                    put("centerline", JSONArray().apply {
                        link.centerline.forEach { point ->
                            put(JSONObject().put("x", point.xMeters).put("y", point.yMeters))
                        }
                    })
                })
            }
        }).put("coordination", JSONObject().apply {
            val settings = project.network.coordination
            put("enabled", settings.enabled)
            put("cycleLengthSeconds", settings.cycleLengthSeconds)
            put("progressionSpeedKmh", settings.progressionSpeedKmh)
            put("referenceIntersectionId", settings.referenceIntersectionId)
            put("direction", settings.direction.name)
            put("offsetsSeconds", JSONObject().apply {
                settings.offsetsSeconds.forEach { (id, offset) -> put(id, offset) }
            })
        }))
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
                val roadLanesJson = segmentJson.optJSONArray("lanes") ?: JSONArray()
                val roadLanes = (0 until roadLanesJson.length()).mapNotNull { laneIndex ->
                    val laneJson = roadLanesJson.optJSONObject(laneIndex) ?: return@mapNotNull null
                    val allowed = laneJson.optJSONArray("allowedMovements") ?: JSONArray()
                    RoadSegmentLane(
                        id = laneJson.optString("id"),
                        index = laneJson.optInt("index", laneIndex + 1),
                        widthMeters = laneJson.optDouble("widthMeters", 3.5),
                        travelDirection = runCatching {
                            LaneTravelDirection.valueOf(laneJson.optString("travelDirection"))
                        }.getOrDefault(LaneTravelDirection.ALONG_CENTERLINE),
                        allowedMovements = (0 until allowed.length())
                            .mapNotNull { runCatching { MovementKind.valueOf(allowed.getString(it)) }.getOrNull() }
                            .toSet().ifEmpty { setOf(MovementKind.THROUGH) }
                    )
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
                    arcRadiusMeters = if (segmentJson.has("arcRadiusMeters")) segmentJson.optDouble("arcRadiusMeters") else null,
                    lanes = roadLanes
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
            val connectionsJson = item.optJSONArray("laneConnections") ?: JSONArray()
            val connections = (0 until connectionsJson.length()).mapNotNull { connectionIndex ->
                val connection = connectionsJson.optJSONObject(connectionIndex) ?: return@mapNotNull null
                LaneConnection(
                    id = connection.optString("id"),
                    fromApproachId = connection.optString("fromApproachId"),
                    fromLaneId = connection.optString("fromLaneId"),
                    toApproachId = connection.optString("toApproachId"),
                    toLaneId = connection.optString("toLaneId")
                )
            }
            val planJson = item.optJSONObject("signalPlan")
            val signalPlan = planJson?.let { plan ->
                val phasesJson = plan.optJSONArray("phases") ?: JSONArray()
                SignalPlan(
                    enabled = plan.optBoolean("enabled", true),
                    phases = (0 until phasesJson.length()).mapNotNull { phaseIndex ->
                        val phase = phasesJson.optJSONObject(phaseIndex) ?: return@mapNotNull null
                        val greens = phase.optJSONArray("greenConnectionIds") ?: JSONArray()
                        SignalPhase(
                            id = phase.optString("id"),
                            name = phase.optString("name", "Фаза " + (phaseIndex + 1)),
                            greenConnectionIds = (0 until greens.length()).map { greens.getString(it) }.toSet(),
                            greenSeconds = phase.optInt("greenSeconds", 30),
                            redYellowSeconds = phase.optInt("redYellowSeconds", 2),
                            flashingGreenSeconds = phase.optInt("flashingGreenSeconds", 3),
                            yellowSeconds = phase.optInt("yellowSeconds", 3),
                            allRedSeconds = phase.optInt("allRedSeconds", 1)
                        )
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
                safetyIslands = islands,
                laneConnections = connections,
                signalPlan = signalPlan
            )
        }
        val linksJson = networkJson.optJSONArray("trafficLinks") ?: JSONArray()
        val trafficLinks = (0 until linksJson.length()).mapNotNull { index ->
            val link = linksJson.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                ru.trafficsignalstudio.model.NetworkTrafficLink(
                    id = link.optString("id"),
                    fromIntersectionId = link.getString("fromIntersectionId"),
                    fromMovementConnectionId = link.getString("fromMovementConnectionId"),
                    toIntersectionId = link.getString("toIntersectionId"),
                    toMovementConnectionId = link.getString("toMovementConnectionId"),
                    flowSharePercent = link.optDouble("flowSharePercent", 100.0),
                    distanceMeters = link.optDouble("distanceMeters", 500.0),
                    enabled = link.optBoolean("enabled", true),
                    travelSpeedKmh = link.optDouble("travelSpeedKmh", 30.0).coerceIn(5.0, 120.0),
                    laneCount = link.optInt("laneCount", 1).coerceIn(1, 8),
                    queueStorageLengthMeters = link.optDouble("queueStorageLengthMeters", 120.0).coerceIn(1.0, 5000.0),
                    laneWidthMeters = link.optDouble("laneWidthMeters", 3.5).coerceIn(2.5, 5.0),
                    laneChangesEnabled = link.optBoolean("laneChangesEnabled", true),
                    centerline = (link.optJSONArray("centerline") ?: JSONArray()).let { points ->
                        (0 until points.length()).mapNotNull { pointIndex ->
                            points.optJSONObject(pointIndex)?.let { point ->
                                Point2D(point.optDouble("x"), point.optDouble("y"))
                            }
                        }.takeIf { it.size >= 2 }.orEmpty()
                    }
                )
            }.getOrNull()
        }
        val coordinationJson = networkJson.optJSONObject("coordination") ?: JSONObject()
        val offsetsJson = coordinationJson.optJSONObject("offsetsSeconds") ?: JSONObject()
        val offsets = offsetsJson.keys().asSequence().mapNotNull { key ->
            runCatching { key to offsetsJson.getInt(key) }.getOrNull()
        }.toMap()
        val coordination = ru.trafficsignalstudio.model.NetworkCoordinationSettings(
            enabled = coordinationJson.optBoolean("enabled", false),
            cycleLengthSeconds = coordinationJson.optInt("cycleLengthSeconds", 90).coerceIn(30, 240),
            progressionSpeedKmh = coordinationJson.optDouble("progressionSpeedKmh", 50.0).coerceIn(5.0, 120.0),
            referenceIntersectionId = coordinationJson.optString("referenceIntersectionId").takeIf { it.isNotBlank() },
            direction = runCatching {
                ru.trafficsignalstudio.model.CoordinationDirection.valueOf(coordinationJson.optString("direction"))
            }.getOrDefault(ru.trafficsignalstudio.model.CoordinationDirection.FORWARD),
            offsetsSeconds = offsets
        )
        return ProjectDocument(
            schemaVersion = ProjectDocument.CURRENT_SCHEMA_VERSION,
            id = json.getString("id"),
            name = json.optString("name", "Проект"),
            createdAtEpochMs = json.optLong("createdAtEpochMs"),
            updatedAtEpochMs = json.optLong("updatedAtEpochMs"),
            network = RoadNetwork(intersections, trafficLinks, coordination)
        )
    }
}
