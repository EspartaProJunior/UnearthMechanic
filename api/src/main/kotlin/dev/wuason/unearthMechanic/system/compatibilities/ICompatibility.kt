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

    open fun isValid(loc: Location, expectedAdapterId: String?): Boolean {
        return isValidFurniture(loc, expectedAdapterId) || isValidBlock(loc, expectedAdapterId)
    }

    open fun isValidFurniture(loc: Location, expectedAdapterId: String?): Boolean = false
    open fun isValidBlock(loc: Location, expectedAdapterId: String?): Boolean = false

    open fun removeFurnitureByUUID(loc: Location, uuid: UUID?): Boolean = false

    /**
     * Indicates if the current given UUID is in the process of being removed (e.g., a furniture entity).
     */
    abstract fun isRemoving(location: Location): Boolean

    /**
     * Marks the given UUID as being removed, to prevent duplicate removal operations.
     */
    abstract fun setRemoving(location: Location)

    /**
     * Clears the removing mark for the given UUID after removal has completed.
     */
    abstract fun clearRemoving(location: Location)

    /**
     * Retrieves the UUID of a furniture entity at the given location, if present.
     *
     * @param location The location to check.
     * @return The UUID of the furniture entity, or null if none found.
     */
    abstract fun getFurnitureUUID(location: Location): UUID?

    open fun getCurrentBlockPropsFromEvent(
        event: Event,
        loc: Location
    ): Map<String, String> {
        return emptyMap()
    }

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
}