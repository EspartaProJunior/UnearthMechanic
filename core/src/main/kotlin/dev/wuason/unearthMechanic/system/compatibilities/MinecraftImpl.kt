package dev.wuason.unearthMechanic.system.compatibilities

import dev.wuason.libs.adapter.Adapter
import dev.wuason.libs.adapter.AdapterComp
import dev.wuason.libs.adapter.AdapterData
import dev.wuason.unearthMechanic.UnearthMechanicPlugin
import dev.wuason.unearthMechanic.config.*
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.StageData
import dev.wuason.unearthMechanic.system.StageManager
import dev.wuason.unearthMechanic.utils.Utils
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.*

class MinecraftImpl(
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

    override fun getFurnitureUUID(location: Location): UUID? {
        return null
    }

    override fun isValidUUID(loc: Location, expectedAdapterId: String?, expectedUuid: UUID?): Boolean {
        return false
    }

    override fun isValid(loc: Location, expectedAdapterId: String?): Boolean {
        if (loc.block.type != Material.AIR){
            Bukkit.getConsoleSender().sendMessage("[UM] loc.block.type != Material.AIR")
            return true
        }

        return false
    }

    @EventHandler
    fun onInteractBlock(event: PlayerInteractEvent) {
        if (event.hasBlock() && event.hand == EquipmentSlot.HAND && event.action == Action.RIGHT_CLICK_BLOCK && event.useInteractedBlock() == Event.Result.ALLOW) {
            val block: Block = event.clickedBlock?: return
            val adapterId = Adapter.getAdapterId(block)
            if (adapterId.contains("mc:")) stageManager.interact(event.player, adapterId, block.location, event, this)
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val stageData: StageData = StageData.fromBlock(event.block) ?: return
        val stageDataBack: StageData = stageData.getBackStageData() ?: return
        if(stageDataBack.getActualAdapterData().adapter == adapterComp()) {
            StageData.removeStageData(event.block)
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
        loc.block.type = Material.getMaterial(itemAdapterData.id.uppercase(Locale.ENGLISH)) ?: return
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
        throw UnsupportedOperationException("Minecraft does not support furniture stages")
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
        }
        else if (stage is IFurnitureStage) {
            Bukkit.getScheduler().runTaskLater(core, Runnable {
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

    override fun handleRemove(
        player: Player,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    ) {
        if (event is PlayerInteractEvent) {
            loc.block.type = Material.AIR
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
        if (event is PlayerInteractEvent) {
            val block: Block = event.clickedBlock!!
            return Utils.calculateHashCode(block.type.hashCode(), block.blockData.hashCode(), block.state.hashCode(), block.hashCode())
        }
        return -1
    }

    override fun getItemHand(event: Event): ItemStack? {
        if (event is PlayerInteractEvent) {
            return event.item
        }
        return null
    }

    override fun getBlockFace(event: Event): BlockFace? {
        if (event is PlayerInteractEvent) {
            return event.blockFace
        }
        return null
    }

}