package ru.trafficsignalstudio.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleModelTest {
    @Test
    fun catalogContainsRoadPublicTransportAndVulnerableRoadUsers() {
        val classes = VehicleModelCatalog.defaults.map { it.vehicleClass }.toSet()
        assertTrue(classes.contains(VehicleClass.PASSENGER_CAR))
        assertTrue(classes.contains(VehicleClass.RIGID_TRUCK))
        assertTrue(classes.contains(VehicleClass.CITY_BUS))
        assertTrue(classes.contains(VehicleClass.ARTICULATED_BUS))
        assertTrue(classes.contains(VehicleClass.TROLLEYBUS))
        assertTrue(classes.contains(VehicleClass.ARTICULATED_TROLLEYBUS))
        assertTrue(classes.contains(VehicleClass.MOTORCYCLE))
        assertTrue(classes.contains(VehicleClass.BICYCLE))
    }

    @Test
    fun defaultPassengerCarHasPositiveDimensionsAndPerformance() {
        val model = VehicleModelCatalog.defaultPassengerCar
        assertEquals("passenger-car-basic", model.id)
        assertTrue(model.lengthMeters > 0.0)
        assertTrue(model.widthMeters > 0.0)
        assertTrue(model.minimumTurningRadiusMeters > 0.0)
        assertTrue(model.maximumSpeedKmh > 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsImpossibleVehicleDimensions() {
        VehicleModel(name = "Invalid", vehicleClass = VehicleClass.PASSENGER_CAR,
            lengthMeters = 4.0, widthMeters = 0.0, heightMeters = 1.5,
            wheelbaseMeters = 2.0, minimumTurningRadiusMeters = 5.0,
            maximumSpeedKmh = 100.0, accelerationMetersPerSecondSquared = 1.0,
            serviceDecelerationMetersPerSecondSquared = 2.0,
            frontOverhangMeters = 1.0, rearOverhangMeters = 1.0)
    }
}
