package dev.wuason.unearthMechanic.config

import dev.wuason.unearthMechanic.system.animations.AnimationManager
import dev.wuason.unearthMechanic.utils.ItemBuilder
import org.bukkit.inventory.ItemStack

class Animation(
    private val ticks: Long,
    private val animationItem: String,
    private val blockInteractions: Boolean = true
): IAnimation {

    override fun getTicks(): Long {
        return ticks
    }

    override fun getAnimationItem(): ItemStack {
        return ItemBuilder(animationItem, 1).addPersistentData(AnimationManager.ANIM_NAMESPACED_KEY, animationItem).build()
    }

    override fun shouldBlockInteractions(): Boolean {
        return blockInteractions
    }

    override fun toString(): String {
        return "Animation(ticks=$ticks, animationItem=$animationItem, blockInteractions=$blockInteractions)"
    }
}
