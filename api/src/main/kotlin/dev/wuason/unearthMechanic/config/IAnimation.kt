package dev.wuason.unearthMechanic.config

import org.bukkit.inventory.ItemStack

/**
 * Interface that specifies the contract for any animation used within the application.
 * Implementations of this interface should provide the item associated with the animation
 * and the duration (in ticks) for which the animation runs.
 */
interface IAnimation {
    /**
     * Retrieves the animation item associated with the animation.
     *
     * @return The ItemStack representing the animation item.
     */
    fun getAnimationItem(): ItemStack
    /**
     * Retrieves the number of ticks for the animation.
     *
     * @return The number of ticks for the animation as a `Long`.
     */
    fun getTicks(): Long

    fun shouldBlockInteractions(): Boolean = true
}