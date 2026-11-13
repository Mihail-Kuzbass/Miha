package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.Approach
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.IntersectionType
import ru.trafficsignalstudio.model.Lane
import ru.trafficsignalstudio.model.MovementKind
import ru.trafficsignalstudio.model.Point2D
import ru.trafficsignalstudio.model.RoadSegment

object IntersectionTemplates {
    fun fourLeg(name: String = "Четырёхсторонний перекрёсток", lanesPerApproach: Int = 2) =
        radial(name, IntersectionType.FOUR_LEG, listOf(0.0, 90.0, 180.0, 270.0), lanesPerApproach)

    fun tJunction(name: String = "Т-образный перекрёсток") =
        radial(name, IntersectionType.T_JUNCTION, listOf(0.0, 90.0, 270.0), 2)

    fun yJunction(name: String = "Y-образный перекрёсток") =
        radial(name, IntersectionType.Y_JUNCTION, listOf(0.0, 120.0, 240.0), 2)

    fun fiveLeg(name: String = "Пятисторонний перекрёсток") =
        radial(name, IntersectionType.FIVE_LEG, (0..4).map { it * 72.0 }, 2)

    fun sixLeg(name: String = "Шестисторонний перекрёсток") =
        radial(name, IntersectionType.SIX_LEG, (0..5).map { it * 60.0 }, 2)

    fun roundabout(name: String = "Круговое движение") =
        radial(name, IntersectionType.ROUNDABOUT, listOf(0.0, 90.0, 180.0, 270.0), 1)

    /**
     * Conceptual diamond interchange layout. The mainline and ramps are
     * independent centerlines; elevated segments are drawn separately from
     * at-grade approaches. Detailed vertical profiles and conflict analysis
     * are intentionally handled by later engineering milestones.
     */
    fun diamondInterchange(name: String = "Ромбовидная транспортная развязка"): Intersection {
        val segments = listOf(
            RoadSegment(name = "Основной ход — верхний уровень",
                centerline = listOf(Point2D(-75.0, 0.0), Point2D(75.0, 0.0)),
                widthMeters = 14.0, laneCount = 4, elevationLevel = 1),
            RoadSegment(name = "Поперечная улица — нижний уровень",
                centerline = listOf(Point2D(0.0, -65.0), Point2D(0.0, 65.0)),
                widthMeters = 12.0, laneCount = 4, elevationLevel = 0),
            RoadSegment(name = "Северо-западный съезд",
                centerline = listOf(Point2D(-18.0, -18.0), Point2D(-27.0, -31.0), Point2D(-42.0, -35.0)),
                widthMeters = 7.0, laneCount = 2, elevationLevel = 1, isRamp = true),
            RoadSegment(name = "Северо-восточный съезд",
                centerline = listOf(Point2D(18.0, -18.0), Point2D(27.0, -31.0), Point2D(42.0, -35.0)),
                widthMeters = 7.0, laneCount = 2, elevationLevel = 1, isRamp = true),
            RoadSegment(name = "Юго-западный съезд",
                centerline = listOf(Point2D(-18.0, 18.0), Point2D(-27.0, 31.0), Point2D(-42.0, 35.0)),
                widthMeters = 7.0, laneCount = 2, elevationLevel = 1, isRamp = true),
            RoadSegment(name = "Юго-восточный съезд",
                centerline = listOf(Point2D(18.0, 18.0), Point2D(27.0, 31.0), Point2D(42.0, 35.0)),
                widthMeters = 7.0, laneCount = 2, elevationLevel = 1, isRamp = true)
        )
        return Intersection(name = name, type = IntersectionType.DIAMOND_INTERCHANGE,
            approaches = emptyList(), roadSegments = segments)
    }

    fun cloverleafInterchange(name: String = "Клеверная транспортная развязка"): Intersection {
        val base = diamondInterchange(name)
        val loops = listOf(
            listOf(Point2D(-18.0, -18.0), Point2D(-35.0, -18.0), Point2D(-43.0, -8.0), Point2D(-35.0, 2.0), Point2D(-18.0, 2.0)),
            listOf(Point2D(18.0, -18.0), Point2D(35.0, -18.0), Point2D(43.0, -8.0), Point2D(35.0, 2.0), Point2D(18.0, 2.0)),
            listOf(Point2D(-18.0, 18.0), Point2D(-35.0, 18.0), Point2D(-43.0, 8.0), Point2D(-35.0, -2.0), Point2D(-18.0, -2.0)),
            listOf(Point2D(18.0, 18.0), Point2D(35.0, 18.0), Point2D(43.0, 8.0), Point2D(35.0, -2.0), Point2D(18.0, -2.0))
        ).mapIndexed { index, points ->
            RoadSegment(name = "Петлевой съезд ${index + 1}", centerline = points,
                widthMeters = 7.0, laneCount = 1, elevationLevel = 1, isRamp = true)
        }
        return base.copy(type = IntersectionType.CLOVERLEAF_INTERCHANGE, roadSegments = base.roadSegments + loops)
    }

    private fun radial(
        name: String,
        type: IntersectionType,
        headings: List<Double>,
        lanesPerApproach: Int
    ): Intersection {
        require(lanesPerApproach in 1..8)
        val approaches = headings.mapIndexed { index, heading ->
            approach(
                when {
                    headings.size == 3 && index == 0 -> "Север"
                    headings.size == 3 -> "Подход ${index + 1}"
                    else -> "Подход ${index + 1}"
                },
                heading,
                lanesPerApproach
            )
        }
        return Intersection(name = name, type = type, approaches = approaches)
    }

    private fun approach(name: String, heading: Double, laneCount: Int) =
        Approach(name = name, headingDegrees = heading, lanes = lanes(laneCount))

    private fun lanes(count: Int): List<Lane> = (1..count).map { index ->
        Lane(index = index, allowedMovements = setOf(
            MovementKind.THROUGH, MovementKind.LEFT, MovementKind.RIGHT
        ))
    }
}
