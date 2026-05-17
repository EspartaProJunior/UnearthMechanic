package dev.wuason.unearthMechanic.system.compatibilities.crucible

import dev.wuason.adapter.Adapter
import dev.wuason.adapter.AdapterComp
import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.UnearthMechanicPlugin
import dev.wuason.unearthMechanic.config.IBlockStage
import dev.wuason.unearthMechanic.config.IFurnitureStage
import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.IStage
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.StageData
import dev.wuason.unearthMechanic.system.StageManager
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import dev.wuason.unearthMechanic.utils.Utils
import io.lumine.mythic.bukkit.BukkitAdapter
import io.lumine.mythiccrucible.MythicCrucible
import io.lumine.mythiccrucible.events.MythicFurniturePlaceEvent
import io.lumine.mythiccrucible.events.MythicFurnitureRemoveEvent
import io.lumine.mythiccrucible.items.CrucibleItem
import io.lumine.mythiccrucible.items.blocks.CustomBlockItemContext
import io.lumine.mythiccrucible.items.furniture.Furniture
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.*
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.Collections
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

class MythicCrucibleImpl(
    pluginName: String,
    private val core: UnearthMechanicPlugin,
    private val stageManager: StageManager,
    adapterComp: AdapterComp,
) : ICompatibility(
    pluginName,
    adapterComp
) {
    private val removedLocations = Collections.synchronizedSet(mutableSetOf<Location>())

    companion object {
        private val rotationMap = Collections.synchronizedMap(mutableMapOf<Location, Pair<Float, Float>>())
        private val itemFrameRotationMap = Collections.synchronizedMap(mutableMapOf<Location, org.bukkit.Rotation>())
    }

    override fun isRemoving(location: Location): Boolean {
        return removedLocations.contains(location.block.location)
    }

    override fun setRemoving(location: Location) {
        removedLocations.add(location.block.location)
    }

    override fun clearRemoving(location: Location) {
        removedLocations.remove(location.block.location)
    }

    private fun removeStageData(location: Location) {
        StageData.removeStageData(location)
    }

    private fun cleanAdapterId(id: String?): String? {
        return id
            ?.removePrefix("crucible:")
            ?.removePrefix("mythiccrucible:")
            ?.substringBefore("[")
            ?.takeIf { it.isNotBlank() }
    }

    private fun getCrucibleItem(adapterId: String?): CrucibleItem? {
        val cleanId = cleanAdapterId(adapterId) ?: return null
        return MythicCrucible.inst().getItemManager().getItem(cleanId).orElse(null)
    }

    private fun getBlockContext(adapterId: String?): CustomBlockItemContext? {
        return getCrucibleItem(adapterId)?.blockData
    }

    private fun getBlockContextAt(block: Block): CustomBlockItemContext? {
        val adapterId = adapterComp().getAdapterId(block)
        return getBlockContext(adapterId)
    }

    private fun getFurnitureAt(loc: Location): Furniture? {
        return MythicCrucible.inst()
            .getItemManager()
            .getFurnitureManager()
            .getFurniture(loc.block)
            .orElse(null)
    }

    private fun getFurnitureFromEntity(entity: Entity): Furniture? {
        return MythicCrucible.inst()
            .getItemManager()
            .getFurnitureManager()
            .getFurniture(BukkitAdapter.adapt(entity))
            .orElse(null)
    }

    private fun getCrucibleBlockId(block: Block): String? {
        return cleanAdapterId(adapterComp().getAdapterId(block))
    }

    private fun getCrucibleFurnitureId(entity: Entity): String? {
        return cleanAdapterId(adapterComp().getAdapterId(entity))
    }

    private fun getCrucibleIdAt(loc: Location): String? {
        return getCrucibleBlockId(loc.block)
    }

    private fun isCustomBlock(block: Block): Boolean {
        return getCrucibleBlockId(block) != null
    }

    private fun isPossibleFurnitureEntity(entity: Entity): Boolean {
        return entity is ItemFrame ||
                entity is ArmorStand ||
                entity is ItemDisplay ||
                entity is TextDisplay ||
                entity is Interaction ||
                entity is BlockDisplay
    }

    private fun findFurnitureEntity(location: Location): Entity? {
        val keyLoc = location.block.location

        val furniture = getFurnitureAt(keyLoc)
        if (furniture != null) {
            return furniture.frame
        }

        val world = keyLoc.world ?: return null
        val center = keyLoc.clone().add(0.5, 0.5, 0.5)

        return world.getNearbyEntities(center, 1.5, 1.5, 1.5)
            .asSequence()
            .filter { it.isValid && !it.isDead }
            .filter { isPossibleFurnitureEntity(it) }
            .firstOrNull { adapterComp().getAdapterId(it) != null }
    }

    override fun getFurnitureUUID(location: Location): UUID? {
        return findFurnitureEntity(location)?.uniqueId
    }

    override fun isValidBlock(loc: Location, expectedAdapterId: String?): Boolean {
        val currentId = getCrucibleBlockId(loc.block) ?: return false
        val expected = cleanAdapterId(expectedAdapterId)

        return expected == null || currentId.equals(expected, ignoreCase = true)
    }

    override fun isValidFurniture(loc: Location, expectedAdapterId: String?): Boolean {
        val furniture = findFurnitureEntity(loc) ?: return false
        val currentId = getCrucibleFurnitureId(furniture) ?: return false
        val expected = cleanAdapterId(expectedAdapterId)

        return expected == null || currentId.equals(expected, ignoreCase = true)
    }

    override fun isValidUUID(
        loc: Location,
        expectedAdapterId: String?,
        expectedUuid: UUID?
    ): Boolean {
        val keyLoc = loc.block.location

        if (expectedUuid != null && isRemoving(keyLoc)) {
            return true
        }

        val furniture = findFurnitureEntity(keyLoc) ?: return false
        val currentId = getCrucibleFurnitureId(furniture)
        val expected = cleanAdapterId(expectedAdapterId)

        if (expectedUuid != null && furniture.uniqueId == expectedUuid) {
            return true
        }

        return expected != null &&
                currentId != null &&
                currentId.equals(expected, ignoreCase = true)
    }

    override fun removeFurnitureByUUID(loc: Location, uuid: UUID?): Boolean {
        if (uuid == null) return false

        val keyLoc = loc.block.location
        val world = keyLoc.world ?: return false
        val center = keyLoc.clone().add(0.5, 0.5, 0.5)

        val entity = world.getNearbyEntities(center, 1.5, 1.5, 1.5)
            .firstOrNull {
                it.uniqueId == uuid &&
                        it.isValid &&
                        !it.isDead &&
                        isPossibleFurnitureEntity(it) &&
                        getCrucibleFurnitureId(it) != null
            } ?: return false

        rotationMap[keyLoc] = entity.location.yaw to entity.location.pitch

        if (entity is ItemFrame) {
            itemFrameRotationMap[keyLoc] = entity.rotation
        }

        setRemoving(keyLoc)
        removeStageData(keyLoc)
        removeFurnitureEntity(entity, null, false)

        Bukkit.getScheduler().runTaskLater(core, Runnable {
            if (!stageManager.activeSequences.contains(keyLoc)) {
                clearRemoving(keyLoc)
            }
        }, 2L)

        return true
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    fun onInteractBlock(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return

        val block = event.clickedBlock ?: return
        val blockId = getCrucibleIdAt(block.location) ?: return

        stageManager.interact(
            event.player,
            "crucible:$blockId",
            block.location,
            event,
            this
        )
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    fun onInteractFurniture(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return

        val entity = event.rightClicked
        val furnitureId = getCrucibleFurnitureId(entity) ?: return
        val loc = entity.location.block.location

        if (stageManager.isTransitioning(loc)) {
            event.isCancelled = true
            return
        }

        stageManager.interact(
            event.player,
            "crucible:$furnitureId",
            loc,
            event,
            this
        )
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    fun onInteractFurniturePrecise(event: PlayerInteractAtEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return

        val entity = event.rightClicked
        val furnitureId = getCrucibleFurnitureId(entity) ?: return
        val loc = entity.location.block.location

        if (stageManager.isTransitioning(loc)) {
            event.isCancelled = true
            return
        }

        stageManager.interact(
            event.player,
            "crucible:$furnitureId",
            loc,
            event,
            this
        )
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (getCrucibleIdAt(event.block.location) == null) return
        removeStageData(event.block.location)
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    fun onCrucibleFurnitureRemove(event: MythicFurnitureRemoveEvent) {
        val loc = event.furniture.frame.location.block.location

        removeStageData(loc)
        setRemoving(loc)

        Bukkit.getScheduler().runTaskLater(core, Runnable {
            if (!stageManager.activeSequences.contains(loc)) {
                clearRemoving(loc)
            }
        }, 2L)
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    fun onCrucibleFurniturePlace(event: MythicFurniturePlaceEvent) {
        val loc = event.block.location.block.location

        Bukkit.getScheduler().runTaskLater(core, Runnable {
            if (isRemoving(loc)) {
                clearRemoving(loc)
            }
        }, 3L)
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
            is IBlockStage -> handleBlockStage(player, itemAdapterData, event, loc, toolUsed, generic, stage)
            is IFurnitureStage -> Bukkit.getScheduler().runTaskLater(core, Runnable {
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
        when (stage) {
            is IBlockStage -> handleBlockStage(player, itemAdapterData, event, loc, toolUsed, generic, stage)
            is IFurnitureStage -> handleFurnitureStage(player, itemAdapterData, event, loc, toolUsed, generic, stage)
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
        val keyLoc = loc.block.location

        findFurnitureEntity(keyLoc)?.let { previous ->
            rotationMap[keyLoc] = previous.location.yaw to previous.location.pitch

            if (previous is ItemFrame) {
                itemFrameRotationMap[keyLoc] = previous.rotation
            }

            removeFurnitureEntity(previous, player, false)
        }

        placeBlock(player, itemAdapterData.id, keyLoc)
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
            if (!stageManager.activeSequences.contains(keyLoc)) {
                clearRemoving(keyLoc)
            }
            return
        }

        findFurnitureEntity(keyLoc)?.let { previous ->
            rotationMap[keyLoc] = previous.location.yaw to previous.location.pitch

            if (previous is ItemFrame) {
                itemFrameRotationMap[keyLoc] = previous.rotation
            }

            removeFurnitureEntity(previous, player, false)
        }

        if (isCustomBlock(keyLoc.block)) {
            removeBlock(player, keyLoc, false)
        }

        spawnFurniture(player, itemAdapterData.id, keyLoc)

        Bukkit.getScheduler().runTaskLater(core, Runnable {
            if (!stageManager.activeSequences.contains(keyLoc)) {
                clearRemoving(keyLoc)
            }
        }, 5L)
    }

    private fun hardRemoveCrucibleAt(
        loc: Location,
        player: Player?,
        doActions: Boolean = false
    ): Boolean {
        val keyLoc = loc.block.location
        var removed = false

        findFurnitureEntity(keyLoc)?.let { furnitureEntity ->
            rotationMap[keyLoc] = furnitureEntity.location.yaw to furnitureEntity.location.pitch

            if (furnitureEntity is ItemFrame) {
                itemFrameRotationMap[keyLoc] = furnitureEntity.rotation
            }

            setRemoving(keyLoc)
            removeStageData(keyLoc)
            removeFurnitureEntity(furnitureEntity, player, doActions)
            removed = true
        }

        if (isCustomBlock(keyLoc.block)) {
            setRemoving(keyLoc)
            removeStageData(keyLoc)
            removeBlock(player, keyLoc, doActions)
            removed = true
        }

        if (removed) {
            Bukkit.getScheduler().runTaskLater(core, Runnable {
                if (!stageManager.activeSequences.contains(keyLoc)) {
                    clearRemoving(keyLoc)
                }
            }, 2L)
        }

        return removed
    }

    override fun handleRemove(
        player: Player,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    ) {
        hardRemoveCrucibleAt(loc,player,false)
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
        return hardRemoveCrucibleAt(loc, player, false)
    }

    override fun hashCode(
        player: Player,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: Int
    ): Int {
        val furniture = findFurnitureEntity(loc)
        if (furniture != null) {
            return Utils.calculateHashCode(
                furniture.location.hashCode(),
                furniture.hashCode(),
                furniture.type.hashCode(),
                furniture.uniqueId.hashCode(),
                furniture.isDead.hashCode()
            )
        }

        val block = loc.block
        return Utils.calculateHashCode(
            block.location.hashCode(),
            block.hashCode(),
            block.type.hashCode(),
            block.blockData.hashCode(),
            block.state.hashCode()
        )
    }

    override fun getItemHand(event: Event): ItemStack? {
        return when (event) {
            is PlayerInteractEvent -> event.item
            is PlayerInteractEntityEvent -> event.player.inventory.itemInMainHand
            is PlayerInteractAtEntityEvent -> event.player.inventory.itemInMainHand
            else -> null
        }
    }

    override fun getBlockFace(event: Event): BlockFace? {
        return when (event) {
            is PlayerInteractEvent -> event.blockFace
            else -> null
        }
    }

    override fun getCurrentAdapterDataAt(
        event: Event,
        loc: Location
    ): AdapterData? {
        val id = when (event) {
            is PlayerInteractEntityEvent -> getCrucibleFurnitureId(event.rightClicked)
            is PlayerInteractAtEntityEvent -> getCrucibleFurnitureId(event.rightClicked)
            else -> getCrucibleIdAt(loc)
        } ?: return null

        return Adapter.getAdapterData("crucible:$id").getOrNull()
    }

    override fun getCurrentBlockPropsFromEvent(
        event: Event,
        loc: Location
    ): Map<String, String> {
        return emptyMap()
    }

    private fun placeBlock(
        player: Player,
        adapterId: String,
        loc: Location
    ): Boolean {
        val blockData = getBlockContext(adapterId) ?: return false
        return blockData.place(player, loc.block)
    }

    private fun removeBlock(
        player: Player?,
        loc: Location,
        doActions: Boolean = false
    ): Boolean {
        val blockData = getBlockContextAt(loc.block) ?: return false
        val removed = blockData.remove(loc.block, player, doActions)

        if (removed) {
            loc.block.setType(Material.AIR,false)
        }

        return removed
    }

    private fun spawnFurniture(
        player: Player,
        adapterId: String,
        loc: Location
    ): Entity? {
        val item = getCrucibleItem(adapterId) ?: return null
        val furnitureData = item.furnitureData ?: return null

        val keyLoc = loc.block.location
        val yaw = rotationMap.remove(keyLoc)?.first ?: player.location.yaw
        val cachedFrameRotation = itemFrameRotationMap.remove(keyLoc)

        val oldYaw = player.location.yaw
        val oldPitch = player.location.pitch

        try {
            player.setRotation(yaw, oldPitch)

            val placed = furnitureData.place(
                player,
                keyLoc.block,
                BlockFace.UP,
                null
            )

            if (!placed) return null
        } finally {
            player.setRotation(oldYaw, oldPitch)
        }

        val furniture = getFurnitureAt(keyLoc) ?: return findFurnitureEntity(keyLoc)?.let { return it }

        if (cachedFrameRotation != null && furniture.frame is ItemFrame) {
            (furniture.frame as ItemFrame).rotation = cachedFrameRotation
        }

        return furniture.frame
    }

    private fun removeFurniture(
        furniture: Furniture,
        breaker: Entity?,
        doActions: Boolean = false
    ): Boolean {
        return furniture.furnitureData.remove(
            furniture,
            breaker,
            doActions,
            true
        )
    }

    private fun removeFurnitureEntity(
        entity: Entity,
        breaker: Entity?,
        doActions: Boolean = false
    ): Boolean {
        val furniture = getFurnitureFromEntity(entity) ?: return false
        return removeFurniture(furniture, breaker, doActions)
    }
}