package dev.wuason.unearthMechanic.system.compatibilities.ia

import dev.lone.itemsadder.api.CustomBlock
import dev.lone.itemsadder.api.CustomFurniture
import dev.lone.itemsadder.api.Events.CustomBlockBreakEvent
import dev.lone.itemsadder.api.Events.CustomBlockInteractEvent
import dev.lone.itemsadder.api.Events.FurnitureBreakEvent
import dev.lone.itemsadder.api.Events.FurnitureInteractEvent
import dev.lone.itemsadder.api.Events.FurniturePlaceEvent
import dev.wuason.adapter.AdapterComp
import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.UnearthMechanicPlugin
import dev.wuason.unearthMechanic.config.*
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.StageData
import dev.wuason.unearthMechanic.system.StageManager
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import dev.wuason.unearthMechanic.utils.FoliaUtils
import dev.wuason.unearthMechanic.utils.Utils
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.Collections
import java.util.UUID
import kotlin.collections.set

class ItemsAdderImpl(
    pluginName: String,
    private val core: UnearthMechanicPlugin,
    private val stageManager: StageManager,
    adapterComp: AdapterComp
) : ICompatibility(
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

        private val furnitureBaseLocationByEntity = Collections.synchronizedMap(mutableMapOf<UUID, Location>())
    }

    fun removeStageData(location: Location){
        StageData.removeStageData(location)
    }

    fun isPossibleFurnitureEntity(entity: Entity): Boolean {
        return entity is ItemFrame || entity is ArmorStand || entity is ItemDisplay || entity is TextDisplay
    }

    override fun getFurnitureUUID(location: Location): UUID? {
        val keyLoc = location.block.location
        val world = keyLoc.world ?: return null
        val center = keyLoc.clone().add(0.5, 0.5, 0.5)

        val entities = world.getNearbyEntities(center, 1.05, 1.25, 1.05)
            .sortedBy { it.location.distanceSquared(center) }

        for (entity in entities) {
            try {
                if (!isPossibleFurnitureEntity(entity)) continue
                if (!entity.isValid || entity.isDead) continue
                if (!isSameFurnitureArea(entity, keyLoc)) continue

                val furniture = CustomFurniture.byAlreadySpawned(entity)
                if (furniture != null) {
                    rememberFurnitureBase(entity, keyLoc)
                    return entity.uniqueId
                }
            } catch (_: Exception) {
                continue
            }
        }

        return null
    }

    override fun getFurnitureUUID(
        loc: Location,
        expectedAdapterId: String
    ): UUID? {
        val keyLoc = loc.block.location
        val world = keyLoc.world ?: return null

        val cleanExpectedId = expectedAdapterId
            .removePrefix("ia:")
            .removePrefix("itemsadder:")
            .substringBefore("[")

        val center = keyLoc.clone().add(0.5, 0.5, 0.5)

        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)
            .sortedBy { it.location.distanceSquared(center) }

        var bestEntity: Entity? = null
        var bestDistance = Double.MAX_VALUE

        for (entity in nearby) {
            if (!isPossibleFurnitureEntity(entity)) continue
            if (!entity.isValid || entity.isDead) continue
            if (!isSameFurnitureArea(entity, keyLoc)) continue

            val furniture = try {
                CustomFurniture.byAlreadySpawned(entity)
            } catch (_: Throwable) {
                null
            } ?: continue

            if (!furniture.namespacedID.equals(cleanExpectedId, ignoreCase = true)) {
                continue
            }

            rememberFurnitureBase(entity, keyLoc)

            val entityBlockLoc = entity.location.block.location

            if (entityBlockLoc == keyLoc) {
                return entity.uniqueId
            }

            val distance = entity.location.distanceSquared(center)

            if (distance < bestDistance) {
                bestDistance = distance
                bestEntity = entity
            }
        }

        return bestEntity?.uniqueId
    }

    private fun rememberFurnitureBase(entity: Entity, baseLoc: Location) {
        furnitureBaseLocationByEntity[entity.uniqueId] = baseLoc.block.location
    }

    private fun forgetFurnitureBase(entity: Entity) {
        furnitureBaseLocationByEntity.remove(entity.uniqueId)
    }

    private fun isSameFurnitureArea(entity: Entity, keyLoc: Location): Boolean {
        val remembered = furnitureBaseLocationByEntity[entity.uniqueId]
        if (remembered != null) {
            return remembered.block.location == keyLoc.block.location
        }

        return entity.location.block.location == keyLoc.block.location
    }

    private fun debug(msg: String) {
        //Bukkit.getConsoleSender().sendMessage("§6[UM][IA] §f$msg")
    }

    override fun isValidUUID(loc: Location, expectedAdapterId: String?, expectedUuid: UUID?): Boolean {
        val keyLoc = loc.block.location
        val world = keyLoc.world ?: run {
            //debug("isValidUUID: world=null en $keyLoc")
            return false
        }

        val center = keyLoc.clone().add(0.5, 0.5, 0.5)
        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        //debug("isValidUUID: loc=$keyLoc expectedUuid=$expectedUuid expectedId=$expectedAdapterId nearby=${nearby.size}")

        for (entity in nearby) {
            if (!isSameFurnitureArea(entity, keyLoc)) continue
            if (!isPossibleFurnitureEntity(entity) || !entity.isValid || entity.isDead) continue

            val furniture = try { CustomFurniture.byAlreadySpawned(entity) } catch (_: Throwable) { null } ?: continue

            //debug("isValidUUID: entity=${entity.type} uuid=${entity.uniqueId} id=${furniture.namespacedID}")

            if (expectedUuid != null) {
                if (entity.uniqueId == expectedUuid) {
                    //debug("isValidUUID: MATCH UUID")
                    return true
                }
                continue
            }

            if (expectedAdapterId != null &&
                furniture.namespacedID.equals(expectedAdapterId.removePrefix("ia:"), ignoreCase = true)
            ) {
                //debug("isValidUUID: MATCH adapterId")
                return true
            }
        }

        //debug("isValidUUID: FAIL loc=$keyLoc")
        return false
    }

    override fun isValidFurniture(loc: Location, expectedAdapterId: String?): Boolean {
        val keyLoc = loc.block.location
        val world = keyLoc.world ?: run {
            //debug("isValidFurniture: world=null en $keyLoc")
            return false
        }
        val cleanId = expectedAdapterId?.removePrefix("ia:")

        val center = keyLoc.clone().add(0.5, 0.5, 0.5)
        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        //debug("isValidFurniture: loc=$keyLoc expected=$cleanId nearby=${nearby.size}")

        for (entity in nearby) {
            if (!isSameFurnitureArea(entity, keyLoc)) continue
            if (!isPossibleFurnitureEntity(entity) || !entity.isValid || entity.isDead) {
                //debug("isValidFurniture: ignorada entity=${entity.type} uuid=${entity.uniqueId} valid=${entity.isValid} dead=${entity.isDead}")
                continue
            }

            val furniture = CustomFurniture.byAlreadySpawned(entity)
            if(furniture == null){
                //debug("isValidFurniture: entity=${entity.type} uuid=${entity.uniqueId} no mapea a furniture")
                continue
            }

            //debug("isValidFurniture: encontrada entity=${entity.type} uuid=${entity.uniqueId} id=${furniture.namespacedID}")

            if (cleanId == null) return true
            if (furniture.namespacedID.equals(cleanId, ignoreCase = true)) {
                //debug("isValidFurniture: MATCH id=$cleanId")
                return true
            }
        }

        //debug("isValidFurniture: FAIL loc=$keyLoc expected=$cleanId")
        return false
    }

    override fun isValidBlock(loc: Location, expectedAdapterId: String?): Boolean {
        val cleanId = expectedAdapterId?.removePrefix("ia:") ?: return loc.block.type != Material.AIR

        val customBlock = try { CustomBlock.byAlreadyPlaced(loc.block) } catch (_: Throwable) { null }
        if (customBlock != null) {
            return customBlock.namespacedID.equals(cleanId, ignoreCase = true)
        }

        return false
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
            if (!isSameFurnitureArea(entity, keyLoc)) continue
            if (!isPossibleFurnitureEntity(entity) || !entity.isValid || entity.isDead) continue
            if (entity.uniqueId != uuid) continue

            val furniture = try {
                CustomFurniture.byAlreadySpawned(entity)
            } catch (_: Throwable) {
                null
            } ?: continue

            rotationMap[keyLoc] = Pair(entity.location.yaw, entity.location.pitch)

            if (entity is ItemFrame) {
                itemFrameRotationMap[keyLoc] = entity.rotation
            }

            forgetFurnitureBase(entity)
            furniture.remove(false)
            return true
        }

        return false
    }

    @EventHandler
    fun onInteractBlock(event: CustomBlockInteractEvent) {
        if (event.action == Action.RIGHT_CLICK_BLOCK && event.hand == EquipmentSlot.HAND) {
            stageManager.interact(
                event.player,
                "ia:" + event.namespacedID,
                event.blockClicked.location,
                event,
                this
            )
        }
    }

    @EventHandler
    fun onInteractFurniture(event: FurnitureInteractEvent) {
        val loc = event.bukkitEntity.location.block.location
        if (stageManager.isTransitioning(loc)) {
            event.isCancelled = true
            return
        }

        val uuid = event.bukkitEntity.uniqueId

        if (event.bukkitEntity != null && event.bukkitEntity.uniqueId == uuid) {
            val adapterId = "ia:" + event.namespacedID

            stageManager.interact(
                event.player,
                adapterId,
                loc,
                event,
                this
            )
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBlockBreak(event: CustomBlockBreakEvent) {
        StageData.removeStageData(event.block)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onFurnitureBreak(event: FurnitureBreakEvent) {
        val loc = event.bukkitEntity.location.block.location

        if (stageManager.isTransitioning(loc)) {
            event.isCancelled = true
            return
        }

        if (stageManager.activeSequences.contains(loc)) {
            stageManager.cancelSequence(this, loc)
            event.isCancelled = true
            return
        }

        removeStageData(loc)
        setRemoving(loc)

        if (!isRemoving(loc)) {
            if (!stageManager.activeSequences.contains(loc)) {
                clearRemoving(loc)
            }
        }
    }

    private val lastFurniturePlace: MutableMap<UUID, Location> = mutableMapOf()
    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        val block = event.clickedBlock ?: return

        lastFurniturePlace[player.uniqueId] = block.location.block.location
    }

    @EventHandler
    fun onFurniturePlace(event: FurniturePlaceEvent) {
        val player = event.player
        val playerId = player.uniqueId

        FoliaUtils.runAtEntity(player) {
            val targetLoc = lastFurniturePlace[playerId] ?: player.location.block.location

            FoliaUtils.runLater(3L) {
                FoliaUtils.runAtLocation(targetLoc) {
                    val world = targetLoc.world ?: return@runAtLocation
                    val center = targetLoc.clone().add(0.5, 0.5, 0.5)

                    val placedEntity = world.getNearbyEntities(center, 1.5, 1.5, 1.5)
                        .asSequence()
                        .filter { isPossibleFurnitureEntity(it) }
                        .filter { it.isValid && !it.isDead }
                        .filter {
                            try {
                                CustomFurniture.byAlreadySpawned(it) != null
                            } catch (_: Throwable) {
                                false
                            }
                        }
                        .sortedBy { it.location.distanceSquared(center) }
                        .firstOrNull()

                    if (placedEntity != null) {
                        val loc = placedEntity.location.block.location

                        rememberFurnitureBase(placedEntity, loc)

                        if (isRemoving(loc)) {
                            clearRemoving(loc)
                        }
                    }

                    if (isRemoving(targetLoc)) {
                        clearRemoving(targetLoc)
                    }

                    lastFurniturePlace.remove(playerId)
                }
            }
        }
    }

    private fun placeBlock(adapterId: String, location: Location?) {
        CustomBlock.place(adapterId.replace("ia:", ""), location)
    }

    private fun breakBlock(location: Location?) {
        CustomBlock.remove(location)
    }

    private fun breakFurniture(entity: Entity?, player: Player?) {
        CustomFurniture.remove(entity, false)
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

                FoliaUtils.runLater(2L) {
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
            //Bukkit.getConsoleSender().sendMessage("[UM] handleFurnitureStage $loc → tick=${Bukkit.getCurrentTick()}")
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
        placeBlock(itemAdapterData.id, loc)
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

    private fun handleFurnitureStage(
        player: Player,
        itemAdapterData: AdapterData,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    ) {
        if(isRemoving(loc.block.location)){
            if(!stageManager.activeSequences.contains(loc.block.location)){
                clearRemoving(loc.block.location) }
            //Bukkit.getConsoleSender().sendMessage("[UM] Bloqueada recolocación definitiva en ${loc.block.location}")
            return
        }

        if (event is FurnitureInteractEvent) {

            val entityEvent: Entity = event.bukkitEntity
            if(!entityEvent.isValid) {
                //Bukkit.getConsoleSender().sendMessage(" NO ES VALIDO EL FURNITURE" +loc)
                return
            }

            //if(isRemoving(loc.block.location)) return
            if(isRemoving(loc.block.location)){
                if(!stageManager.activeSequences.contains(event.bukkitEntity.location.block.location)){
                    clearRemoving(event.bukkitEntity.location.block.location)
                }
                //Bukkit.getConsoleSender().sendMessage("Spawn cancelado en $loc - adapter ${itemAdapterData.id}")
                return
            }

            val currentId = "ia:" + event.namespacedID
            val hadCurrentObject = isValidFurniture(loc, currentId) || isValidBlock(loc, currentId)

            //debug("handleFurnitureStage: currentId=$currentId targetId=${itemAdapterData.id} hadCurrentObject=$hadCurrentObject")

            if (hadCurrentObject) {
                event.furniture?.remove(false)
                event.bukkitEntity.remove()
                breakBlock(event.bukkitEntity.location)
            } else {
                breakBlock(event.bukkitEntity.location)
            }
            //debug("handleFurnitureStage: BEFORE SPAWN target=${itemAdapterData.id} removing=${isRemoving(loc.block.location)} currentEventId=${event.namespacedID}")
            CustomFurniture.spawn(itemAdapterData.id, loc.block)?.let { customFurniture ->
                //Bukkit.getConsoleSender().sendMessage("[IA] spawn furniture at $loc - adapter ${itemAdapterData.id}")
                val entity: Entity = customFurniture.entity ?: return
                rememberFurnitureBase(entity, loc.block.location)

                entity.setRotation(entityEvent.location.yaw, entityEvent.location.pitch)

                if(isPossibleFurnitureEntity(entityEvent)){
                    val furniture = CustomFurniture.byAlreadySpawned(entityEvent)
                    if (furniture != null) {
                        rotationMap[loc] = Pair(entityEvent.location.yaw, entityEvent.location.pitch)
                    }
                }

                if (entityEvent is ItemFrame && entity is ItemFrame) {
                    entity.rotation = entityEvent.rotation
                    itemFrameRotationMap[loc] = entityEvent.rotation
                }
                //debug("handleFurnitureStage: AFTER SPAWN target=${itemAdapterData.id} spawned=${customFurniture.entity?.uniqueId}")

            }
            val entityLoc = event.bukkitEntity.location.block.location

            FoliaUtils.runLater(5L) {
                FoliaUtils.runAtLocation(entityLoc) {
                    if (!stageManager.activeSequences.contains(entityLoc)) {
                        clearRemoving(entityLoc)
                    }
                }
            }
        } else {
            // Sequence System
            val keyLoc = loc.block.location
            val rotation = rotationMap.remove(keyLoc)
            val cachedFrameRotation = itemFrameRotationMap.remove(keyLoc)

            if(isRemoving(loc.block.location)){
                if(!stageManager.activeSequences.contains(loc.block.location)){
                    clearRemoving(loc.block.location) }
                return
            }
            val keyBlock = keyLoc.block
            CustomFurniture.spawn(itemAdapterData.id, keyBlock)?.let { customFurniture ->
                val entity: Entity = customFurniture.entity ?: return
                rememberFurnitureBase(entity, keyLoc)
                //Bukkit.getConsoleSender().sendMessage("[IA] spawn Sequence $loc - adapter ${itemAdapterData.id}")

                if (rotation != null) entity.setRotation(rotation.first, rotation.second)
                if (cachedFrameRotation != null && entity is ItemFrame) {
                    entity.rotation = cachedFrameRotation
                }
            }
        }
    }

    private fun hardRemoveItemsAdderAt(loc: Location): Boolean {
        val keyLoc = loc.block.location
        var removed = false

        // CustomBlock
        try {
            val customBlock = CustomBlock.byAlreadyPlaced(keyLoc.block)
            if (customBlock != null) {
                CustomBlock.remove(keyLoc)
                removed = true
            }
        } catch (_: Throwable) {}

        // CustomFurniture
        val world = keyLoc.world ?: return removed
        val center = keyLoc.clone().add(0.5, 0.5, 0.5)

        for (entity in world.getNearbyEntities(center, 1.5, 1.5, 1.5)) {
            if (!isPossibleFurnitureEntity(entity) || !entity.isValid || entity.isDead) continue
            if (!isSameFurnitureArea(entity, keyLoc)) continue

            val furniture = try {
                CustomFurniture.byAlreadySpawned(entity)
            } catch (_: Throwable) {
                null
            } ?: continue

            rotationMap[keyLoc] = Pair(entity.location.yaw, entity.location.pitch)

            if (entity is ItemFrame) {
                itemFrameRotationMap[keyLoc] = entity.rotation
            }

            forgetFurnitureBase(entity)
            try {
                furniture.remove(false)
            } catch (_: Throwable) {
                entity.remove()
            }

            removed = true
        }

        if (removed) {
            StageData.removeStageData(keyLoc)
            setRemoving(keyLoc)

            FoliaUtils.runLater(2L) {
                FoliaUtils.runAtLocation(keyLoc) {
                    if (!stageManager.activeSequences.contains(keyLoc)) {
                        clearRemoving(keyLoc)
                    }
                }
            }
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
        return hardRemoveItemsAdderAt(loc)
    }

    override fun handleRemove(
        player: Player,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    ) {
        if (hardRemoveItemsAdderAt(loc)) return
        if (event is CustomBlockInteractEvent) {
            breakBlock(loc)
            return
        }
        if (event is FurnitureInteractEvent) {
            event.bukkitEntity?.let { entity ->
                if (isPossibleFurnitureEntity(entity)) {
                    val key = entity.location.block.location
                    rotationMap[key] = Pair(entity.location.yaw, entity.location.pitch)

                    if (entity is ItemFrame) {
                        itemFrameRotationMap[key] = entity.rotation
                    }
                    forgetFurnitureBase(entity)
                }
            }
            setRemoving(event.bukkitEntity.location.block.location)

            //Bukkit.getConsoleSender().sendMessage("[IA] Furniture removido en $loc")
            //val uuid = event.bukkitEntity.uniqueId
            removeStageData(event.bukkitEntity.location.block.location)
            event.furniture?.remove(false)
            return
        }

        // Sequence System
        val center = loc.clone().add(0.5, 0.5, 0.5)
        val nearby = loc.world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        var removedAnyFurniture = false

        for (entity in nearby) {
            if (!isPossibleFurnitureEntity(entity) || !entity.isValid || entity.isDead) continue
            if (!isSameFurnitureArea(entity, loc.block.location)) continue

            val furniture = try {
                CustomFurniture.byAlreadySpawned(entity)
            } catch (_: Throwable) {
                null
            } ?: continue

            val key = loc.block.location

            rotationMap[key] = Pair(entity.location.yaw, entity.location.pitch)

            if (entity is ItemFrame) {
                itemFrameRotationMap[key] = entity.rotation
            }

            forgetFurnitureBase(entity)
            furniture.remove(false)
            removedAnyFurniture = true
        }

        if (!removedAnyFurniture && loc.block.type != org.bukkit.Material.AIR) {
            //debug("handleRemove[Sequence]: no furniture found, breakBlock fallback loc=${loc.block.location}")
            breakBlock(loc)
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
        if (event is CustomBlockInteractEvent) {
            val block: Block = event.blockClicked
            return Utils.calculateHashCode(
                block.location.hashCode(),
                block.hashCode(),
                block.type.hashCode(),
                block.blockData.hashCode(),
                block.state.hashCode()
            )
        }
        if (event is FurnitureInteractEvent) {
            val entity: Entity = event.bukkitEntity
            return Utils.calculateHashCode(
                entity.location.hashCode(),
                entity.hashCode(),
                entity.type.hashCode(),
                entity.uniqueId.hashCode(),
                entity.isDead.hashCode(),
                entity.facing.hashCode()
            )
        }
        return -1
    }

    override fun getItemHand(event: Event): ItemStack? {
        if (event is CustomBlockInteractEvent) {
            return event.item
        }
        return null
    }

    override fun getBlockFace(event: Event): BlockFace? {
        if (event is CustomBlockInteractEvent) {
            return event.blockFace
        }
        return null
    }

}