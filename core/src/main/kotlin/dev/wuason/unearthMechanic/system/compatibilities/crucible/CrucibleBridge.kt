package dev.wuason.unearthMechanic.system.compatibilities.crucible

import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Entity

interface CrucibleBridge {
    fun isCustomBlock(block: Block): Boolean
    fun getBlockId(block: Block): String?

    fun isFurnitureEntity(entity: Entity): Boolean
    fun getFurnitureId(entity: Entity): String?
    fun getFurnitureAnchor(entity: Entity): Location?
    fun findFurnitureEntity(location: Location): Entity?

    fun placeBlock(id: String, location: Location)
    fun removeBlock(location: Location)

    fun spawnFurniture(id: String, location: Location): Entity?
    fun removeFurniture(entity: Entity)
}