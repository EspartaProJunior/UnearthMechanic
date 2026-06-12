package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite

import net.momirealms.craftengine.core.world.BlockPos
import org.bukkit.Location
import org.bukkit.block.Block

object TermiteKeys {
    fun key(block: Block): String = "${block.world.name}:${block.x},${block.y},${block.z}"
    fun key(location: Location): String = "${location.world?.name}:${location.blockX},${location.blockY},${location.blockZ}"
    fun key(worldName: String, pos: BlockPos): String = "$worldName:${pos.x()},${pos.y()},${pos.z()}"
}
