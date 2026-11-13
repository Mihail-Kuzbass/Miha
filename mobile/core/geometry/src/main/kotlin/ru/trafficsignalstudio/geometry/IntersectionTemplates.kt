package ru.trafficsignalstudio.geometry

import ru.trafficsignalstudio.model.Approach
import ru.trafficsignalstudio.model.Intersection
import ru.trafficsignalstudio.model.IntersectionType
import ru.trafficsignalstudio.model.Lane
import ru.trafficsignalstudio.model.MovementKind

object IntersectionTemplates {
    fun fourLeg(
        name: String = "Четырёхсторонний перекрёсток",
        lanesPerApproach: Int = 2
    ): Intersection {
        require(lanesPerApproach in 1..8) {
            "Количество полос на подходе должно быть от 1 до 8"
        }

        val approaches = listOf(
            Approach(name = "Север", headingDegrees = 0.0, lanes = lanes(lanesPerApproach)),
            Approach(name = "Восток", headingDegrees = 90.0, lanes = lanes(lanesPerApproach)),
            Approach(name = "Юг", headingDegrees = 180.0, lanes = lanes(lanesPerApproach)),
            Approach(name = "Запад", headingDegrees = 270.0, lanes = lanes(lanesPerApproach))
        )

        return Intersection(
            name = name,
            type = IntersectionType.FOUR_LEG,
            approaches = approaches
        )
    }

    fun tJunction(name: String = "Т-образный перекрёсток"): Intersection =
        Intersection(
            name = name,
            type = IntersectionType.T_JUNCTION,
            approaches = listOf(
                Approach(name = "Север", headingDegrees = 0.0, lanes = lanes(2)),
                Approach(name = "Восток", headingDegrees = 90.0, lanes = lanes(2)),
                Approach(name = "Запад", headingDegrees = 270.0, lanes = lanes(2))
            )
        )

    private fun lanes(count: Int): List<Lane> = (1..count).map { index ->
        Lane(
            index = index,
            allowedMovements = setOf(
                MovementKind.THROUGH,
                MovementKind.LEFT,
                MovementKind.RIGHT
            )
        )
    }
}
