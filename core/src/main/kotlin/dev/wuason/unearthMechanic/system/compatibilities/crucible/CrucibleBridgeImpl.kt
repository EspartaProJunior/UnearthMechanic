package dev.wuason.unearthMechanic.system.compatibilities.crucible

import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.TextDisplay

class CrucibleBridgeImpl : CrucibleBridge {

    override fun isCustomBlock(block: Block): Boolean {
        return getBlockId(block) != null
    }

    override fun getBlockId(block: Block): String? {
        // TODO:
        // Reemplaza esto por la llamada REAL del API de MythicCrucible.
        // Debe devolver algo como "my_block_id" si este block es de Crucible,
        // o null si no lo es.
        return null
    }

    override fun isFurnitureEntity(entity: Entity): Boolean {
        return getFurnitureId(entity) != null ||
                entity is ItemFrame ||
                entity is ItemDisplay ||
                entity is TextDisplay ||
                entity is Interaction
    }

    override fun getFurnitureId(entity: Entity): String? {
        // TODO:
        // Reemplaza esto por la llamada REAL del API de MythicCrucible.
        // Debe devolver algo como "my_furniture_id" si la entity pertenece
        // a un furniture de Crucible, o null si no lo es.
        return null
    }

    override fun getFurnitureAnchor(entity: Entity): Location? {
        // TODO:
        // Si tu API te da una "base location" o "anchor", úsala aquí.
        // De momento usamos el bloque donde está la entidad.
        return entity.location.block.location
    }

    override fun findFurnitureEntity(location: Location): Entity? {
        val world = location.world ?: return null
        val keyLoc = location.block.location
        val center = keyLoc.clone().add(0.5, 0.5, 0.5)

        return world.getNearbyEntities(center, 1.5, 1.5, 1.5)
            .firstOrNull { entity ->
                entity.isValid &&
                        !entity.isDead &&
                        getFurnitureAnchor(entity)?.block?.location == keyLoc &&
                        getFurnitureId(entity) != null
            }
    }

    override fun placeBlock(id: String, location: Location) {
        // TODO:
        // Llamada REAL al API de Crucible para colocar custom block.
        //
        // Ejemplo conceptual:
        // crucibleApi.blocks().place(id, location)
    }

    override fun removeBlock(location: Location) {
        // TODO:
        // Llamada REAL al API de Crucible para romper/remover custom block.
        //
        // Ejemplo conceptual:
        // crucibleApi.blocks().remove(location)
    }

    override fun spawnFurniture(id: String, location: Location): Entity? {
        // TODO:
        // Llamada REAL al API de Crucible para spawnear furniture y devolver
        // su entidad principal.
        //
        // Ejemplo conceptual:
        // return crucibleApi.furniture().spawn(id, location)?.entity
        return null
    }

    override fun removeFurniture(entity: Entity) {
        // TODO:
        // Llamada REAL al API de Crucible para remover furniture.
        // Si tu API necesita un wrapper o manager, resuélvelo aquí.
        //
        // Como fallback temporal:
        entity.remove()
    }
}