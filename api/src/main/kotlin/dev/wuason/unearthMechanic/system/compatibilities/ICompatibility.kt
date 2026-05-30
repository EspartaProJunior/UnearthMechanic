package dev.wuason.unearthMechanic.system.compatibilities

import dev.wuason.adapter.AdapterComp
import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.IStage
import dev.wuason.unearthMechanic.system.ILiveTool
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * Interface representing compatibility handling in the system. This interface must be implemented
 * by classes that handle compatibility-related events and actions within the system.
 */
abstract class ICompatibility(
    private val pluginName: String,
    private val adapterComp: AdapterComp
) : Listener {


    /**
     * Checks if the plugin associated with the compatibility interface is loaded.
     *
     * @return true if the plugin is loaded, false otherwise.
     */
    fun loaded(): Boolean {
        return Bukkit.getPluginManager().getPlugin(pluginName) != null
    }

    /**
     * Determines if the compatibility interface is enabled.
     * The method checks whether the plugin is loaded and enabled in the Bukkit plugin manager.
     *
     * @return true if the plugin is loaded and enabled, false otherwise.
     */
    fun enabled(): Boolean {
        return loaded() && Bukkit.getPluginManager().isPluginEnabled(pluginName)
    }

    /**
     * Retrieves the name of the plugin associated with the compatibility interface.
     *
     * @return The name of the plugin as a string.
     */
    fun name(): String {
        return pluginName
    }

    /**
     * Retrieves the `AdapterComp` instance associated with the compatibility interface.
     *
     * @return The `AdapterComp` instance.
     */
    fun adapterComp(): AdapterComp {
        return adapterComp
    }

    /**
     * Constructs a path string by combining the type from the adapter component
     * and the provided identifier.
     *
     * @param id The identifier to be included in the constructed path.
     * @return A string representing the constructed path in the format "type:id".
     */
    fun getPath(id: String): String {
        return adapterComp.type + ":" + id
    }

    /**
     * Handles the processing of a specific stage when an event occurs.
     *
     * @param player The player involved in the event.
     * @param itemAdapterData The adapter data associated with the item relevant to the stage.
     * @param event The event triggering the stage handling.
     * @param loc The location where the event is taking place.
     * @param toolUsed The tool used by the player during the event.
     * @param generic A generic instance related to the item and stage.
     * @param stage The current stage to be handled.
     */
    abstract fun handleStage(
        player: Player,
        itemAdapterData: AdapterData,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    )

    abstract fun handleSequenceStage(
        player: Player,
        itemAdapterData: AdapterData,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    )

    /**
     * Handles the removal of an item from a specific stage when an event occurs.
     *
     * @param player The player involved in the event.
     * @param event The event that triggered the removal.
     * @param loc The location where the event took place.
     * @param toolUsed The tool used by the player during the event.
     * @param generic The generic item involved in the stage.
     * @param stage The specific stage from which the item is to be removed.
     */
    abstract fun handleRemove(
        player: Player, event: Event, loc: Location, toolUsed: ILiveTool, generic: IGeneric, stage: IStage
    )

    /**
     * Computes the hash code for the provided parameters.
     *
     * @param player The player involved in the event.
     * @param event The event that triggered the hash code computation.
     * @param loc The location related to the event.
     * @param toolUsed The tool used by the player.
     * @param generic The generic item related to the event.
     * @param stage The current stage of the event.
     * @return The computed hash code as an integer.
     */
    abstract fun hashCode(
        player: Player, event: Event, loc: Location, toolUsed: ILiveTool, generic: IGeneric, stage: Int
    ): Int

    /**
     * Retrieves the ItemStack in the player's hand during an event.
     *
     * @param event The event during which the item in hand is to be retrieved.
     * @return The ItemStack in the player's hand, or null if not available.
     */
    abstract fun getItemHand(event: Event): ItemStack?

    /**
     * Retrieves the block face for the given event.
     *
     * @param event The event for which the block face should be determined.
     * @return The block face associated with the event, or null if none is found.
     */
    abstract fun getBlockFace(event: Event): org.bukkit.block.BlockFace?

    /**
     * This method is triggered when the class implementing the ICompatibility interface is loaded.
     * It can be used to perform any necessary initialization procedures.
     */
    open fun onLoad() {}

    /**
     * Checks if the block or furniture at the given location is still valid for processing sequences.
     * Default implementation always returns true.
     *
     * @param location The location to validate.
     * @return true if valid, false otherwise.
     */
    open fun isValidUUID(loc: Location, expectedAdapterId: String?, expectedUuid: UUID?): Boolean = false

    /**
     * Checks if the current block or furniture at the given location matches the expected adapter ID.
     *
     * This method validates both furniture and block targets using the compatibility implementation.
     *
     * @param loc The location to validate.
     * @param expectedAdapterId The expected adapter ID to compare against.
     * @return true if either the furniture or block at the location is valid, false otherwise.
     */
    open fun isValid(loc: Location, expectedAdapterId: String?): Boolean {
        return isValidFurniture(loc, expectedAdapterId) || isValidBlock(loc, expectedAdapterId)
    }

    /**
     * Checks if the furniture at the given location matches the expected adapter ID.
     *
     * @param loc The location to check.
     * @param expectedAdapterId The expected furniture adapter ID.
     * @return true if the furniture matches the expected adapter ID, false otherwise.
     */
    open fun isValidFurniture(loc: Location, expectedAdapterId: String?): Boolean = false
    /**
     * Checks if the block at the given location matches the expected adapter ID.
     *
     * @param loc The location to check.
     * @param expectedAdapterId The expected block adapter ID.
     * @return true if the block matches the expected adapter ID, false otherwise.
     */
    open fun isValidBlock(loc: Location, expectedAdapterId: String?): Boolean = false

    /**
     * Removes a furniture entity at the given location using its UUID when supported.
     *
     * This is useful when the compatibility layer needs to remove a specific furniture entity
     * instead of removing any furniture found at the location.
     *
     * @param loc The location where the furniture should be removed.
     * @param uuid The UUID of the furniture entity to remove, or null if unavailable.
     * @return true if the furniture was removed successfully, false otherwise.
     */
    open fun removeFurnitureByUUID(loc: Location, uuid: UUID?): Boolean = false

    /**
     * Indicates if the target at the given location is currently being removed.
     *
     * This prevents duplicate removal operations while a compatibility implementation
     * is already processing the same location.
     *
     * @param location The location to check.
     * @return true if the location is currently marked as removing, false otherwise.
     */
    abstract fun isRemoving(location: Location): Boolean

    /**
     * Marks the target at the given location as being removed.
     *
     * This is used to prevent duplicate removal operations during transformations.
     *
     * @param location The location to mark as removing.
     */
    abstract fun setRemoving(location: Location)

    /**
     * Clears the removing mark for the given location after the removal has completed.
     *
     * @param location The location whose removing mark should be cleared.
     */
    abstract fun clearRemoving(location: Location)

    /**
     * Retrieves the UUID of a furniture entity at the given location, if present.
     *
     * @param location The location to check.
     * @return The UUID of the furniture entity, or null if none found.
     */
    abstract fun getFurnitureUUID(location: Location): UUID?

    open fun getFurnitureUUID(
        loc: Location,
        expectedAdapterId: String
    ): UUID? {
        return getFurnitureUUID(loc)
    }

    /**
     * Retrieves the current block properties from the event context.
     *
     * This can be used to preserve compatibility-specific block data such as facing,
     * axis, rotation, or other placement properties before applying a transformation.
     *
     * @param event The event from which the block properties may be extracted.
     * @param loc The location of the current block.
     * @return a map containing the current block properties, or an empty map if unavailable.
     */
    open fun getCurrentBlockPropsFromEvent(
        event: Event,
        loc: Location
    ): Map<String, String> {
        return emptyMap()
    }

    /**
     * Retrieves the current adapter data present at the given location.
     *
     * This can be used when the compatibility layer needs to detect the active block
     * or furniture before deciding how to transform or remove it.
     *
     * @param event The event related to the current interaction.
     * @param loc The location to inspect.
     * @return the current AdapterData at the location, or null if none is available.
     */
    open fun getCurrentAdapterDataAt(
        event: Event,
        loc: Location
    ): AdapterData? {
        return null
    }

    /**
     * Handles a cross-compatibility remove operation before placing or applying the target transformation.
     *
     * This allows one compatibility implementation to remove its own block or furniture before
     * another compatibility implementation places the new target.
     *
     * @param player The player involved in the event.
     * @param event The event that triggered the transformation.
     * @param loc The location where the target is being transformed.
     * @param toolUsed The tool used by the player.
     * @param generic The generic configuration being processed.
     * @param stage The current stage being applied.
     * @param targetCompatibility The compatibility that will handle the target transformation.
     * @return true if the cross-compatibility remove was handled, false otherwise.
     */
    open fun handleCrossCompatibilityRemoveBeforeTarget(
        player: Player,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage,
        targetCompatibility: ICompatibility
    ): Boolean {
        return false
    }

    /**
     * Places the new furniture first and then removes the old furniture when supported.
     *
     * This can reduce visual flickering during furniture transformations by avoiding
     * a remove-then-place gap between both operations.
     *
     * @param loc The location where the furniture transformation occurs.
     * @param currentAdapterId The adapter ID of the currently placed furniture.
     * @param targetAdapterId The adapter ID of the furniture that should be placed.
     * @param oldUuid The UUID of the old furniture entity, or null if unavailable.
     * @return the UUID of the newly placed furniture, or null if the operation is not supported.
     */
    open fun placeNewFurnitureThenRemoveOld(
        loc: Location,
        currentAdapterId: String,
        targetAdapterId: String,
        oldUuid: UUID?
    ): UUID? {
        return null
    }
}