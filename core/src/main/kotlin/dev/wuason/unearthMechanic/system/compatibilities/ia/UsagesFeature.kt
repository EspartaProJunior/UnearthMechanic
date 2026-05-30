package dev.wuason.unearthMechanic.system.compatibilities.ia

import dev.lone.itemsadder.api.CustomStack
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.IStage
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import dev.wuason.unearthMechanic.system.features.AbstractFeature
import dev.wuason.unearthMechanic.utils.FoliaUtils
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.inventory.ItemStack

class UsagesFeature: AbstractFeature() {

    override fun onApply(
        p: Player,
        comp: ICompatibility,
        event: Event,
        loc: Location,
        liveTool: ILiveTool,
        iStage: IStage,
        iGeneric: IGeneric
    ) {
        FoliaUtils.runAtEntity(p) {
            val itemMainHand: ItemStack = liveTool.getItemMainHand() ?: return@runAtEntity

            if (p.gameMode == GameMode.CREATIVE) return@runAtEntity
            if (iStage.getUsagesIaToRemove() <= 0) return@runAtEntity
            if (itemMainHand.type.isAir) return@runAtEntity

            CustomStack.byItemStack(itemMainHand)?.let { customStack ->
                customStack.reduceUsages(iStage.getUsagesIaToRemove())
                itemMainHand.itemMeta = customStack.itemStack.itemMeta

                UnearthMechanic.getInstance()
                    .getStageManager()
                    .getAnimator()
                    .getAnimation(p)
                    ?.updateItemMainHandData()
            }
        }
    }

}