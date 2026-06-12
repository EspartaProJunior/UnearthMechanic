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
     * Retrieves the amount of the tool item to remove from the player's inventory (any slot) when the stage is applied.
     *
     * @return the amount to consume from inventory, or 0 if not used.
     */
    fun getReduceItemInventory(): Int

    /**
     * If all items should be batched together or done one-by-one.
     *
     * @return true to batch all items, false to process one grouping at a time
     */
    fun isBatchedProcess(): Boolean

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
     * Attempts to add items to the player's inventory first, then drops at location if inventory is full.
     *
     * @param loc The location where the items will be dropped if inventory is full.
     * @param player The player whose inventory should receive the items first.
     */
    fun dropItems(loc: Location, player: Player)

    /**
     * Adds items to the specified player's inventory based on certain stage conditions.
     *
     * @param player The player to whom items will be added.
     */
    fun addItems(player: Player)

    /**
     * Retrieves the commands to execute when this stage is applied.
     *
     * Supported placeholder:
     * - {player}: name of the player who interacted
     *
     * @return a list of stage commands
     */
    fun getExecuteCommands(): List<IStageCommand>

    /**
     * Retrieves the amount of food points to add to the player when this stage is applied.
     *
     * @return the amount of food points to add.
     */
    fun getFoodAdd(): Int

    /**
     * Retrieves the amount of saturation to add to the player when this stage is applied.
     *
     * @return the amount of saturation to add.
     */
    fun getSaturationAdd(): Float


}