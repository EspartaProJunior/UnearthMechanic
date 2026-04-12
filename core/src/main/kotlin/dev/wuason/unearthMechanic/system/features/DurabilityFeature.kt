package dev.wuason.unearthMechanic.system.features

import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.IStage
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import dev.wuason.unearthMechanic.utils.ItemBuilder
import dev.wuason.unearthMechanic.utils.VersionDetector
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import kotlin.math.min

class DurabilityFeature: AbstractFeature() {

    override fun onApply(
        p: Player,
        comp: ICompatibility,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        stage: IStage,
        iGeneric: IGeneric
    ) {
        if (stage.getDurabilityToRemove() > 0 && p.gameMode != GameMode.CREATIVE) {
            val itemMainHand: ItemStack = toolUsed.getItemMainHand()?: return
            if (!itemMainHand.type.isAir) {
                itemMainHand.editMeta { meta ->
                    if (meta is Damageable) {

                        if (VersionDetector.getServerVersion().isLessThan(VersionDetector.ServerVersion.v1_20_5)) {
                            meta.damage += stage.getDurabilityToRemove()
                            if (meta.damage >= itemMainHand.type.maxDurability) {
                                toolUsed.getITool().getReplaceOnBreak()?.let {
                                    toolUsed.setItemMainHand(ItemBuilder(it, 1).build())
                                }?: toolUsed.setItemMainHand(ItemStack(Material.AIR))
                            }
                        }
                        else {
                            if (meta.hasMaxDamage()) {

                                meta.damage += min(stage.getDurabilityToRemove(), meta.maxDamage - meta.damage)

                                if (meta.damage >= meta.maxDamage) {
                                    toolUsed.getITool().getReplaceOnBreak()?.let {
                                        toolUsed.setItemMainHand(ItemBuilder(it, 1).build())
                                    }?: toolUsed.setItemMainHand(ItemStack(Material.AIR))
                                }
                            }
                            else {

                                meta.damage += stage.getDurabilityToRemove()

                                if (meta.damage >= itemMainHand.type.maxDurability) {

                                    toolUsed.getITool().getReplaceOnBreak()?.let {
                                        toolUsed.setItemMainHand(ItemBuilder(it, 1).build())
                                    }?: toolUsed.setItemMainHand(ItemStack(Material.AIR))

                                }
                            }
                        }
                    }
                }

                UnearthMechanic.getInstance().getStageManager().getAnimator().getAnimation(p)?.let { anim ->
                    anim.updateItemMainHandData()
                }
            }

        }
    }

}