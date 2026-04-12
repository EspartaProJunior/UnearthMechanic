package dev.wuason.unearthMechanic.utils

import dev.wuason.adapter.Adapter
import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.IStage
import dev.wuason.unearthMechanic.system.StageData
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import kotlin.jvm.optionals.getOrNull
import kotlin.math.absoluteValue

/**
 * A utility class that provides various helper methods for handling stages, blocks, and hash code calculations.
 */
class Utils {
    /**
     * Companion object for utility functions related to stages and block manipulation.
     */
    companion object {
        /**
         * Checks if the provided stage is the last stage in the generic object's stage list.
         *
         * @param stage The current stage index to check.
         * @param generic The IGeneric object containing the list of stages.
         * @return True if the provided stage index is the last stage, otherwise false.
         */
        fun isLastStage(stage: Int, generic: IGeneric): Boolean {
            return stage == generic.getStages().size - 1
        }

        /**
         * Determines if the given stage is the last stage in the generic stages.
         *
         * @param stage The current stage to check.
         * @param generic The generic object containing the list of stages.
         * @return True if the given stage is the last stage, otherwise false.
         */
        fun isLastStage(stage: IStage, generic: IGeneric): Boolean {
            return stage.getStage() == generic.getStages().size - 1
        }

        /**
         * Retrieves the actual item ID for the given stage data.
         *
         * @param stageData the data representing the stage, including location, current stage, and generic information
         * @return the item ID associated with the current stage if available, otherwise the base item ID
         */
        fun getActualAdapterData(stageData: StageData): AdapterData {
            for (stage in stageData.getStage() downTo 0) {
                stageData.getGeneric().getStages()[stage].getAdapterData()?.let { return it }
            }
            return stageData.getGeneric().getBaseStage().getAdapterData()!!
        }

        /**
         * Collects a list of blocks surrounding a given block in a specified pattern based on direction and player orientation.
         *
         * @param block Initial block to start from.
         * @param size The size parameter determines the range along the Y-axis.
         * @param deep The deep parameter determines the range along one of the horizontal axes.
         * @param depth Determines how deep the search should go.
         * @param player Player whose orientation and facing direction influence the block collection.
         * @param blockFace The face of the block relative to the player's orientation.
         * @return A list of blocks surrounding the initial block based on specified parameters.
         * @throws IllegalArgumentException if the depth parameter is 0.
         */
        fun blockAround(block: Block, size: Int, deep: Int, depth: Int, player: Player, blockFace: BlockFace): List<Block> {
            if (depth == 0) throw IllegalArgumentException("Depth cannot be 0")
            val blocks: MutableList<Block> = mutableListOf()
            for (d in 0 until depth.absoluteValue) {
                for (i in -deep..deep) {
                    for (y in -size..size) {

                        if (blockFace.direction.blockY != 0) {
                            if (player.facing.direction.blockZ != 0) blocks.add(block.getRelative(i, d * (blockFace.direction.blockY * -1), y))
                            else blocks.add(block.getRelative(y, d * (blockFace.direction.blockY * -1), i))
                        }
                        else {
                            if (player.facing.direction.blockZ != 0) blocks.add(block.getRelative(i, y, d * player.facing.direction.blockZ))
                            else blocks.add(block.getRelative(d * player.facing.direction.blockX, y, i))
                        }

                    }

                }
            }
            return blocks
        }

        /**
         * Calculates a combined hash code for the given array of objects.
         *
         * @param objects The array of objects for which the hash code is calculated.
         * @return The combined hash code of the objects. If the array is empty, returns -1.
         */
        fun calculateHashCode(vararg objects: Any): Int {
            if (objects.isEmpty()) return -1
            if (objects.size == 1) return objects[0].hashCode()
            var result = 1
            for (element in objects) {
                result = 31 * result + element.hashCode()
            }
            return result
        }

        /**
         * Checks if the given location is an exact block location.
         *
         * @param loc1 The first location to compare.
         * @param loc2 The second location to compare.
         * @return True if the locations are the same block location, otherwise false.
         */

        fun isExactBlockLocation(loc1: Location, loc2: Location): Boolean {
            return loc1.blockX == loc2.blockX && loc1.blockY == loc2.blockY && loc1.blockZ == loc2.blockZ
        }

        fun String.toAdapter(): AdapterData? {
            return Adapter.getAdapterData(this).getOrNull()
        }

    }
}