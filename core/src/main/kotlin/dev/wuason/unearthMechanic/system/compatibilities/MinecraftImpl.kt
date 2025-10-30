package dev.wuason.unearthMechanic.system.compatibilities

import dev.wuason.libs.adapter.Adapter
import dev.wuason.libs.adapter.AdapterComp
import dev.wuason.libs.adapter.AdapterData
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.UnearthMechanicPlugin
import dev.wuason.unearthMechanic.compatibilities.craftengine.CraftEnginePlugin
import dev.wuason.unearthMechanic.config.*
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.StageData
import dev.wuason.unearthMechanic.system.StageManager
import dev.wuason.unearthMechanic.utils.Utils
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.properties.Property
import net.momirealms.craftengine.core.block.properties.type.DoubleBlockHalf
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Orientable
import org.bukkit.block.data.Rotatable
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Stairs
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

    private val lastBlockData = mutableMapOf<Location, BlockData>()

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

    fun copyOrientationProperties(from: BlockData, to: BlockData): BlockData {
        try {
            if (from is Directional && to is Directional) to.facing = from.facing
            if (from is Rotatable && to is Rotatable) to.rotation = from.rotation
            if (from is Orientable && to is Orientable) to.axis = from.axis
            if (from is Bisected && to is Bisected) to.half = from.half
            if (from is Stairs && to is Stairs) {
                to.facing = from.facing
                to.half = from.half
            }
        } catch (e: Exception) {
            //Bukkit.getConsoleSender().sendMessage("❌ [DEBUG] Error aplicando orientación: ${e.message}")
        }

        return to
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

        val state1 = loc.block.blockData //lastBlockData[loc]
        val keyLoc = loc.block.location

        state1?.let { it1 ->
            Bukkit.getScheduler().runTaskLater(UnearthMechanic.getInstance(), Runnable {
                val newBlock = keyLoc.block
                val state2 = newBlock.blockData

                //Bukkit.getConsoleSender().sendMessage("📦 [2DEBUG] Block colocado en $loc: ${newBlock.type}")
                //Bukkit.getConsoleSender().sendMessage("🔎 [2DEBUG] BlockData actual: ${state2}")
                //Bukkit.getConsoleSender().sendMessage("📄 [2DEBUG] BlockData original: ${it1}")

                if(CraftEnginePlugin.isCraftEngineEnabled() && CraftEnginePlugin.isCraftEngineLoaded()) {
                    //Bukkit.getLogger().info("CraftEnginePlugin.isCraftEngineLoaded()")
                    val previousBlockState : ImmutableBlockState? = CraftEngineBlocks.getCustomBlockState(state1);
                    if(previousBlockState != null) {
                        //Bukkit.getLogger().info("previousBlockState != null")
                        var doubleBlockProperty : Property<*>? = null;
                        for (property in previousBlockState.properties) {
                            if (property.valueClass() == DoubleBlockHalf::class.java) {
                                doubleBlockProperty = property
                                break
                            }
                        }
                        if (doubleBlockProperty != null) {
                            val half = previousBlockState.get(doubleBlockProperty) as DoubleBlockHalf;
                            when(half) {
                                DoubleBlockHalf.UPPER -> {
                                    //Bukkit.getLogger().info("DoubleBlockHalf.UPPER")
                                    CraftEngineBlocks.remove(loc.block)
                                    CraftEngineBlocks.remove(loc.clone().add(0.0, -1.0,0.0).block)
                                }
                                DoubleBlockHalf.LOWER -> {
                                    //Bukkit.getLogger().info("DoubleBlockHalf.LOWER")
                                    CraftEngineBlocks.remove(loc.block)
                                }
                            }
                        }
                    }else{
                        //Bukkit.getLogger().info("previousBlockState == null")
                        when(state1) {
                            is Door -> {
                                //Bukkit.getLogger().info("is Door")
                                val half = state1.half
                                if(half != null){
                                    if(half == Bisected.Half.TOP){
                                        //Bukkit.getLogger().info("Bisected.Half.TOP")
                                        CraftEngineBlocks.remove(loc.clone().add(0.0, -1.0,0.0).block)

                                    }else if(half == Bisected.Half.BOTTOM){
                                        //Bukkit.getLogger().info("Bisected.Half.BOTTOM")
                                        CraftEngineBlocks.remove(loc.clone().add(0.0, 1.0,0.0).block)
                                    }
                                }
                            }
                        }
                    }
                }

                val combinedData = it1?.let { copyOrientationProperties(it, state2.clone()) } ?: state2

                //Bukkit.getConsoleSender().sendMessage("🛠️ [2DEBUG] BlockData combinado: ${combinedData}")

                newBlock.blockData = combinedData
                val result = newBlock.state.update(true, false)
                //Bukkit.getConsoleSender().sendMessage("✅ [DEBUG] ¿Bloque actualizado?: $result")
            }, 2L)
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
            val data = loc.block.blockData
            lastBlockData[loc.block.location.block.location] = data
            //Bukkit.getConsoleSender().sendMessage("💾 [DEBUG2] Guardado BlockData en $loc: $data")

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