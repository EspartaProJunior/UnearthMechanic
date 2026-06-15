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
import dev.wuason.unearthMechanic.UnearthMechanicPlugin
import dev.wuason.unearthMechanic.config.*
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.StageData
import dev.wuason.unearthMechanic.system.StageManager
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import dev.wuason.unearthMechanic.utils.FoliaUtils
import dev.wuason.unearthMechanic.utils.Utils
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

    private val debugNexo = false

    private fun dbg(message: String) {
        if (!debugNexo) return
        core.logger.info("[UM-NEXO-DBG] $message")
    }

    private fun warnDbg(message: String, throwable: Throwable? = null) {
        if (!debugNexo) return

        core.logger.warning("[UM-NEXO-DBG] $message")

        if (throwable != null) {
            throwable.printStackTrace()
        }
    }

    private fun Location.shortLoc(): String {
        return "${world?.name ?: "null"}:${blockX},${blockY},${blockZ}"
    }

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
        val keyLoc = location.block.location
        val world = keyLoc.world ?: run {
            dbg("getFurnitureUUID FAIL world=null loc=${keyLoc.shortLoc()}")
            return null
        }

        val center = keyLoc.clone().add(0.5, 0.5, 0.5)
        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        dbg("getFurnitureUUID START loc=${keyLoc.shortLoc()} nearby=${nearby.size}")

        val found = nearby
            .asSequence()
            .filter { it.isValid && !it.isDead }
            .filter { it.location.block.location == keyLoc }
            .filter {
                val result = try {
                    NexoFurniture.isFurniture(it)
                } catch (ex: Throwable) {
                    warnDbg("getFurnitureUUID isFurniture threw entity=${it.type} uuid=${it.uniqueId}", ex)
                    false
                }

                dbg(
                    "getFurnitureUUID scan entity=${it.type} uuid=${it.uniqueId} " +
                            "entityLoc=${it.location.block.location.shortLoc()} isFurniture=$result"
                )

                result
            }
            .sortedBy { it.location.distanceSquared(center) }
            .firstOrNull()

        dbg("getFurnitureUUID RESULT loc=${keyLoc.shortLoc()} uuid=${found?.uniqueId}")

        return found?.uniqueId
    }

    override fun getFurnitureUUID(
        loc: Location,
        expectedAdapterId: String
    ): UUID? {
        val keyLoc = loc.block.location
        val world = keyLoc.world ?: run {
            dbg("getFurnitureUUID(expected) FAIL world=null loc=${keyLoc.shortLoc()}")
            return null
        }

        val cleanExpectedId = expectedAdapterId
            .removePrefix("nexo:")
            .substringBefore("[")

        val center = keyLoc.clone().add(0.5, 0.5, 0.5)
        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        dbg(
            "getFurnitureUUID(expected) START loc=${keyLoc.shortLoc()} " +
                    "expected=$cleanExpectedId nearby=${nearby.size}"
        )

        var bestEntity: Entity? = null
        var bestDistance = Double.MAX_VALUE

        for (entity in nearby) {
            if (!entity.isValid || entity.isDead) continue

            val entityLoc = entity.location.block.location

            val isFurniture = try {
                NexoFurniture.isFurniture(entity)
            } catch (ex: Throwable) {
                warnDbg("getFurnitureUUID(expected) isFurniture threw entity=${entity.type} uuid=${entity.uniqueId}", ex)
                false
            }

            if (!isFurniture) continue

            val mechanic = try {
                NexoFurniture.furnitureMechanic(entity)
            } catch (ex: Throwable) {
                warnDbg("getFurnitureUUID(expected) furnitureMechanic threw entity=${entity.type} uuid=${entity.uniqueId}", ex)
                null
            } ?: continue

            dbg(
                "getFurnitureUUID(expected) scan entity=${entity.type} uuid=${entity.uniqueId} " +
                        "entityLoc=${entityLoc.shortLoc()} itemID=${mechanic.itemID}"
            )

            if (!mechanic.itemID.equals(cleanExpectedId, ignoreCase = true)) {
                continue
            }

            if (entityLoc == keyLoc) {
                dbg("getFurnitureUUID(expected) DIRECT MATCH uuid=${entity.uniqueId}")
                return entity.uniqueId
            }

            val distance = entity.location.distanceSquared(center)

            if (distance < bestDistance) {
                bestDistance = distance
                bestEntity = entity
            }
        }

        dbg("getFurnitureUUID(expected) RESULT loc=${keyLoc.shortLoc()} uuid=${bestEntity?.uniqueId}")

        return bestEntity?.uniqueId
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
        if (uuid == null) {
            dbg("removeFurnitureByUUID SKIP uuid=null loc=${loc.block.location.shortLoc()}")
            return false
        }

        val keyLoc = loc.block.location
        val world = keyLoc.world ?: run {
            dbg("removeFurnitureByUUID FAIL world=null loc=${keyLoc.shortLoc()} uuid=$uuid")
            return false
        }

        val center = keyLoc.clone().add(0.5, 0.5, 0.5)
        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        dbg("removeFurnitureByUUID START loc=${keyLoc.shortLoc()} uuid=$uuid nearby=${nearby.size}")

        for (entity in nearby) {
            dbg(
                "removeFurnitureByUUID scan entity=${entity.type} uuid=${entity.uniqueId} " +
                        "valid=${entity.isValid} dead=${entity.isDead} loc=${entity.location.block.location.shortLoc()}"
            )

            if (entity.location.block.location != keyLoc) continue
            if (!entity.isValid || entity.isDead) continue
            if (entity.uniqueId != uuid) continue

            val isFurniture = try {
                NexoFurniture.isFurniture(entity)
            } catch (ex: Throwable) {
                warnDbg("removeFurnitureByUUID isFurniture threw uuid=${entity.uniqueId}", ex)
                false
            }

            dbg("removeFurnitureByUUID matched uuid=$uuid isFurniture=$isFurniture")

            if (!isFurniture) continue

            rotationMap[keyLoc] = Pair(entity.location.yaw, entity.location.pitch)

            if (entity is org.bukkit.entity.ItemFrame) {
                itemFrameRotationMap[keyLoc] = entity.rotation
            }

            try {
                val removed = NexoFurniture.remove(
                    entity,
                    null,
                    emptyNexoDrop("unearth_transform_remove")
                )
                dbg("removeFurnitureByUUID NexoFurniture.remove result=$removed uuid=$uuid loc=${keyLoc.shortLoc()}")
            } catch (ex: Throwable) {
                warnDbg("removeFurnitureByUUID remove threw, fallback entity.remove uuid=$uuid", ex)
                entity.remove()
            }

            return true
        }

        dbg("removeFurnitureByUUID RESULT false loc=${keyLoc.shortLoc()} uuid=$uuid")
        return false
    }

    private fun cleanupFurnitureEntities(loc: Location, keepUuid: UUID? = null) {
        val keyLoc = loc.block.location
        val world = keyLoc.world ?: return
        val center = keyLoc.clone().add(0.5, 0.5, 0.5)

        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        for (entity in nearby) {
            if (entity.uniqueId == keepUuid) continue
            if (entity.location.block.location != keyLoc) continue
            if (!entity.isValid || entity.isDead) continue

            val isFurniture = try {
                NexoFurniture.isFurniture(entity)
            } catch (_: Throwable) {
                false
            }

            if (!isFurniture) continue

            entity.remove()
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
        val loc = event.baseEntity.location.block.location

        dbg(
            "FurnitureInteract hand=${event.hand} player=${event.player.name} " +
                    "itemID=${event.mechanic.itemID} path=${getPath(event.mechanic.itemID)} " +
                    "entity=${event.baseEntity.type} uuid=${event.baseEntity.uniqueId} " +
                    "loc=${loc.shortLoc()} transitioning=${stageManager.isTransitioning(loc)} " +
                    "removing=${isRemoving(loc)} emptyHand=${isEmptyHand(event.player)}"
        )

        if (stageManager.isTransitioning(loc)) {
            dbg("FurnitureInteract CANCELLED because transitioning loc=${loc.shortLoc()}")
            event.isCancelled = true
            return
        }

        if (event.hand != EquipmentSlot.HAND) return
        event.isCancelled = true

        stageManager.interact(
            event.player,
            getPath(event.mechanic.itemID),
            event.baseEntity.location,
            event,
            this
        )
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
        val entity = event.baseEntity

        FoliaUtils.runAtEntity(entity) {
            val loc = entity.location.block.location

            FoliaUtils.runLater(3L) {
                FoliaUtils.runAtLocation(loc) {
                    clearRemoving(loc)
                }
            }
        }
    }

    private fun cleanNexoId(id: String): String {
        return id.removePrefix("nexo:")
    }

    private fun isEmptyHand(player: Player): Boolean {
        return player.inventory.itemInMainHand.type.isAir
    }

    private fun emptyNexoDrop(sourceId: String = "unearth_internal_remove"): Drop {
        return Drop(
            mutableListOf(),
            silktouch = false,
            fortune = false,
            sourceID = sourceId
        )
    }

    private fun placeBlock(itemAdapterData: AdapterData, location: Location) {
        NexoBlocks.place(cleanNexoId(itemAdapterData.id), location.block.location)
    }

    private fun breakBlock(location: Location, player: Player) {
        NexoBlocks.remove(location, player)
    }

    private fun placeFurniture(
        itemAdapterData: AdapterData,
        location: Location,
        blockFace: BlockFace,
        yaw: Float
    ): org.bukkit.entity.ItemDisplay? {
        return NexoFurniture.place(
            cleanNexoId(itemAdapterData.id),
            location.block.location,
            yaw,
            blockFace
        )
    }

    private fun placeFurniture(
        itemAdapterData: AdapterData,
        location: Location
    ): org.bukkit.entity.ItemDisplay? {
        return NexoFurniture.place(
            cleanNexoId(itemAdapterData.id),
            location.block.location,
            0f,
            BlockFace.UP
        )
    }

    private fun breakFurniture(entity: Entity, player: Player, id: String) {
        //NexoFurniture.remove(entity, player, Drop(mutableListOf(), silktouch = false, fortune = false, sourceID = id))
        NexoFurniture.remove(
            entity,
            player,
            emptyNexoDrop(id)
        )
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
        when (stage) {
            is IBlockStage -> {
                FoliaUtils.runAtLocation(loc) {
                    handleBlockStage(player, itemAdapterData, event, loc, toolUsed, generic, stage)
                }
            }
            is IFurnitureStage -> {
                val keyLoc = loc.block.location

                FoliaUtils.runLater(1L) {
                    FoliaUtils.runAtLocation(keyLoc) {
                        handleFurnitureStage(player, itemAdapterData, event, keyLoc, toolUsed, generic, stage)
                    }
                }
            }
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

    private fun removeFurnitureAt(loc: Location): Boolean {
        val keyLoc = loc.block.location

        dbg("removeFurnitureAt START loc=${keyLoc.shortLoc()}")

        val uuid = getFurnitureUUID(keyLoc)

        dbg("removeFurnitureAt uuid=$uuid loc=${keyLoc.shortLoc()}")

        if (uuid != null) {
            val result = removeFurnitureByUUID(keyLoc, uuid)
            dbg("removeFurnitureAt byUUID result=$result uuid=$uuid loc=${keyLoc.shortLoc()}")
            return result
        }

        return try {
            val result = NexoFurniture.remove(
                keyLoc,
                null,
                emptyNexoDrop("unearth_transform_remove")
            )
            dbg("removeFurnitureAt byLocation result=$result loc=${keyLoc.shortLoc()}")
            result
        } catch (ex: Throwable) {
            warnDbg("removeFurnitureAt byLocation threw loc=${keyLoc.shortLoc()}", ex)
            false
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
        val targetId = cleanNexoId(itemAdapterData.id)

        dbg(
            "handleFurnitureStage ENTER player=${player.name} rawTarget=${itemAdapterData.id} " +
                    "target=$targetId loc=${keyLoc.shortLoc()} event=${event.javaClass.simpleName} " +
                    "removing=${isRemoving(keyLoc)} activeSequence=${stageManager.activeSequences.contains(keyLoc)}"
        )

        if (isRemoving(keyLoc) && !stageManager.activeSequences.contains(keyLoc)) {
            dbg("handleFurnitureStage clearRemoving before replace loc=${keyLoc.shortLoc()}")
            clearRemoving(keyLoc)
        }

        val oldYaw = try {
            when (event) {
                is NexoFurnitureInteractEvent -> event.baseEntity.location.yaw
                else -> rotationMap[keyLoc]?.first
            }
        } catch (ex: Throwable) {
            warnDbg("handleFurnitureStage oldYaw threw loc=${keyLoc.shortLoc()}", ex)
            rotationMap[keyLoc]?.first
        } ?: 0f

        val oldPitch = try {
            when (event) {
                is NexoFurnitureInteractEvent -> event.baseEntity.location.pitch
                else -> rotationMap[keyLoc]?.second
            }
        } catch (ex: Throwable) {
            warnDbg("handleFurnitureStage oldPitch threw loc=${keyLoc.shortLoc()}", ex)
            rotationMap[keyLoc]?.second
        }

        val oldFace = try {
            when (event) {
                is NexoFurnitureInteractEvent -> event.baseEntity.facing
                else -> BlockFace.UP
            }
        } catch (ex: Throwable) {
            warnDbg("handleFurnitureStage oldFace threw loc=${keyLoc.shortLoc()}", ex)
            BlockFace.UP
        }

        dbg(
            "handleFurnitureStage rotation target=$targetId loc=${keyLoc.shortLoc()} " +
                    "yaw=$oldYaw pitch=$oldPitch face=$oldFace"
        )

        try {
            val removed = removeFurnitureAt(keyLoc)

            dbg(
                "handleFurnitureStage after remove removed=$removed loc=${keyLoc.shortLoc()} " +
                        "removing=${isRemoving(keyLoc)}"
            )

            dbg(
                "handleFurnitureStage PLACE attempt target=$targetId loc=${keyLoc.shortLoc()} " +
                        "yaw=$oldYaw face=$oldFace"
            )

            val placed = NexoFurniture.place(
                targetId,
                keyLoc,
                oldYaw,
                oldFace
            )

            dbg(
                "handleFurnitureStage PLACE result target=$targetId loc=${keyLoc.shortLoc()} " +
                        "placed=${placed != null} uuid=${placed?.uniqueId} type=${placed?.type}"
            )

            if (placed == null) {
                core.logger.warning(
                    "[UnearthMechanic][Nexo] NexoFurniture.place returned null for id=$targetId at $keyLoc"
                )
                return
            }

            if (oldPitch != null) {
                dbg("handleFurnitureStage setRotation uuid=${placed.uniqueId} yaw=$oldYaw pitch=$oldPitch")
                placed.setRotation(oldYaw, oldPitch)
            }

            clearRemoving(keyLoc)

            dbg(
                "handleFurnitureStage DONE target=$targetId loc=${keyLoc.shortLoc()} " +
                        "uuid=${placed.uniqueId} removing=${isRemoving(keyLoc)}"
            )
        } catch (ex: Throwable) {
            warnDbg(
                "handleFurnitureStage replace threw target=$targetId loc=${keyLoc.shortLoc()}",
                ex
            )
            clearRemoving(keyLoc)
        }

        /*if (event is NexoFurnitureInteractEvent) {
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

            FoliaUtils.runLater(2L) {
                FoliaUtils.runAtLocation(oldLoc) {
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

                    if (!stageManager.activeSequences.contains(oldLoc)) {
                        clearRemoving(oldLoc)
                    }
                }
            }

        } else {
            val rotation = rotationMap.remove(keyLoc)
            val cachedFrameRotation = itemFrameRotationMap.remove(keyLoc)

            if (isRemoving(keyLoc)) {
                if (!stageManager.activeSequences.contains(keyLoc)) clearRemoving(keyLoc)
                return
            }

            NexoFurniture.furnitureMechanic(itemAdapterData.id)
                ?.place(keyLoc, rotation?.first ?: 0f, BlockFace.UP)

            FoliaUtils.runLater(2L) {
                FoliaUtils.runAtLocation(keyLoc) {
                    val center = keyLoc.clone().add(0.5, 0.5, 0.5)

                    keyLoc.world?.getNearbyEntities(center, 1.5, 1.5, 1.5)?.forEach { entity ->
                        if (entity.location.block.location != keyLoc) return@forEach
                        if (!entity.isValid || entity.isDead) return@forEach
                        if (!NexoFurniture.isFurniture(entity)) return@forEach

                        if (rotation != null) {
                            entity.setRotation(rotation.first, rotation.second)
                        }

                        if (cachedFrameRotation != null && entity is org.bukkit.entity.ItemFrame) {
                            entity.rotation = cachedFrameRotation
                        }
                    }
                }
            }
        }*/
    }

    private fun hardRemoveNexoAt(loc: Location, player: Player? = null): Boolean {
        val keyLoc = loc.block.location
        var removed = false

        dbg("hardRemoveNexoAt START loc=${keyLoc.shortLoc()} player=${player?.name}")

        try {
            val hasNexoBlock =
                NexoBlocks.noteBlockMechanic(keyLoc.block) != null ||
                        NexoBlocks.stringMechanic(keyLoc.block) != null

            dbg("hardRemoveNexoAt blockCheck loc=${keyLoc.shortLoc()} hasNexoBlock=$hasNexoBlock")

            if (hasNexoBlock) {
                if (player != null) {
                    NexoBlocks.remove(keyLoc, player)
                } else {
                    keyLoc.block.type = org.bukkit.Material.AIR
                }
                removed = true
            }
        } catch (ex: Throwable) {
            warnDbg("hardRemoveNexoAt block remove threw loc=${keyLoc.shortLoc()}", ex)
            keyLoc.block.type = org.bukkit.Material.AIR
        }

        val world = keyLoc.world ?: return removed
        val center = keyLoc.clone().add(0.5, 0.5, 0.5)
        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        dbg("hardRemoveNexoAt furniture scan loc=${keyLoc.shortLoc()} nearby=${nearby.size}")

        for (entity in nearby) {
            if (!entity.isValid || entity.isDead) continue
            if (entity.location.block.location != keyLoc) continue

            val isFurniture = try {
                NexoFurniture.isFurniture(entity)
            } catch (ex: Throwable) {
                warnDbg("hardRemoveNexoAt isFurniture threw uuid=${entity.uniqueId}", ex)
                false
            }

            dbg(
                "hardRemoveNexoAt scan entity=${entity.type} uuid=${entity.uniqueId} " +
                        "isFurniture=$isFurniture loc=${entity.location.block.location.shortLoc()}"
            )

            if (!isFurniture) continue

            rotationMap[keyLoc] = Pair(entity.location.yaw, entity.location.pitch)

            if (entity is org.bukkit.entity.ItemFrame) {
                itemFrameRotationMap[keyLoc] = entity.rotation
            }

            try {
                val result = if (player != null) {
                    NexoFurniture.remove(entity, player, emptyNexoDrop("unearth_hard_remove"))
                } else {
                    NexoFurniture.remove(entity, null, emptyNexoDrop("unearth_hard_remove"))
                }

                dbg("hardRemoveNexoAt remove entity result=$result uuid=${entity.uniqueId}")
            } catch (ex: Throwable) {
                warnDbg("hardRemoveNexoAt remove entity threw uuid=${entity.uniqueId}", ex)
                entity.remove()
            }

            removed = true
        }

        if (removed) {
            dbg("hardRemoveNexoAt removed=true cleanup loc=${keyLoc.shortLoc()}")

            cleanupFurnitureEntities(keyLoc)
            StageData.removeStageData(keyLoc)
            setRemoving(keyLoc)

            FoliaUtils.runLater(2L) {
                FoliaUtils.runAtLocation(keyLoc) {
                    if (!stageManager.activeSequences.contains(keyLoc)) {
                        dbg("hardRemoveNexoAt delayed clearRemoving loc=${keyLoc.shortLoc()}")
                        clearRemoving(keyLoc)
                    }
                }
            }
        }

        dbg("hardRemoveNexoAt END loc=${keyLoc.shortLoc()} removed=$removed")

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
        return hardRemoveNexoAt(loc, null)
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

            val isFurniture = try {
                NexoFurniture.isFurniture(entity)
            } catch (_: Throwable) {
                false
            }

            if (!isFurniture) continue

            val key = loc.block.location

            rotationMap[key] = Pair(entity.location.yaw, entity.location.pitch)

            if (entity is org.bukkit.entity.ItemFrame) {
                itemFrameRotationMap[key] = entity.rotation
            }

            try {
                NexoFurniture.remove(
                    entity,
                    player,
                    emptyNexoDrop("unearth_handle_remove")
                )
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