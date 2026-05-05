package dev.wuason.unearthMechanic.system.compatibilities.nexo

import com.nexomc.nexo.api.NexoBlocks
import com.nexomc.nexo.api.NexoFurniture
import com.nexomc.nexo.api.events.custom_block.noteblock.NexoNoteBlockBreakEvent
import com.nexomc.nexo.api.events.custom_block.noteblock.NexoNoteBlockInteractEvent
import com.nexomc.nexo.api.events.custom_block.stringblock.NexoStringBlockBreakEvent
import com.nexomc.nexo.api.events.custom_block.stringblock.NexoStringBlockInteractEvent
import com.nexomc.nexo.api.events.furniture.NexoFurnitureBreakEvent
import com.nexomc.nexo.api.events.furniture.NexoFurnitureInteractEvent
import com.nexomc.nexo.api.events.furniture.NexoFurniturePlaceEvent
import com.nexomc.nexo.utils.drops.Drop
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

class NexoImpl(
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
                val furniture = NexoFurniture.isFurniture(entity)
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
        val cleanId = expectedAdapterId?.removePrefix("nexo:")
        val center = keyLoc.clone().add(0.5, 0.5, 0.5)
        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        for (entity in nearby) {
            if (entity.location.block.location != keyLoc) continue
            if (!entity.isValid || entity.isDead) continue
            if (!NexoFurniture.isFurniture(entity)) continue

            if (expectedUuid != null) {
                if (entity.uniqueId == expectedUuid) return true
                continue
            }

            val mechanic = try { NexoFurniture.furnitureMechanic(entity) } catch (_: Throwable) { null }
            if (cleanId != null && mechanic != null && mechanic.itemID.equals(cleanId, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    override fun isValidFurniture(loc: Location, expectedAdapterId: String?): Boolean {
        val keyLoc = loc.block.location
        val world = keyLoc.world ?: return false
        val cleanId = expectedAdapterId?.removePrefix("nexo:")
        val center = keyLoc.clone().add(0.5, 0.5, 0.5)
        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        for (entity in nearby) {
            if (entity.location.block.location != keyLoc) continue
            if (!entity.isValid || entity.isDead) continue
            if (!NexoFurniture.isFurniture(entity)) continue

            val mechanic = try { NexoFurniture.furnitureMechanic(entity) } catch (_: Throwable) { null }

            if (cleanId == null) return true
            if (mechanic != null && mechanic.itemID.equals(cleanId, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    override fun isValidBlock(loc: Location, expectedAdapterId: String?): Boolean {
        val cleanId = expectedAdapterId?.removePrefix("nexo:") ?: return loc.block.type != org.bukkit.Material.AIR

        return try {
            val noteId = NexoBlocks.noteBlockMechanic(loc.block)?.itemID
            val stringId = NexoBlocks.stringMechanic(loc.block)?.itemID
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
            if (!NexoFurniture.isFurniture(entity)) continue

            val mechanic = try { NexoFurniture.furnitureMechanic(entity) } catch (_: Throwable) { null }

            NexoFurniture.remove(entity, null, null)

            // extra clean
            cleanupFurnitureEntities(keyLoc)

            return true
        }

        return false
    }

    private fun cleanupFurnitureEntities(loc: Location) {
        val world = loc.world ?: return
        val center = loc.clone().add(0.5, 0.5, 0.5)

        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        for (entity in nearby) {
            if (entity.location.block.location != loc) continue
            if (!entity.isValid || entity.isDead) continue

            try {
                if (NexoFurniture.isFurniture(entity)) {
                    entity.remove()
                }
            } catch (_: Throwable) {}
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteractBlock(event: NexoNoteBlockInteractEvent) {
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

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteractBlock(event: NexoStringBlockInteractEvent) {
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

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteractFurniture(event: NexoFurnitureInteractEvent) {
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
    fun onBreakBlock(event: NexoNoteBlockBreakEvent) {
        StageData.removeStageData(event.block)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBreakBlock(event: NexoStringBlockBreakEvent) {
        StageData.removeStageData(event.block)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onFurnitureBreak(event: NexoFurnitureBreakEvent) {
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
    fun onFurniturePlace(event: NexoFurniturePlaceEvent) {
        Bukkit.getScheduler().runTaskLater(UnearthMechanic.getInstance(), Runnable {
            clearRemoving(event.baseEntity.location.block.location)
        }, 3L)
    }


    private fun placeBlock(itemAdapterData: AdapterData, location: Location) {
        NexoBlocks.place(itemAdapterData.id, location)
    }

    private fun breakBlock(location: Location, player: Player) {
        NexoBlocks.remove(location, player)
    }

    private fun placeFurniture(
        itemAdapterData: AdapterData,
        location: Location,
        blockFace: BlockFace,
        yaw: Float
    ) {
        NexoFurniture.furnitureMechanic(itemAdapterData.id)?.place(location, yaw, blockFace)
    }
    private fun placeFurniture(
        itemAdapterData: AdapterData,
        location: Location,
    ) {
        NexoFurniture.furnitureMechanic(itemAdapterData.id)?.place(location, 0f, BlockFace.UP)
    }

    private fun breakFurniture(entity: Entity, player: Player, id: String) {
        NexoFurniture.remove(entity, player, Drop(mutableListOf(), silktouch = false, fortune = false, sourceID = id))
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

        if (event is NexoFurnitureInteractEvent) {
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
                    NexoFurniture.remove(oldEntity, null, null)
                } catch (_: Throwable) {
                    oldEntity.remove()
                }
                cleanupFurnitureEntities(oldLoc)
                breakBlock(oldLoc, player)
            } else {
                breakBlock(oldLoc, player)
            }

            NexoFurniture.furnitureMechanic(itemAdapterData.id)?.place(oldLoc, oldYaw, oldFace)

            Bukkit.getScheduler().runTaskLater(UnearthMechanic.getInstance(), Runnable {
                val center = oldLoc.clone().add(0.5, 0.5, 0.5)
                oldLoc.world?.getNearbyEntities(center, 1.5, 1.5, 1.5)?.forEach { entity ->
                    if (entity.location.block.location != oldLoc) return@forEach
                    if (!entity.isValid || entity.isDead) return@forEach
                    if (!NexoFurniture.isFurniture(entity)) return@forEach

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

            NexoFurniture.furnitureMechanic(itemAdapterData.id)
                ?.place(keyLoc, rotation?.first ?: 0f, BlockFace.UP)

            Bukkit.getScheduler().runTaskLater(UnearthMechanic.getInstance(), Runnable {
                val center = keyLoc.clone().add(0.5, 0.5, 0.5)
                keyLoc.world?.getNearbyEntities(center, 1.5, 1.5, 1.5)?.forEach { entity ->
                    if (entity.location.block.location != keyLoc) return@forEach
                    if (!entity.isValid || entity.isDead) return@forEach
                    if (!NexoFurniture.isFurniture(entity)) return@forEach

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
        if (event is NexoNoteBlockInteractEvent || event is NexoStringBlockInteractEvent) {
            loc.block.type = org.bukkit.Material.AIR
        }
        if (event is NexoFurnitureInteractEvent) {
            event.baseEntity?.let { entity ->
                rotationMap[entity.location] = Pair(entity.location.yaw, entity.location.pitch)
            }
            setRemoving(event.baseEntity.location.block.location)

            removeStageData(event.baseEntity.location.block.location)
            breakFurniture(event.baseEntity, player, event.mechanic.itemID)
        }

        val nearby = loc.world.getNearbyEntities(loc, 0.5, 1.0, 0.5)
        var removedAnyFurniture = false

        for (entity in nearby) {
            if (!NexoFurniture.isFurniture(entity) || !entity.isValid || entity.isDead) continue
            if (entity.location.block.location != loc.block.location) continue
            try {
                val furniture = NexoFurniture.isFurniture(entity)
                if (furniture != null && entity.isValid && !entity.isDead) {
                    NexoFurniture.remove(entity)
                    removedAnyFurniture = true
                }
            } catch (_: Exception) {
                continue
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
        if (event is NexoNoteBlockInteractEvent) {
            val block: Block = event.block
            return Utils.calculateHashCode(
                block.type.hashCode(),
                block.blockData.hashCode(),
                block.state.hashCode(),
                event.mechanic.itemID.hashCode(),
                block.hashCode()
            )
        }
        if (event is NexoStringBlockInteractEvent) {
            val block: Block = event.block
            return Utils.calculateHashCode(
                block.type.hashCode(),
                block.blockData.hashCode(),
                block.state.hashCode(),
                event.mechanic.itemID.hashCode(),
                block.hashCode()
            )
        }

        if (event is NexoFurnitureInteractEvent) {
            val entity: Entity = event.baseEntity
            return Utils.calculateHashCode(
                entity.type.hashCode(),
                entity.isDead.hashCode(),
                entity.uniqueId.hashCode(),
                entity.hashCode(),
                entity.facing.hashCode(),
                entity.location.hashCode()
            )
        }
        return -1
    }

    override fun getItemHand(event: Event): ItemStack? {
        if (event is NexoNoteBlockInteractEvent) {
            return event.itemInHand
        }
        if (event is NexoStringBlockInteractEvent) {
            return event.itemInHand
        }
        if (event is NexoFurnitureInteractEvent) {
            return event.itemInHand
        }
        return null
    }

    override fun getBlockFace(event: Event): BlockFace? {
        if (event is NexoNoteBlockInteractEvent) {
            return event.blockFace
        }
        if (event is NexoStringBlockInteractEvent) {
            return event.blockFace
        }
        return null
    }

}