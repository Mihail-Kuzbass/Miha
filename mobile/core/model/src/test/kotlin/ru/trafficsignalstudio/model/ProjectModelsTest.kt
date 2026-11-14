package ru.trafficsignalstudio.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectModelsTest {
    @Test
    fun newProjectUsesCurrentSchemaAndName() {
        val project = ProjectDocument.newProject("Тестовый узел")
        assertEquals(ProjectDocument.CURRENT_SCHEMA_VERSION, project.schemaVersion)
        assertEquals("Тестовый узел", project.name)
        assertTrue(project.id.isNotBlank())
    }

    @Test
    fun networkLinkHasBackwardCompatibleRoadDefaults() {
        val link = NetworkTrafficLink(
            fromIntersectionId = "A",
            fromMovementConnectionId = "out",
            toIntersectionId = "B",
            toMovementConnectionId = "in"
        )
        assertEquals(1, link.laneCount)
        assertEquals(120.0, link.queueStorageLengthMeters, 0.0)
        assertEquals(30.0, link.travelSpeedKmh, 0.0)
    }

    @Test
    fun networkLinkSupportsCurvedGeometryAndDynamicLaneChangesByDefault() {
        val points = listOf(Point2D(0.0, 0.0), Point2D(100.0, 0.0), Point2D(100.0, 100.0))
        val link = NetworkTrafficLink(
            fromIntersectionId = "A",
            fromMovementConnectionId = "out",
            toIntersectionId = "B",
            toMovementConnectionId = "in",
            centerline = points,
            laneCount = 3
        )
        assertEquals(points, link.centerline)
        assertEquals(3.5, link.laneWidthMeters, 0.0)
        assertTrue(link.laneChangesEnabled)
    }

    @Test
    fun signalPhaseDefaultsToGOSTRedYellowTransition() {
        val phase = SignalPhase(name = "Основная", greenConnectionIds = setOf("movement"))
        assertEquals(2, phase.redYellowSeconds)
        assertEquals(3, phase.yellowSeconds)
    }

    @Test
    fun laneDefaultsToThroughMovement() {
        val lane = Lane(index = 1)
        assertTrue(lane.allowedMovements.contains(MovementKind.THROUGH))
        assertEquals(3.5, lane.widthMeters, 0.0)
    }
}
