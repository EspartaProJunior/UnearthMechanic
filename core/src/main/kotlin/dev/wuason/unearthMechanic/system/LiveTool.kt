package dev.wuason.unearthMechanic.system

import dev.wuason.adapter.Adapter
import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.config.ITool
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import kotlin.jvm.optionals.getOrNull


class LiveTool(private var toolData: ToolData, private val iTool: ITool, private val player: Player, private val stageManager: StageManager): ILiveTool {

    private val initialSlotType: AdapterData? = if (toolData.slotPos >= 0) {
        player.inventory.getItem(toolData.slotPos)?.let { Adapter.getAdapterData(Adapter.getAdapterId(it)).getOrNull() }
    } else null

    override fun getItemMainHand(): ItemStack {
        return if (!stageManager.getAnimator().isAnimating(player)) {
            // Should always be the correct stack we want
            toolData.itemStack
        } else {
            stageManager.getAnimator().getAnimation(player)!!.getItemMainHand()
        }
    }

    override fun getITool(): ITool {
        return iTool
    }

    override fun setItemMainHand(item: ItemStack) {
        toolData = toolData.copy(itemStack = item)
        if (!stageManager.getAnimator().isAnimating(player)) {
            player.inventory.setItem(toolData.slotPos, item)
        } else {
            stageManager.getAnimator().getAnimation(player)?.setItemMainHand(item)
        }
    }

    override fun isValid(): Boolean {
        val animation = stageManager.getAnimator().getAnimation(player)
        if (animation != null && (toolData.slotPos < 0 || toolData.slotPos == player.inventory.heldItemSlot)) {
            val originalToolData = Adapter.getAdapterData(Adapter.getAdapterId(animation.getItemMainHand())).getOrNull()
            return animation.isValid() && originalToolData == iTool.getAdapterData()
        }

        if (toolData.slotPos >= 0) {
            val current = player.inventory.getItem(toolData.slotPos)
            if (current == null || current.type.isAir) return initialSlotType == null
            val currentType = Adapter.getAdapterData(Adapter.getAdapterId(current)).getOrNull()
            return currentType == initialSlotType
        }
        return player.inventory.itemInMainHand == toolData.itemStack
    }

    override fun isOriginalItem(): Boolean {
        return Adapter.getAdapterData(Adapter.getAdapterId(getItemMainHand())).getOrNull() == iTool.getAdapterData()
    }
}