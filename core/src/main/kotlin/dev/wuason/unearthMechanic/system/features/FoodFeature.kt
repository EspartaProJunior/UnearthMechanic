package dev.wuason.unearthMechanic.system.features

import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.IStage
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event
import kotlin.math.max
import kotlin.math.min

class FoodFeature : AbstractFeature() {

    override fun onApply(
        p: Player,
        comp: ICompatibility,
        event: Event,
        loc: Location,
        liveTool: ILiveTool,
        iStage: IStage,
        iGeneric: IGeneric
    ) {
        val foodAdd = iStage.getFoodAdd()
        val saturationAdd = iStage.getSaturationAdd()

        if (foodAdd == 0 && saturationAdd == 0.0f) return

        if (foodAdd != 0) {
            p.foodLevel = (p.foodLevel + foodAdd).coerceIn(0, 20)
        }

        if (saturationAdd != 0.0f) {
            val newSaturation = p.saturation + saturationAdd

            p.saturation = min(
                max(newSaturation, 0.0f),
                p.foodLevel.toFloat()
            )
        }
    }
}