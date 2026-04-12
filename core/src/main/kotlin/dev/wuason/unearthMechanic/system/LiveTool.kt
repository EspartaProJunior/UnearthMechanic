package dev.wuason.unearthMechanic.system

import dev.wuason.adapter.Adapter
import dev.wuason.unearthMechanic.config.ITool
import dev.wuason.unearthMechanic.system.animations.IAnimationRunner
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class LiveTool(private var itemMainHand: ItemStack, private val iTool: ITool, private val player: Player, private val stageManager: StageManager): ILiveTool {

    override fun getItemMainHand(): ItemStack {
        return if (!stageManager.getAnimator().isAnimating(player)) {
            player.inventory.itemInMainHand
        } else {
            stageManager.getAnimator().getAnimation(player)!!.getItemMainHand()
        }
    }

    override fun getITool(): ITool {
        return iTool
    }

    override fun setItemMainHand(item: ItemStack) {
        itemMainHand = item
        if (!stageManager.getAnimator().isAnimating(player)) player.inventory.setItemInMainHand(item)
        else stageManager.getAnimator().getAnimation(player)?.setItemMainHand(item)
    }

    override fun isValid(): Boolean {
        if (stageManager.getAnimator().isAnimating(player)) {
            val data: IAnimationRunner = stageManager.getAnimator().getAnimation(player)!!
            return data.isValid()
        }
        return player.inventory.itemInMainHand == itemMainHand
    }

    override fun isOriginalItem(): Boolean {
        return Adapter.getAdapterData(Adapter.getAdapterId(getItemMainHand())).get() == iTool.getAdapterData()
    }
}