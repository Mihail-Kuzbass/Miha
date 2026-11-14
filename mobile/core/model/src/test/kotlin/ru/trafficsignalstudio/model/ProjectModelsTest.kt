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
    fun laneDefaultsToThroughMovement() {
        val lane = Lane(index = 1)
        assertTrue(lane.allowedMovements.contains(MovementKind.THROUGH))
        assertEquals(3.5, lane.widthMeters, 0.0)
    }
}
