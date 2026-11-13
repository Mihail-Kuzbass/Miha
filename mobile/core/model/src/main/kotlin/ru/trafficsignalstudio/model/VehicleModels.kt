package ru.trafficsignalstudio.model

import java.util.UUID

enum class VehicleClass {
    PASSENGER_CAR,
    LIGHT_COMMERCIAL,
    RIGID_TRUCK,
    ARTICULATED_TRUCK,
    CITY_BUS,
    ARTICULATED_BUS,
    TROLLEYBUS,
    ARTICULATED_TROLLEYBUS,
    MOTORCYCLE,
    BICYCLE
}

/**
 * Engineering vehicle template. Dimensions are editable nominal defaults;
 * they are not a declaration of a specific manufacturer's certified envelope.
 */
data class VehicleModel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val vehicleClass: VehicleClass,
    val lengthMeters: Double,
    val widthMeters: Double,
    val heightMeters: Double,
    val wheelbaseMeters: Double,
    val minimumTurningRadiusMeters: Double,
    val maximumSpeedKmh: Double,
    val accelerationMetersPerSecondSquared: Double,
    val serviceDecelerationMetersPerSecondSquared: Double,
    val frontOverhangMeters: Double,
    val rearOverhangMeters: Double,
    val articulationPointFromFrontMeters: Double? = null,
    val isDefault: Boolean = false,
    /** Standstill bumper-to-bumper clearance used by the car-following model. */
    val minimumGapMeters: Double = 2.0,
    /** Desired time headway to the leader for IDM car-following. */
    val desiredTimeHeadwaySeconds: Double = 1.5
) {
    init {
        require(lengthMeters > 0.0 && widthMeters > 0.0 && heightMeters > 0.0)
        require(wheelbaseMeters >= 0.0 && minimumTurningRadiusMeters >= 0.0)
        require(maximumSpeedKmh >= 0.0)
        require(accelerationMetersPerSecondSquared >= 0.0)
        require(serviceDecelerationMetersPerSecondSquared >= 0.0)
        require(minimumGapMeters >= 0.0)
        require(desiredTimeHeadwaySeconds >= 0.0)
        require(frontOverhangMeters >= 0.0 && rearOverhangMeters >= 0.0)
        require(frontOverhangMeters + rearOverhangMeters < lengthMeters)
        require(articulationPointFromFrontMeters == null ||
            articulationPointFromFrontMeters in 0.0..lengthMeters)
    }
}

/** Runtime state for a vehicle instance in a future time-domain simulation. */
data class VehiclePose(
    val vehicleId: String = UUID.randomUUID().toString(),
    val modelId: String,
    val position: Point2D,
    /** Clockwise degrees from map north, matching Approach.headingDegrees. */
    val headingDegrees: Double,
    val speedKmh: Double = 0.0,
    val accelerationMetersPerSecondSquared: Double = 0.0
) {
    init {
        require(speedKmh >= 0.0)
    }
}

object VehicleModelCatalog {
    val defaults: List<VehicleModel> = listOf(
        VehicleModel(
            id = "passenger-car-basic", name = "Легковой автомобиль", vehicleClass = VehicleClass.PASSENGER_CAR,
            lengthMeters = 4.6, widthMeters = 1.8, heightMeters = 1.5, wheelbaseMeters = 2.7,
            minimumTurningRadiusMeters = 5.5, maximumSpeedKmh = 130.0,
            accelerationMetersPerSecondSquared = 2.0, serviceDecelerationMetersPerSecondSquared = 3.5,
            frontOverhangMeters = 0.9, rearOverhangMeters = 1.0, isDefault = true
        ),
        VehicleModel(
            id = "light-commercial-basic", name = "Малотоннажный фургон", vehicleClass = VehicleClass.LIGHT_COMMERCIAL,
            lengthMeters = 6.0, widthMeters = 2.0, heightMeters = 2.5, wheelbaseMeters = 3.7,
            minimumTurningRadiusMeters = 6.8, maximumSpeedKmh = 110.0,
            accelerationMetersPerSecondSquared = 1.4, serviceDecelerationMetersPerSecondSquared = 3.0,
            frontOverhangMeters = 1.0, rearOverhangMeters = 1.3
        ),
        VehicleModel(
            id = "rigid-truck-basic", name = "Грузовой автомобиль", vehicleClass = VehicleClass.RIGID_TRUCK,
            lengthMeters = 10.0, widthMeters = 2.5, heightMeters = 3.8, wheelbaseMeters = 5.2,
            minimumTurningRadiusMeters = 9.5, maximumSpeedKmh = 90.0,
            accelerationMetersPerSecondSquared = 0.8, serviceDecelerationMetersPerSecondSquared = 2.5,
            frontOverhangMeters = 1.4, rearOverhangMeters = 3.4
        ),
        VehicleModel(
            id = "articulated-truck-basic", name = "Автопоезд", vehicleClass = VehicleClass.ARTICULATED_TRUCK,
            lengthMeters = 16.5, widthMeters = 2.55, heightMeters = 4.0, wheelbaseMeters = 11.0,
            minimumTurningRadiusMeters = 12.5, maximumSpeedKmh = 90.0,
            accelerationMetersPerSecondSquared = 0.55, serviceDecelerationMetersPerSecondSquared = 2.2,
            frontOverhangMeters = 1.4, rearOverhangMeters = 3.0, articulationPointFromFrontMeters = 4.2
        ),
        VehicleModel(
            id = "city-bus-12m", name = "Городской автобус 12 м", vehicleClass = VehicleClass.CITY_BUS,
            lengthMeters = 12.0, widthMeters = 2.55, heightMeters = 3.2, wheelbaseMeters = 6.0,
            minimumTurningRadiusMeters = 10.5, maximumSpeedKmh = 80.0,
            accelerationMetersPerSecondSquared = 0.9, serviceDecelerationMetersPerSecondSquared = 2.8,
            frontOverhangMeters = 2.7, rearOverhangMeters = 3.3
        ),
        VehicleModel(
            id = "articulated-bus-18m", name = "Сочленённый автобус 18 м", vehicleClass = VehicleClass.ARTICULATED_BUS,
            lengthMeters = 18.0, widthMeters = 2.55, heightMeters = 3.2, wheelbaseMeters = 5.8,
            minimumTurningRadiusMeters = 12.0, maximumSpeedKmh = 80.0,
            accelerationMetersPerSecondSquared = 0.7, serviceDecelerationMetersPerSecondSquared = 2.7,
            frontOverhangMeters = 2.7, rearOverhangMeters = 3.5, articulationPointFromFrontMeters = 6.0
        ),
        VehicleModel(
            id = "trolleybus-12m", name = "Троллейбус 12 м", vehicleClass = VehicleClass.TROLLEYBUS,
            lengthMeters = 12.0, widthMeters = 2.55, heightMeters = 3.4, wheelbaseMeters = 6.0,
            minimumTurningRadiusMeters = 11.0, maximumSpeedKmh = 70.0,
            accelerationMetersPerSecondSquared = 0.8, serviceDecelerationMetersPerSecondSquared = 2.8,
            frontOverhangMeters = 2.6, rearOverhangMeters = 3.4
        ),
        VehicleModel(
            id = "articulated-trolleybus-18m", name = "Сочленённый троллейбус 18 м", vehicleClass = VehicleClass.ARTICULATED_TROLLEYBUS,
            lengthMeters = 18.0, widthMeters = 2.55, heightMeters = 3.4, wheelbaseMeters = 5.8,
            minimumTurningRadiusMeters = 12.5, maximumSpeedKmh = 65.0,
            accelerationMetersPerSecondSquared = 0.65, serviceDecelerationMetersPerSecondSquared = 2.7,
            frontOverhangMeters = 2.6, rearOverhangMeters = 3.5, articulationPointFromFrontMeters = 6.0
        ),
        VehicleModel(
            id = "motorcycle-basic", name = "Мотоцикл", vehicleClass = VehicleClass.MOTORCYCLE,
            lengthMeters = 2.2, widthMeters = 0.85, heightMeters = 1.4, wheelbaseMeters = 1.5,
            minimumTurningRadiusMeters = 2.8, maximumSpeedKmh = 120.0,
            accelerationMetersPerSecondSquared = 2.5, serviceDecelerationMetersPerSecondSquared = 4.0,
            frontOverhangMeters = 0.35, rearOverhangMeters = 0.35
        ),
        VehicleModel(
            id = "bicycle-basic", name = "Велосипед", vehicleClass = VehicleClass.BICYCLE,
            lengthMeters = 1.8, widthMeters = 0.65, heightMeters = 1.1, wheelbaseMeters = 1.1,
            minimumTurningRadiusMeters = 1.5, maximumSpeedKmh = 35.0,
            accelerationMetersPerSecondSquared = 0.8, serviceDecelerationMetersPerSecondSquared = 2.0,
            frontOverhangMeters = 0.35, rearOverhangMeters = 0.35
        )
    )

    val defaultPassengerCar: VehicleModel
        get() = defaults.first { it.isDefault }
}
