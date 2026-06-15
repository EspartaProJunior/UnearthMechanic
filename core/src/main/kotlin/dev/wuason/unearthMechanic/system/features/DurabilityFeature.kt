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

class DurabilityFeature : AbstractFeature() {

    override fun onApply(
        p: Player,
        comp: ICompatibility,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        stage: IStage,
        iGeneric: IGeneric
    ) {
        val reduce = stage.getDurabilityToRemove()
        if (reduce <= 0 || p.gameMode == GameMode.CREATIVE) return

        val itemMainHand: ItemStack = toolUsed.getItemMainHand() ?: return
        if (itemMainHand.type.isAir) return

        var shouldBreak = false

        itemMainHand.editMeta { meta ->
            if (meta !is Damageable) return@editMeta

            val maxDurability =
                if (!VersionDetector.getServerVersion().isLessThan(VersionDetector.ServerVersion.v1_20_5) && meta.hasMaxDamage()) {
                    meta.maxDamage
                } else {
                    itemMainHand.type.maxDurability.toInt()
                }

            if (maxDurability <= 0) return@editMeta

            val newDamage = meta.damage + min(reduce, maxDurability - meta.damage)
            meta.damage = newDamage

            shouldBreak = newDamage >= maxDurability
        }

        if (shouldBreak) {
            toolUsed.getITool().getReplaceOnBreak()?.let {
                toolUsed.setItemMainHand(ItemBuilder(it, 1).build())
            } ?: toolUsed.setItemMainHand(ItemStack(Material.AIR))
        }

        UnearthMechanic.getInstance().getStageManager().getAnimator().getAnimation(p)?.let { anim ->
            anim.updateItemMainHandData()
        }
    }
}