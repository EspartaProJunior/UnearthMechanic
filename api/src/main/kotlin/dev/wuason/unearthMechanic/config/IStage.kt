package dev.wuason.unearthMechanic.config

import dev.wuason.adapter.AdapterData
import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * IStage represents a stage in a process, offering various functionalities
 * related to items, drops, sounds, and removal conditions.
 */
interface IStage {

    /**
     * Retrieves the current stage as an integer.
     *
     * @return The current stage number.
     */
    fun getStage(): Int


    /**
     * Retrieves the adapter data associated with the item ID.
     *
     * @return The AdapterData instance containing configuration or characteristics, or null if not available.
     */
    fun getAdapterData(): AdapterData?

    /**
     * Retrieves the amount of durability to remove from an item.
     *
     * @return the integer value representing the amount of durability to be removed.
     */
    fun getDurabilityToRemove(): Int

    /**
     * Retrieves the number of usages to be removed.
     *
     * @return the number of usages that should be removed.
     */
    fun getUsagesIaToRemove(): Int

    /**
     * The permission required to advance from stage
     *
     * @return returns the permission value set in the configuration
     */
    fun getPermissionStage(): String

    /**
     * Determines if only one drop is allowed in the current stage.
     *
     * @return true if only one drop is permitted; false otherwise.
     */
    fun isOnlyOneDrop(): Boolean

    /**
     * Determines if the item should be removed based on the stage configuration.
     *
     * @return true if the item should be removed; false otherwise.
     */
    fun isRemove(): Boolean

    /**
     * Determines if the item in the player's main hand should be removed.
     *
     * @return true if the item in the main hand should be removed, false otherwise.
     */
    fun isRemoveItemMainHand(): Boolean

    /**
     * Retrieves a list of item drops associated with the current stage.
     *
     * @return A list of `IDrop` instances representing the items that can be dropped.
     */
    fun getDrops(): List<IDrop>

    /**
     * Retrieves the amount by which the item in hand should be reduced.
     *
     * @return the integer value indicating how much the item in hand should be reduced.
     */
    fun getReduceItemHand(): Int

    /**
     * Retrieves a list of items associated with a particular stage or context.
     *
     * @return a List of IItem instances representing the items.
     */
    fun getItems(): List<IItem>

    /**
     * Determines if there is only a single item.
     *
     * @return true if only one item is present, false otherwise
     */
    fun isOnlyOneItem(): Boolean

    /**
     * Retrieves a list of sound effects associated with the stage.
     *
     * @return a List of ISound, each representing a sound effect with properties such as
     * soundId, volume, pitch, and delay.
     */
    fun getSounds(): List<ISound>

    /**
     * Retrieves the delay associated with the stage.
     *
     * @return the delay in milliseconds as a Long.
     */
    fun getDelay(): Long

    /**
     * Determines if there is a delay for the tool animation in the game.
     *
     * @return `true` if there is a delay for the tool animation, `false` otherwise.
     */
    fun isToolAnimDelay(): Boolean

    /**
     * Drops items at the provided location based on the implementation's drop mechanics.
     *
     * @param loc The location where the items will be dropped.
     */
    fun dropItems(loc: Location)

    /**
     * Adds items to the specified player's inventory based on certain stage conditions.
     *
     * @param player The player to whom items will be added.
     */
    fun addItems(player: Player)

}