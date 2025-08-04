package dev.wuason.unearthMechanic.system.features

import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.IStage
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.inventory.ItemStack

class BasicFeatures: AbstractFeature() {

    override fun onProcess(
        tick: Long,
        p: Player,
        comp: ICompatibility,
        event: Event,
        loc: Location,
        liveTool: ILiveTool,
        iStage: IStage,
        iGeneric: IGeneric
    ) {
    }

    override fun onApply(
        p: Player,
        comp: ICompatibility,
        event: Event,
        loc: Location,
        liveTool: ILiveTool,
        iStage: IStage,
        iGeneric: IGeneric
    ) {
        if (iStage.getDrops().isNotEmpty()) iStage.dropItems(loc)
        if (iStage.getItems().isNotEmpty()) iStage.addItems(p)

        if (iStage.isRemoveItemMainHand() && p.gameMode != GameMode.CREATIVE) liveTool.setItemMainHand(ItemStack(Material.AIR))

        if (iStage.getReduceItemHand() != 0) liveTool.getItemMainHand()?.let {
            if (p.gameMode != GameMode.CREATIVE) {
                if (!it.type.isAir) it.subtract(iStage.getReduceItemHand())
                UnearthMechanic.getInstance().getStageManager().getAnimator().getAnimation(p)?.let { anim ->
                    anim.updateItemMainHandData()
                }
            }
        }

        if (iStage.getSounds().isNotEmpty()) {
            iStage.getSounds().forEach { sound ->
                if (sound.delay > 0) {
                    Bukkit.getScheduler().runTaskLater(UnearthMechanic.getInstance(), Runnable {
                        p.getWorld().playSound(
                            loc,
                            sound.soundId,
                            SoundCategory.BLOCKS,
                            sound.volume,
                            sound.pitch
                        )
                    }, sound.delay)
                } else {
                    p.getWorld().playSound(
                        loc,
                        sound.soundId,
                        SoundCategory.BLOCKS,
                        sound.volume,
                        sound.pitch
                    )
                }
            }
        }
    }
}