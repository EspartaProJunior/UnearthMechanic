package dev.wuason.unearthMechanic.system.compatibilities.or

import com.nexomc.nexo.api.NexoFurniture
import dev.wuason.adapter.AdapterComp
import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.UnearthMechanicPlugin
import dev.wuason.unearthMechanic.config.*
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.StageData
import dev.wuason.unearthMechanic.system.StageManager
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import dev.wuason.unearthMechanic.utils.Utils
import io.th0rgal.oraxen.api.OraxenBlocks
import io.th0rgal.oraxen.api.OraxenFurniture
import io.th0rgal.oraxen.api.events.furniture.OraxenFurnitureBreakEvent
import io.th0rgal.oraxen.api.events.furniture.OraxenFurnitureInteractEvent
import io.th0rgal.oraxen.api.events.furniture.OraxenFurniturePlaceEvent
import io.th0rgal.oraxen.api.events.noteblock.OraxenNoteBlockBreakEvent
import io.th0rgal.oraxen.api.events.noteblock.OraxenNoteBlockInteractEvent
import io.th0rgal.oraxen.api.events.stringblock.OraxenStringBlockBreakEvent
import io.th0rgal.oraxen.api.events.stringblock.OraxenStringBlockInteractEvent
import io.th0rgal.oraxen.utils.drops.Drop
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.Collections
import java.util.UUID

class OraxenImpl(
    pluginName: String,
    private val core: UnearthMechanicPlugin,
    private val stageManager: StageManager,
    adapterComp: AdapterComp
): ICompatibility(
    pluginName,
    adapterComp
) {
    private val removedLocations = Collections.synchronizedSet(mutableSetOf<Location>())

    override fun isRemoving(location: Location): Boolean {
        return removedLocations.contains(location)
    }

    override fun setRemoving(location: Location) {
        removedLocations.add(location)
    }

    override fun clearRemoving(location: Location) {
        removedLocations.remove(location)
    }

    companion object {
        private val rotationMap = Collections.synchronizedMap(mutableMapOf<Location, Pair<Float, Float>>())
        val itemFrameRotationMap = Collections.synchronizedMap(mutableMapOf<Location, org.bukkit.Rotation>())
    }

    fun removeStageData(location: Location){
        StageData.removeStageData(location)
    }

    override fun getFurnitureUUID(location: Location): UUID? {
        val world = location.world ?: return null

        val entities = world.getNearbyEntities(location, 1.0, 1.0, 1.0)
        for (entity in entities) {
            try {
                val furniture = OraxenFurniture.isFurniture(entity)
                if (furniture != null) {
                    return entity.uniqueId
                }
            } catch (e: Exception) {
                // Si lanza error es porque esa entidad no es un mueble válido
                continue
            }
        }

        return null
    }

    override fun isValidUUID(loc: Location, expectedAdapterId: String?, expectedUuid: UUID?): Boolean {
        val keyLoc = loc.block.location
        val world = keyLoc.world ?: return false
        val cleanId = expectedAdapterId?.removePrefix("oraxen:")
        val center = keyLoc.clone().add(0.5, 0.5, 0.5)
        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        for (entity in nearby) {
            if (entity.location.block.location != keyLoc) continue
            if (!entity.isValid || entity.isDead) continue
            if (!OraxenFurniture.isFurniture(entity)) continue

            if (expectedUuid != null) {
                if (entity.uniqueId == expectedUuid) return true
                continue
            }

            val mechanic = try { OraxenFurniture.getFurnitureMechanic(entity) } catch (_: Throwable) { null }
            if (cleanId != null && mechanic != null && mechanic.itemID.equals(cleanId, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    override fun isValidFurniture(loc: Location, expectedAdapterId: String?): Boolean {
        val keyLoc = loc.block.location
        val world = keyLoc.world ?: return false
        val cleanId = expectedAdapterId?.removePrefix("oraxen:")
        val center = keyLoc.clone().add(0.5, 0.5, 0.5)
        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        for (entity in nearby) {
            if (entity.location.block.location != keyLoc) continue
            if (!entity.isValid || entity.isDead) continue
            if (!OraxenFurniture.isFurniture(entity)) continue

            val mechanic = try { OraxenFurniture.getFurnitureMechanic(entity) } catch (_: Throwable) { null }

            if (cleanId == null) return true
            if (mechanic != null && mechanic.itemID.equals(cleanId, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    override fun isValidBlock(loc: Location, expectedAdapterId: String?): Boolean {
        val cleanId = expectedAdapterId?.removePrefix("oraxen:") ?: return loc.block.type != org.bukkit.Material.AIR

        return try {
            val noteId = OraxenBlocks.getNoteBlockMechanic(loc.block)?.itemID
            val stringId = OraxenBlocks.getStringMechanic(loc.block)?.itemID
            noteId.equals(cleanId, ignoreCase = true) || stringId.equals(cleanId, ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    override fun isValid(loc: Location, expectedAdapterId: String?): Boolean {
        return isValidFurniture(loc, expectedAdapterId) || isValidBlock(loc, expectedAdapterId)
    }

    override fun removeFurnitureByUUID(loc: Location, uuid: UUID?): Boolean {
        if (uuid == null) return false

        val keyLoc = loc.block.location
        val world = keyLoc.world ?: return false
        val center = keyLoc.clone().add(0.5, 0.5, 0.5)

        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        for (entity in nearby) {
            if (entity.location.block.location != keyLoc) continue
            if (!entity.isValid || entity.isDead) continue
            if (entity.uniqueId != uuid) continue
            if (!OraxenFurniture.isFurniture(entity)) continue

            val mechanic = try { OraxenFurniture.getFurnitureMechanic(entity) } catch (_: Throwable) { null }

            OraxenFurniture.remove(entity, null, null)

            // extra clean
            cleanupFurnitureEntitiesOraxen(keyLoc)

            return true
        }

        return false
    }

    private fun cleanupFurnitureEntitiesOraxen(loc: Location) {
        val world = loc.world ?: return
        val center = loc.clone().add(0.5, 0.5, 0.5)

        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        for (entity in nearby) {
            if (entity.location.block.location != loc) continue
            if (!entity.isValid || entity.isDead) continue

            if (OraxenFurniture.isFurniture(entity)) {
                entity.remove()
            }
        }
    }

    @EventHandler
    fun onInteractBlock(event: OraxenNoteBlockInteractEvent) {
        if (event.hand == EquipmentSlot.HAND && event.action == Action.RIGHT_CLICK_BLOCK) {
            stageManager.interact(
                event.player,
                getPath(event.mechanic.itemID),
                event.block.location,
                event,
                this
            )
        }
    }

    @EventHandler
    fun onInteractBlock(event: OraxenStringBlockInteractEvent) {
        if (event.hand == EquipmentSlot.HAND) {
            stageManager.interact(
                event.player,
                getPath(event.mechanic.itemID),
                event.block.location,
                event,
                this
            )
        }
    }

    @EventHandler
    fun onInteractFurniture(event: OraxenFurnitureInteractEvent) {
        if (stageManager.isTransitioning(event.baseEntity.location.block.location)) {
            event.isCancelled = true
            return
        }

        if (event.hand == EquipmentSlot.HAND) {
            stageManager.interact(
                event.player,
                getPath(event.mechanic.itemID),
                event.baseEntity.location,
                event,
                this
            )
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBreakBlock(event: OraxenNoteBlockBreakEvent) {
        StageData.removeStageData(event.block)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBreakBlock(event: OraxenStringBlockBreakEvent) {
        StageData.removeStageData(event.block)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onFurnitureBreak(event: OraxenFurnitureBreakEvent) {
        val loc = event.baseEntity.location.block.location
        if (stageManager.isTransitioning(loc)) {
            event.isCancelled = true
            return
        }

        removeStageData(loc)
        setRemoving(loc)

        if(!isRemoving(loc)){
            if(!stageManager.activeSequences.contains(loc)){
                clearRemoving(loc)
            }
        }
    }

    @EventHandler
    fun onFurniturePlace(event: OraxenFurniturePlaceEvent) {
        Bukkit.getScheduler().runTaskLater(UnearthMechanic.getInstance(), Runnable {
            clearRemoving(event.baseEntity.location.block.location)
        }, 3L)
    }



    private fun placeBlock(itemAdapterData: AdapterData, location: Location) {
        OraxenBlocks.place(itemAdapterData.id, location)
    }

    private fun breakBlock(location: Location, player: Player) {
        OraxenBlocks.remove(location, player)
    }

    private fun placeFurniture(
        itemAdapterData: AdapterData,
        location: Location,
        blockFace: BlockFace,
        yaw: Float
    ) {
        OraxenFurniture.getFurnitureMechanic(itemAdapterData.id).place(location, yaw, blockFace)
    }
    private fun placeFurniture(
        itemAdapterData: AdapterData,
        location: Location,
    ) {
        OraxenFurniture.getFurnitureMechanic(itemAdapterData.id).place(location, 0f, BlockFace.UP)
    }

    private fun breakFurniture(entity: Entity, player: Player, id: String) {
        OraxenFurniture.remove(entity, player, Drop(mutableListOf(), false, false, id))
    }

    override fun handleStage(
        player: Player,
        itemAdapterData: AdapterData,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    ) {
        if (stage is IBlockStage) {
            handleBlockStage(player, itemAdapterData, event, loc, toolUsed, generic, stage)
        } else if (stage is IFurnitureStage) {
            Bukkit.getScheduler().runTaskLater(UnearthMechanic.getInstance(), Runnable {
                handleFurnitureStage(player, itemAdapterData, event, loc, toolUsed, generic, stage)
            }, 2L)
        }
    }

    override fun handleSequenceStage(
        player: Player,
        itemAdapterData: AdapterData,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    ) {
        if (stage is IBlockStage) {
            handleBlockStage(player, itemAdapterData, event, loc, toolUsed, generic, stage)
        } else if (stage is IFurnitureStage) {
            handleFurnitureStage(player, itemAdapterData, event, loc, toolUsed, generic, stage)
        }
    }

    private fun handleBlockStage(
        player: Player,
        itemAdapterData: AdapterData,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    ) {
        placeBlock(itemAdapterData, loc)
    }

    private fun handleFurnitureStage(
        player: Player,
        itemAdapterData: AdapterData,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    ) {
        val keyLoc = loc.block.location

        if (isRemoving(keyLoc)) {
            if (!stageManager.activeSequences.contains(keyLoc)) clearRemoving(keyLoc)
            return
        }

        if (event is OraxenFurnitureInteractEvent) {
            val oldEntity = event.baseEntity
            if (!oldEntity.isValid || oldEntity.isDead) return

            val oldLoc = oldEntity.location.block.location
            val oldYaw = oldEntity.location.yaw
            val oldPitch = oldEntity.location.pitch
            val oldFace = oldEntity.facing
            val oldFrameRotation = if (oldEntity is org.bukkit.entity.ItemFrame) oldEntity.rotation else null

            val currentId = getPath(event.mechanic.itemID)
            val hadCurrentObject = isValidFurniture(oldLoc, currentId) || isValidBlock(oldLoc, currentId)

            if (hadCurrentObject) {
                try {
                    OraxenFurniture.remove(oldEntity, null, null)
                } catch (_: Throwable) {
                    oldEntity.remove()
                }
                cleanupFurnitureEntitiesOraxen(oldLoc)
                breakBlock(oldLoc, player)
            } else {
                breakBlock(oldLoc, player)
            }

            OraxenFurniture.getFurnitureMechanic(itemAdapterData.id)
                ?.place(oldLoc, oldYaw, oldFace)

            Bukkit.getScheduler().runTaskLater(UnearthMechanic.getInstance(), Runnable {
                val center = oldLoc.clone().add(0.5, 0.5, 0.5)
                oldLoc.world?.getNearbyEntities(center, 1.5, 1.5, 1.5)?.forEach { entity ->
                    if (entity.location.block.location != oldLoc) return@forEach
                    if (!entity.isValid || entity.isDead) return@forEach
                    if (!OraxenFurniture.isFurniture(entity)) return@forEach

                    entity.setRotation(oldYaw, oldPitch)
                    if (oldFrameRotation != null && entity is org.bukkit.entity.ItemFrame) {
                        entity.rotation = oldFrameRotation
                    }
                }

                if (!stageManager.activeSequences.contains(oldLoc)) clearRemoving(oldLoc)
            }, 2L)

        } else {
            val rotation = rotationMap.remove(keyLoc)
            val cachedFrameRotation = itemFrameRotationMap.remove(keyLoc)

            if (isRemoving(keyLoc)) {
                if (!stageManager.activeSequences.contains(keyLoc)) clearRemoving(keyLoc)
                return
            }

            OraxenFurniture.getFurnitureMechanic(itemAdapterData.id)
                ?.place(keyLoc, rotation?.first ?: 0f, BlockFace.UP)

            Bukkit.getScheduler().runTaskLater(UnearthMechanic.getInstance(), Runnable {
                val center = keyLoc.clone().add(0.5, 0.5, 0.5)
                keyLoc.world?.getNearbyEntities(center, 1.5, 1.5, 1.5)?.forEach { entity ->
                    if (entity.location.block.location != keyLoc) return@forEach
                    if (!entity.isValid || entity.isDead) return@forEach
                    if (!OraxenFurniture.isFurniture(entity)) return@forEach

                    if (rotation != null) entity.setRotation(rotation.first, rotation.second)
                    if (cachedFrameRotation != null && entity is org.bukkit.entity.ItemFrame) {
                        entity.rotation = cachedFrameRotation
                    }
                }
            }, 2L)
        }
    }

    override fun handleRemove(
        player: Player,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    ) {
        if (event is OraxenNoteBlockInteractEvent || event is OraxenStringBlockInteractEvent) {
            OraxenBlocks.remove(loc,player)

            loc.block.type = org.bukkit.Material.AIR
        }
        if (event is OraxenFurnitureInteractEvent) {
            event.baseEntity?.let { entity ->
                rotationMap[entity.location] = Pair(entity.location.yaw, entity.location.pitch)
            }
            setRemoving(event.baseEntity.location.block.location)

            removeStageData(event.baseEntity.location.block.location)
            breakFurniture(event.baseEntity, player, event.mechanic.itemID)
        }

        val center = loc.clone().add(0.5, 0.5, 0.5)
        val nearby = loc.world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        var removedAnyFurniture = false
        for (entity in nearby) {
            if (!OraxenFurniture.isFurniture(entity) || !entity.isValid || entity.isDead) continue
            if (entity.location.block.location != loc.block.location) continue

            val furniture = OraxenFurniture.isFurniture(entity)
            if (furniture != null && entity.isValid && !entity.isDead) {
                OraxenFurniture.remove(loc,player)
                removedAnyFurniture = true
            }
        }

        if (!removedAnyFurniture && loc.block.type != org.bukkit.Material.AIR) {
            //debug("handleRemove[Sequence]: no furniture found, breakBlock fallback loc=${loc.block.location}")
            breakBlock(loc,player)
        }
    }

    override fun hashCode(
        player: Player,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: Int
    ): Int {
        if (event is OraxenNoteBlockInteractEvent) {
            val block: Block = event.block
            return Utils.calculateHashCode(
                block.type.hashCode(),
                block.blockData.hashCode(),
                block.state.hashCode(),
                event.mechanic.itemID.hashCode(),
                block.hashCode()
            )
        }
        if (event is OraxenStringBlockInteractEvent) {
            val block: Block = event.block
            return Utils.calculateHashCode(
                block.type.hashCode(),
                block.blockData.hashCode(),
                block.state.hashCode(),
                event.mechanic.itemID.hashCode(),
                block.hashCode()
            )
        }

        if (event is OraxenFurnitureInteractEvent) {
            val entity: Entity = event.baseEntity
            var result: Int = entity.type.hashCode()
            result = 31 * result + entity.isDead.hashCode()
            result = 31 * result + entity.uniqueId.hashCode()
            result = 31 * result + entity.hashCode()
            result = 31 * result + entity.facing.hashCode()
            result = 31 * result + entity.location.hashCode()
            event.block?.let {
                result = 31 * result + it.type.hashCode()
                result = 31 * result + it.blockData.hashCode()
                result = 31 * result + it.state.hashCode()
                result = 31 * result + it.hashCode()
            }
            event.interactionEntity?.let {
                result = 31 * result + it.type.hashCode()
                result = 31 * result + it.isDead.hashCode()
                result = 31 * result + it.uniqueId.hashCode()
                result = 31 * result + it.hashCode()
                result = 31 * result + it.facing.hashCode()
                result = 31 * result + it.location.hashCode()
            }
            return result
        }
        return -1
    }

    override fun getItemHand(event: Event): ItemStack? {
        if (event is OraxenNoteBlockInteractEvent) {
            return event.itemInHand
        }
        if (event is OraxenStringBlockInteractEvent) {
            return event.itemInHand
        }
        if (event is OraxenFurnitureInteractEvent) {
            return event.itemInHand
        }
        return null
    }

    override fun getBlockFace(event: Event): BlockFace? {
        if (event is OraxenNoteBlockInteractEvent) {
            return event.blockFace
        }
        if (event is OraxenStringBlockInteractEvent) {
            return event.blockFace
        }
        return null
    }

}