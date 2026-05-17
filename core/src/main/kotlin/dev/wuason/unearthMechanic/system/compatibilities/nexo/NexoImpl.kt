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

            val furniture = try {
                NexoFurniture.isFurniture(entity)
            } catch (_: Throwable) {
                null
            } ?: continue

            rotationMap[keyLoc] = Pair(entity.location.yaw, entity.location.pitch)

            if (entity is org.bukkit.entity.ItemFrame) {
                itemFrameRotationMap[keyLoc] = entity.rotation
            }

            try {
                NexoFurniture.remove(entity, null, null)
            } catch (_: Throwable) {
                entity.remove()
            }

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

    // This is null because the furniture might have a barrier as its hitbox,
    // so when the new piece is spawned and the old one is removed,
    // the new furniture might end up without a hitbox
    override fun placeNewFurnitureThenRemoveOld(
        loc: Location,
        currentAdapterId: String,
        targetAdapterId: String,
        oldUuid: UUID?
    ): UUID? {
        return null
    }

    private fun findNewestNexoFurnitureAt(
        keyLoc: Location,
        expectedId: String,
        oldUuid: UUID?
    ): Entity? {
        val world = keyLoc.world ?: return null
        val center = keyLoc.clone().add(0.5, 0.5, 0.5)

        return world.getNearbyEntities(center, 1.5, 1.5, 1.5)
            .asSequence()
            .filter { it.uniqueId != oldUuid }
            .filter { it.isValid && !it.isDead }
            .firstOrNull { entity ->
                try {
                    if (!NexoFurniture.isFurniture(entity)) return@firstOrNull false

                    val mechanic = NexoFurniture.furnitureMechanic(entity)
                    mechanic != null && mechanic.itemID.equals(expectedId, ignoreCase = true)
                } catch (_: Throwable) {
                    false
                }
            }
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

    private fun hardRemoveNexoAt(loc: Location, player: Player? = null): Boolean {
        val keyLoc = loc.block.location
        var removed = false

        // 1) Custom block Nexo: note/string
        try {
            val hasNexoBlock =
                NexoBlocks.noteBlockMechanic(keyLoc.block) != null ||
                        NexoBlocks.stringMechanic(keyLoc.block) != null

            if (hasNexoBlock) {
                if (player != null) {
                    NexoBlocks.remove(keyLoc, player)
                } else {
                    keyLoc.block.type = org.bukkit.Material.AIR
                }
                removed = true
            }
        } catch (_: Throwable) {
            keyLoc.block.type = org.bukkit.Material.AIR
        }

        // 2) Furniture Nexo
        val world = keyLoc.world ?: return removed
        val center = keyLoc.clone().add(0.5, 0.5, 0.5)

        for (entity in world.getNearbyEntities(center, 1.5, 1.5, 1.5)) {
            if (!entity.isValid || entity.isDead) continue
            if (entity.location.block.location != keyLoc) continue

            val isFurniture = try {
                NexoFurniture.isFurniture(entity)
            } catch (_: Throwable) {
                false
            }

            if (!isFurniture) continue

            rotationMap[keyLoc] = Pair(entity.location.yaw, entity.location.pitch)

            if (entity is org.bukkit.entity.ItemFrame) {
                itemFrameRotationMap[keyLoc] = entity.rotation
            }

            try {
                if (player != null) {
                    NexoFurniture.remove(entity, player, null)
                } else {
                    NexoFurniture.remove(entity, null, null)
                }
            } catch (_: Throwable) {
                entity.remove()
            }

            removed = true
        }

        if (removed) {
            cleanupFurnitureEntities(keyLoc)
            StageData.removeStageData(keyLoc)
            setRemoving(keyLoc)

            Bukkit.getScheduler().runTaskLater(core, Runnable {
                if (!stageManager.activeSequences.contains(keyLoc)) {
                    clearRemoving(keyLoc)
                }
            }, 2L)
        }

        return removed
    }

    override fun handleCrossCompatibilityRemoveBeforeTarget(
        player: Player,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage,
        targetCompatibility: ICompatibility
    ): Boolean {
        return hardRemoveNexoAt(loc, player)
    }

    override fun handleRemove(
        player: Player,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    ) {
        if (hardRemoveNexoAt(loc, player)) return
        if (event is NexoNoteBlockInteractEvent || event is NexoStringBlockInteractEvent) {
            loc.block.type = org.bukkit.Material.AIR
        }
        if (event is NexoFurnitureInteractEvent) {
            event.baseEntity?.let { entity ->
                val key = entity.location.block.location
                rotationMap[key] = Pair(entity.location.yaw, entity.location.pitch)

                if (entity is org.bukkit.entity.ItemFrame) {
                    itemFrameRotationMap[key] = entity.rotation
                }
            }
            setRemoving(event.baseEntity.location.block.location)

            removeStageData(event.baseEntity.location.block.location)
            breakFurniture(event.baseEntity, player, event.mechanic.itemID)
        }

        val nearby = loc.world.getNearbyEntities(loc, 0.5, 1.0, 0.5)
        var removedAnyFurniture = false

        for (entity in nearby) {
            if (!entity.isValid || entity.isDead) continue
            if (entity.location.block.location != loc.block.location) continue

            val furniture = try {
                NexoFurniture.isFurniture(entity)
            } catch (_: Throwable) {
                null
            } ?: continue

            val key = loc.block.location

            rotationMap[key] = Pair(entity.location.yaw, entity.location.pitch)

            if (entity is org.bukkit.entity.ItemFrame) {
                itemFrameRotationMap[key] = entity.rotation
            }

            try {
                NexoFurniture.remove(entity)
            } catch (_: Throwable) {
                entity.remove()
            }

            removedAnyFurniture = true
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