package dev.wuason.unearthMechanic.system.compatibilities

import dev.wuason.adapter.Adapter
import dev.wuason.adapter.AdapterComp
import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.UnearthMechanicPlugin
import dev.wuason.unearthMechanic.compatibilities.craftengine.CraftEnginePlugin
import dev.wuason.unearthMechanic.config.*
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.StageData
import dev.wuason.unearthMechanic.system.StageManager
import dev.wuason.unearthMechanic.utils.Utils
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.bukkit.api.event.CustomBlockInteractEvent
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.block.property.type.DoubleBlockHalf
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import net.momirealms.craftengine.libraries.nbt.CompoundTag
import org.bukkit.block.data.*
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Switch
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

    override fun isValidFurniture(loc: Location, expectedAdapterId: String?): Boolean {
        return false
    }

    override fun isValidBlock(loc: Location, expectedAdapterId: String?): Boolean {
        if (expectedAdapterId == null) return loc.block.type != Material.AIR

        val cleanId = expectedAdapterId.removePrefix("mc:")
        return loc.block.type.name.equals(cleanId, ignoreCase = true)
    }

    override fun isValid(loc: Location, expectedAdapterId: String?): Boolean {
        return isValidBlock(loc, expectedAdapterId)
    }

    override fun removeFurnitureByUUID(loc: Location, uuid: UUID?): Boolean = false

    @EventHandler
    fun onInteractBlock(event: PlayerInteractEvent) {
        if (!event.hasBlock()) return
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        //core.logger.info("[UM-DBG-MC] PlayerInteract action=${event.action} hand=${event.hand} hasBlock=${event.hasBlock()} clicked=${event.clickedBlock?.type} useBlock=${event.useInteractedBlock()} cancelled=${event.isCancelled}")

        val block: Block = event.clickedBlock ?: return
        val adapterId = Adapter.getAdapterId(block)

        if (adapterId.contains("mc:") || adapterId.contains("minecraft:")) {
            stageManager.interact(event.player, adapterId, block.location, event, this)
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
            if (from is Openable && to is Openable) to.isOpen = from.isOpen
            if (from is Powerable && to is Powerable) to.isPowered = from.isPowered
            if (from is Waterlogged && to is Waterlogged) to.isWaterlogged = from.isWaterlogged

            if (from is Slab && to is Slab) to.type = from.type

            if (from is Switch && to is Switch) {
                to.face = from.face
                to.facing = from.facing
                to.isPowered = from.isPowered
            }

            if (from is org.bukkit.block.data.type.Stairs && to is org.bukkit.block.data.type.Stairs) {
                to.facing = from.facing
                to.half = from.half
                to.shape = from.shape
                to.isWaterlogged = from.isWaterlogged
            }
        } catch (_: Exception) {}

        return to
    }

    private fun applyCePropertiesToVanilla(
        ceProps: CompoundTag?,
        data: BlockData
    ): BlockData {
        if (ceProps == null) return data

        try {
            if (data is Directional && ceProps.containsKey("facing")) {
                val facing = BlockFace.valueOf(ceProps.getString("facing").uppercase(Locale.ENGLISH))
                if (data.faces.contains(facing)) data.facing = facing
            }

            if (data is Orientable && ceProps.containsKey("axis")) {
                data.axis = org.bukkit.Axis.valueOf(ceProps.getString("axis").uppercase(Locale.ENGLISH))
            }

            if (data is Bisected && ceProps.containsKey("half")) {
                val rawHalf = ceProps.getString("half").lowercase(Locale.ENGLISH)
                data.half = if (rawHalf == "upper" || rawHalf == "top") {
                    Bisected.Half.TOP
                } else {
                    Bisected.Half.BOTTOM
                }
            }

            if (data is Openable && ceProps.containsKey("open")) {
                data.isOpen = ceProps.getBoolean("open")
            }

            if (data is Powerable && ceProps.containsKey("powered")) {
                data.isPowered = ceProps.getBoolean("powered")
            }

            if (data is Waterlogged && ceProps.containsKey("waterlogged")) {
                data.isWaterlogged = ceProps.getBoolean("waterlogged")
            }

            if (data is Slab && ceProps.containsKey("type")) {
                data.type = Slab.Type.valueOf(ceProps.getString("type").uppercase(Locale.ENGLISH))
            }

            if (data is Switch && ceProps.containsKey("face")) {
                data.face = Switch.Face.valueOf(ceProps.getString("face").uppercase(Locale.ENGLISH))
            }
        } catch (_: Exception) {}

        return data
    }

    private fun applyExplicitPropertiesToVanilla(
        props: Map<String, String>,
        data: BlockData
    ): BlockData {
        try {
            props["facing"]?.let {
                if (data is Directional) {
                    val face = BlockFace.valueOf(it.uppercase(Locale.ENGLISH))
                    if (data.faces.contains(face)) data.facing = face
                }
            }

            props["axis"]?.let {
                if (data is Orientable) {
                    data.axis = org.bukkit.Axis.valueOf(it.uppercase(Locale.ENGLISH))
                }
            }

            props["half"]?.let {
                if (data is Bisected) {
                    data.half = when (it.lowercase(Locale.ENGLISH)) {
                        "upper", "top" -> Bisected.Half.TOP
                        else -> Bisected.Half.BOTTOM
                    }
                }
            }

            props["open"]?.let {
                if (data is Openable) data.isOpen = it.toBoolean()
            }

            props["powered"]?.let {
                if (data is Powerable) data.isPowered = it.toBoolean()
            }

            props["waterlogged"]?.let {
                if (data is Waterlogged) data.isWaterlogged = it.toBoolean()
            }

            props["type"]?.let {
                if (data is Slab) data.type = Slab.Type.valueOf(it.uppercase(Locale.ENGLISH))
            }

            props["face"]?.let {
                if (data is Switch) data.face = Switch.Face.valueOf(it.uppercase(Locale.ENGLISH))
            }
        } catch (_: Exception) {}

        return data
    }

    private fun findCeDoubleBlockProperty(state: ImmutableBlockState): Property<*>? {
        for (property in state.properties) {
            if (property.valueClass() == DoubleBlockHalf::class.java) {
                return property
            }
        }
        return null
    }

    private fun removeCraftEngineDoubleBlockSilent(loc: Location, state: ImmutableBlockState) {
        val doubleProp = findCeDoubleBlockProperty(state)

        if (doubleProp == null) {
            loc.block.setType(Material.AIR, false)
            return
        }

        val half = state.get(doubleProp) as DoubleBlockHalf

        val lowerLoc = when (half) {
            DoubleBlockHalf.UPPER -> loc.clone().add(0.0, -1.0, 0.0)
            DoubleBlockHalf.LOWER -> loc.clone()
        }

        val upperLoc = lowerLoc.clone().add(0.0, 1.0, 0.0)

        upperLoc.block.setType(Material.AIR, false)
        lowerLoc.block.setType(Material.AIR, false)
    }

    private fun replaceCraftEngineDoubleBlockWithVanillaDoor(
        loc: Location,
        previousBlockState: ImmutableBlockState,
        newMaterial: Material,
        ceProps: CompoundTag?,
        explicitProps: Map<String, String>
    ) {
        val doubleProp = findCeDoubleBlockProperty(previousBlockState)

        val lowerLoc = if (doubleProp != null) {
            when (previousBlockState.get(doubleProp) as DoubleBlockHalf) {
                DoubleBlockHalf.UPPER -> loc.clone().add(0.0, -1.0, 0.0)
                DoubleBlockHalf.LOWER -> loc.clone()
            }
        } else {
            loc.clone()
        }

        val upperLoc = lowerLoc.clone().add(0.0, 1.0, 0.0)

        upperLoc.block.setType(Material.AIR, false)
        lowerLoc.block.setType(Material.AIR, false)

        lowerLoc.block.setType(newMaterial, false)
        upperLoc.block.setType(newMaterial, false)

        var lowerData = lowerLoc.block.blockData.clone()
        var upperData = upperLoc.block.blockData.clone()

        lowerData = applyCePropertiesToVanilla(ceProps, lowerData)
        lowerData = applyExplicitPropertiesToVanilla(explicitProps, lowerData)

        upperData = applyCePropertiesToVanilla(ceProps, upperData)
        upperData = applyExplicitPropertiesToVanilla(explicitProps, upperData)

        if (lowerData is org.bukkit.block.data.type.Door) {
            lowerData.half = Bisected.Half.BOTTOM
        }

        if (upperData is org.bukkit.block.data.type.Door) {
            upperData.half = Bisected.Half.TOP
        }

        lowerLoc.block.setBlockData(lowerData, false)
        upperLoc.block.setBlockData(upperData, false)

        lowerLoc.block.state.update(true, false)
        upperLoc.block.state.update(true, false)
    }

    private fun isCraftEngineInteract(event: Event): Boolean {
        return event.javaClass.simpleName == "CustomBlockInteractEvent"
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
        val cleanMaterialId = itemAdapterData.id
            .removePrefix("mc:")
            .removePrefix("minecraft:")

        val newMaterial =
            Material.getMaterial(cleanMaterialId.uppercase(Locale.ENGLISH)) ?: return

        val oldData = loc.block.blockData
        val keyLoc = loc.block.location

        Bukkit.getScheduler().runTask(core, Runnable {
            val previousBlockState =
                when {
                    event is CustomBlockInteractEvent -> {
                        event.blockState()
                    }

                    CraftEnginePlugin.isCraftEngineEnabled() &&
                            CraftEnginePlugin.isCraftEngineLoaded() -> {
                        CraftEngineBlocks.getCustomBlockState(oldData)
                    }

                    else -> null
                }

            val ceProps = previousBlockState?.propertiesNbt()
            val explicitProps = (stage as? Stage)?.getExplicitBlockProperties() ?: emptyMap()

            if (previousBlockState != null && newMaterial.createBlockData() is org.bukkit.block.data.type.Door) {
                replaceCraftEngineDoubleBlockWithVanillaDoor(
                    loc = keyLoc,
                    previousBlockState = previousBlockState,
                    newMaterial = newMaterial,
                    ceProps = ceProps,
                    explicitProps = explicitProps
                )
                return@Runnable
            }

            if (previousBlockState != null) {
                removeCraftEngineDoubleBlockSilent(keyLoc, previousBlockState)
            }

            keyLoc.block.type = newMaterial

            val newBlock = keyLoc.block
            var newData = newBlock.blockData.clone()

            newData = copyOrientationProperties(oldData, newData)
            newData = applyCePropertiesToVanilla(ceProps, newData)
            newData = applyExplicitPropertiesToVanilla(explicitProps, newData)

            newBlock.blockData = newData
            newBlock.state.update(true, false)
        })
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
        return
        //throw UnsupportedOperationException("Minecraft does not support furniture stages")
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