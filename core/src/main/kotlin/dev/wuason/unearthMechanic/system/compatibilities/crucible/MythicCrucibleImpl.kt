package dev.wuason.unearthMechanic.system.compatibilities.crucible

import dev.wuason.adapter.AdapterComp
import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.UnearthMechanic
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
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.Collections
import java.util.UUID

class MythicCrucibleImpl(
    pluginName: String,
    private val core: UnearthMechanicPlugin,
    private val stageManager: StageManager,
    adapterComp: AdapterComp,
    private val bridge: CrucibleBridge
) : ICompatibility(pluginName, adapterComp) {
    private val removedLocations = Collections.synchronizedSet(mutableSetOf<Location>())
    private val lastFurniturePlace = mutableMapOf<UUID, Location>()

    companion object {
        private val rotationMap = mutableMapOf<Location, Pair<Float, Float>>()
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

    fun removeStageData(location: Location) {
        StageData.removeStageData(location)
    }

    private fun isPossibleFurnitureEntity(entity: Entity): Boolean {
        return entity is ItemFrame ||
                entity is ItemDisplay ||
                entity is TextDisplay ||
                entity is Interaction ||
                bridge.isFurnitureEntity(entity)
    }

    override fun getFurnitureUUID(location: Location): UUID? {
        return bridge.findFurnitureEntity(location)?.uniqueId
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

        val furniture = bridge.findFurnitureEntity(keyLoc) ?: return false
        val foundId = bridge.getFurnitureId(furniture)?.let { "crucible:$it" }

        if (expectedUuid != null && furniture.uniqueId == expectedUuid) {
            return true
        }

        if (expectedAdapterId != null && expectedAdapterId.equals(foundId, ignoreCase = true)) {
            return true
        }

        return false
    }

    override fun isValid(loc: Location, expectedAdapterId: String?): Boolean {
        val blockId = bridge.getBlockId(loc.block)
        if (blockId != null) {
            return expectedAdapterId == null || expectedAdapterId.equals("crucible:$blockId", true)
        }

        val furniture = bridge.findFurnitureEntity(loc)
        if (furniture != null) {
            val furnitureId = bridge.getFurnitureId(furniture)
            return expectedAdapterId == null || expectedAdapterId.equals("crucible:$furnitureId", true)
        }

        return false
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    fun onInteractBlock(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return

        val block = event.clickedBlock ?: return
        val blockId = bridge.getBlockId(block) ?: return

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
        val furnitureId = bridge.getFurnitureId(entity) ?: return
        val loc = bridge.getFurnitureAnchor(entity) ?: entity.location.block.location

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
        val furnitureId = bridge.getFurnitureId(entity) ?: return
        val loc = bridge.getFurnitureAnchor(entity) ?: entity.location.block.location

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
        if (!bridge.isCustomBlock(event.block)) return
        removeStageData(event.block.location)
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    fun onPlayerInteractForPlacement(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val clicked = event.clickedBlock ?: return
        lastFurniturePlace[event.player.uniqueId] = clicked.location
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastFurniturePlace.remove(event.player.uniqueId)
    }

    private fun placeBlock(adapterId: String, location: Location) {
        bridge.placeBlock(adapterId.removePrefix("crucible:"), location)
    }

    private fun breakBlock(location: Location) {
        bridge.removeBlock(location)
    }

    private fun spawnFurniture(adapterId: String, location: Location): Entity? {
        return bridge.spawnFurniture(adapterId.removePrefix("crucible:"), location)
    }

    private fun breakFurniture(entity: Entity) {
        bridge.removeFurniture(entity)
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
        placeBlock(itemAdapterData.id, loc)
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

        val previous = bridge.findFurnitureEntity(keyLoc)
        if (previous != null) {
            rotationMap[keyLoc] = previous.location.yaw to previous.location.pitch
            breakFurniture(previous)
        } else {
            breakBlock(keyLoc)
        }

        val spawned = spawnFurniture(itemAdapterData.id, keyLoc) ?: return
        rotationMap[keyLoc]?.let { (yaw, pitch) ->
            spawned.setRotation(yaw, pitch)
        }

        Bukkit.getScheduler().runTaskLater(UnearthMechanic.getInstance(), Runnable {
            if (!stageManager.activeSequences.contains(keyLoc)) {
                clearRemoving(keyLoc)
            }
        }, 5L)
    }

    override fun handleRemove(
        player: Player,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    ) {
        val keyLoc = loc.block.location

        val furniture = bridge.findFurnitureEntity(keyLoc)
        if (furniture != null) {
            rotationMap[keyLoc] = furniture.location.yaw to furniture.location.pitch
            setRemoving(keyLoc)
            removeStageData(keyLoc)
            breakFurniture(furniture)
            return
        }

        if (bridge.isCustomBlock(loc.block)) {
            breakBlock(keyLoc)
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
        val furniture = bridge.findFurnitureEntity(loc)
        if (furniture != null) {
            return Utils.calculateHashCode(
                furniture.location.hashCode(),
                furniture.hashCode(),
                furniture.type.hashCode(),
                furniture.uniqueId.hashCode(),
                furniture.isDead.hashCode()
            )
        }

        val block: Block = loc.block
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
            else -> null
        }
    }

    override fun getBlockFace(event: Event): BlockFace? {
        return when (event) {
            is PlayerInteractEvent -> event.blockFace
            else -> null
        }
    }
}