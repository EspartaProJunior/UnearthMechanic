package dev.wuason.unearthMechanic.system

import com.jeff_media.customblockdata.CustomBlockData
import com.jeff_media.morepersistentdatatypes.DataType
import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.UnearthMechanicPlugin
import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.utils.Utils
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.persistence.PersistentDataContainer

/**
 * Represents the data associated with a specific stage of a block at a certain location.
 *
 * @property location The location of the block.
 * @property stage The current stage number.
 * @property generic The generic type associated with the block.
 */
class StageData(private val location: Location, private val stage: Int, private val generic: IGeneric) {
    /**
     * Companion object containing utility methods and constants for managing stage data and custom block data.
     */
    companion object  {
        private fun plugin() = UnearthMechanicPlugin.getInstance()

        /**
         * A NamespacedKey used to uniquely identify and store location data within the plugin.
         */
        val NAMESPACED_LOC_KEY by lazy { NamespacedKey(plugin(), "loc") }
        /**
         * Persistent data key for storing a generic item ID associated with custom blocks.
         *
         * This key is used to identify and retrieve the custom item ID from the
         * PersistentDataContainer of a block. It is crucial for managing and maintaining
         * the state of blocks that have custom behavior and attributes within the plugin.
         */
        val NAMESPACED_ID_KEY by lazy { NamespacedKey(plugin(), "id") }
        /**
         * A constant key used to store and retrieve the current stage data from a block's persistent data container.
         * This key is namespaced and is created using the instance of the `UnearthMechanicPlugin`.
         */
        val NAMESPACED_CUR_STAGE_KEY by lazy { NamespacedKey(plugin(), "current_stage") }
        /**
         * A NamespacedKey instance utilized for identifying custom block data related to a stage mechanism.
         * The key is namespaced under the `UnearthMechanicPlugin` plugin with the identifier "stage".
         */
        val NAMESPACED_KEY by lazy { NamespacedKey(plugin(), "stage") }
        /**
         * Represents a unique key for safely deleting block stages in the UnearthMechanic plugin.
         *
         * This key is used in the PersistentDataContainer for blocks to mark them for safe deletion.
         *
         * Associated with the namespace of the UnearthMechanic plugin instance, ensuring it is unique
         * and does not clash with other plugins.
         */
        val NAMESPACED_SAFE_DELETE_KEY by lazy { NamespacedKey(plugin(), "stageSafeRemove") }
        /**
         * Represents a namespaced key used to identify a specific stage multiple in the plugin.
         *
         * This key is associated with the `stageMultiple` value within the `UnearthMechanicPlugin` instance.
         * It is primarily used in methods to interact with custom block data, particularly for setting,
         * checking, or removing the `stageMultiple` data in a block's persistent data container.
         */
        val NAMESPACED_MULTIPLE by lazy { NamespacedKey(plugin(), "stageMultiple") }

        /**
         * Marks the block at the specified location for safe deletion.
         *
         * @param loc the location of the block to be marked for safe deletion
         */
        fun applySafeDelete(loc: Location) {
            val block: Block = loc.block
            applySafeDelete(block)
        }

        /**
         * Applies the "safe delete" attribute to the specified block.
         * This attribute, once applied, marks the block to prevent it from accidental deletion.
         *
         * @param block The block to which the "safe delete" attribute will be applied.
         */
        fun applySafeDelete(block: Block) {
            val data: PersistentDataContainer = CustomBlockData(block, plugin())
            data.set(NAMESPACED_SAFE_DELETE_KEY, DataType.BOOLEAN, true)
        }

        /**
         * Determines if the block at the given location has the "safe delete" property.
         *
         * @param loc the location of the block to be checked
         * @return true if the block has the "safe delete" property, false otherwise
         */
        fun hasSafeDelete(loc: Location): Boolean {
            val block: Block = loc.block
            return hasSafeDelete(block)
        }

        /**
         * Checks if the given block has the safe delete metadata.
         *
         * @param block the block to check for the safe delete metadata.
         * @return true if the block has the safe delete metadata, false otherwise.
         */
        fun hasSafeDelete(block: Block): Boolean {
            return CustomBlockData.hasCustomBlockData(block, plugin()) && CustomBlockData(block, UnearthMechanicPlugin.getInstance()).has(
                NAMESPACED_SAFE_DELETE_KEY, DataType.BOOLEAN)
        }

        /**
         * Removes the safe delete metadata from a block at the given location.
         *
         * @param loc the location of the block from which to remove the safe delete
         */
        fun removeSafeDelete(loc: Location) {
            val block: Block = loc.block
            removeSafeDelete(block)
        }

        /**
         * Removes the safe delete marker from the specified block if it exists.
         *
         * @param block the block from which to remove the safe delete marker
         */
        fun removeSafeDelete(block: Block) {
            if (!hasSafeDelete(block)) return
            val data: PersistentDataContainer = CustomBlockData(block, plugin())
            data.remove(NAMESPACED_SAFE_DELETE_KEY)
        }

        /**
         * Applies a "multiple" state to the block at the specified location.
         *
         * @param loc the location of the block to which the "multiple" state will be applied
         */
        fun applyMultiple(loc: Location) {
            val block: Block = loc.block
            applyMultiple(block)
        }

        /**
         * Sets a flag indicating that the provided block is part of a multiple-block operation.
         *
         * @param block The block to which the multiple-block operation flag should be applied.
         */
        fun applyMultiple(block: Block) {
            val data: PersistentDataContainer = CustomBlockData(block, plugin())
            data.set(NAMESPACED_MULTIPLE, DataType.BOOLEAN, true)
        }

        /**
         * Checks if the given location has multiple data associated with it.
         *
         * @param loc the Location to be checked.
         * @return true if the location has multiple data, false otherwise.
         */
        fun hasMultiple(loc: Location): Boolean {
            val block: Block = loc.block
            return hasMultiple(block)
        }

        /**
         * Checks if the given block has the "multiple" data attribute set to true.
         *
         * @param block the block to check for the "multiple" data attribute
         * @return `true` if the block has the "multiple" data attribute set, otherwise `false`
         */
        fun hasMultiple(block: Block): Boolean {
            return CustomBlockData.hasCustomBlockData(block, plugin()) && CustomBlockData(block, UnearthMechanicPlugin.getInstance()).has(
                NAMESPACED_MULTIPLE, DataType.BOOLEAN)
        }

        /**
         * Removes multiple blocks based on the location.
         *
         * @param loc the location whose associated block(s) will be processed for removal
         */
        fun removeMultiple(loc: Location) {
            val block: Block = loc.block
            removeMultiple(block)
        }

        /**
         * Removes multiple data from the provided block, if it exists.
         *
         * @param block The block from which to remove multiple data.
         */
        fun removeMultiple(block: Block) {
            if (!hasMultiple(block)) return
            val data: PersistentDataContainer = CustomBlockData(block, plugin())
            data.remove(NAMESPACED_MULTIPLE)
        }

        /**
         * Retrieves the StageData associated with the provided Block, if it exists.
         *
         * @param block The block from which to retrieve the StageData.
         * @return The StageData for the given block, or null if none exists.
         */
        fun fromBlock(block: Block): StageData? {
            if(!CustomBlockData.hasCustomBlockData(block, plugin())) {
                return null
            }
            val data: PersistentDataContainer = CustomBlockData(block, plugin())
            if (!data.has(NAMESPACED_KEY, DataType.BOOLEAN)) {
                return null
            }
            val loc: Location = data.get(NAMESPACED_LOC_KEY, DataType.LOCATION) ?: return null
            val id: String = data.get(NAMESPACED_ID_KEY, DataType.STRING) ?: return null
            val stage: Int = data.get(NAMESPACED_CUR_STAGE_KEY, DataType.INTEGER) ?: return null
            val generic: IGeneric = plugin().getConfigManager().getGenerics()[id] ?: return null
            return StageData(loc, stage, generic)
        }

        /**
         * Converts a given location into corresponding stage data.
         *
         * @param loc The location to be converted.
         * @return The resulting StageData object, or null if not applicable.
         */
        fun fromLoc(loc: Location): StageData? {
            val block: Block = loc.block
            return fromBlock(block)
        }

        /**
         * Saves stage data at the specified location.
         *
         * @param loc the location where the stage data will be saved
         * @param StageData the stage data to be saved
         */
        fun saveStageData(loc: Location, StageData: StageData) {
            val block: Block = loc.block
            saveStageData(block, StageData)
        }

        /**
         * Saves the stage data to the specified block.
         *
         * @param block The block where the stage data should be saved.
         * @param StageData The stage data that includes location, the current stage, and generic information.
         */
        fun saveStageData(block: Block, StageData: StageData) {
            val data: PersistentDataContainer = CustomBlockData(block, plugin())
            data.set(NAMESPACED_LOC_KEY, DataType.LOCATION, StageData.location)
            data.set(NAMESPACED_ID_KEY, DataType.STRING, StageData.generic.getId())
            data.set(NAMESPACED_CUR_STAGE_KEY, DataType.INTEGER, StageData.stage)
            data.set(NAMESPACED_KEY, DataType.BOOLEAN, true)
        }

        /**
         * Removes the stage data associated with the block at the given location.
         *
         * @param loc The location of the block from which to remove the stage data.
         */
        fun removeStageData(loc: Location) {
            val block: Block = loc.block
            removeStageData(block)
        }

        /**
         * Removes stage data associated with the given block.
         *
         * @param block The block from which the stage data will be removed.
         */
        fun removeStageData(block: Block) {
            if (!hasStageData(block)) return
            val data: PersistentDataContainer = CustomBlockData(block, plugin())
            data.remove(NAMESPACED_LOC_KEY)
            data.remove(NAMESPACED_ID_KEY)
            data.remove(NAMESPACED_CUR_STAGE_KEY)
            data.remove(NAMESPACED_KEY)
        }

        /**
         * Compares the given StageData with the block at the specified location.
         *
         * @param stageData The StageData to be compared.
         * @param loc The location of the block to be compared against the StageData.
         * @return True if the block at the location matches the StageData, otherwise false.
         */
        fun compare(stageData: StageData, loc: Location): Boolean {
            val block: Block = loc.block
            return compare(stageData, block)
        }

        /**
         * Compares the specified stage data with the data stored in the given block.
         *
         * @param stageData the data representing the stage, including location, current stage, and generic information
         * @param block the block whose data is being compared
         * @return true if the stage data matches the data stored in the block, false otherwise
         */
        fun compare(stageData: StageData, block: Block): Boolean {
            val data: PersistentDataContainer = CustomBlockData(block, plugin())
            if (!data.has(NAMESPACED_KEY, DataType.BOOLEAN)) {
                return true
            }
            val loc: Location = data.get(NAMESPACED_LOC_KEY, DataType.LOCATION) ?: return false
            val id: String = data.get(NAMESPACED_ID_KEY, DataType.STRING) ?: return false
            val stage: Int = data.get(NAMESPACED_CUR_STAGE_KEY, DataType.INTEGER) ?: return false
            return Utils.isExactBlockLocation(loc, stageData.getLocation()) && stageData.generic.getId() == id && stageData.stage == stage
        }

        /**
         * Checks whether the specified location has stage data associated with it.
         *
         * @param loc The location to check for stage data.
         * @return `true` if the location has stage data, `false` otherwise.
         */
        fun hasStageData(loc: Location): Boolean {
            val block: Block = loc.block
            return hasStageData(block)
        }

        /**
         * Checks if the specified block contains stage data.
         *
         * @param block the block to be checked for stage data
         * @return true if the block contains stage data, false otherwise
         */
        fun hasStageData(block: Block): Boolean {
            return CustomBlockData.hasCustomBlockData(block, plugin()) && CustomBlockData(block, UnearthMechanicPlugin.getInstance()).has(
                NAMESPACED_KEY, DataType.BOOLEAN)
        }
    }

    /**
     * Retrieves the location associated with this StageData.
     *
     * @return the current Location object representing the location in the StageData.
     */
    fun getLocation(): Location {
        return location
    }

    /**
     * Gets the current stage value.
     *
     * @return the current stage as an integer.
     */
    fun getStage(): Int {
        return stage
    }

    /**
     * Retrieves the generic data associated with this stage data.
     *
     * @return the generic data as an IGeneric instance
     */
    fun getGeneric(): IGeneric {
        return generic
    }


    fun getActualAdapterData(): AdapterData {
        return Utils.getActualAdapterData(this)
    }

    /**
     * Retrieves the stage data for the previous stage.
     *
     * @return A StageData object representing the previous stage, or null if the current stage is 0.
     */
    fun getBackStageData(): StageData? {
        if (stage == 0) return null
        return StageData(location, stage - 1, generic)
    }
}