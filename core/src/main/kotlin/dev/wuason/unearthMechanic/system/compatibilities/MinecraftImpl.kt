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
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
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
        val explicitProps = (stage as? Stage)?.getExplicitBlockProperties() ?: emptyMap()

        Bukkit.getScheduler().runTask(core, Runnable {

            if (
                CraftEnginePlugin.isCraftEngineEnabled() &&
                CraftEnginePlugin.isCraftEngineLoaded()
            ) {
                val handledByCe = tryReplaceWithCraftEngineBridge(
                    loc = keyLoc,
                    event = event,
                    oldData = oldData,
                    newMaterial = newMaterial,
                    explicitProps = explicitProps
                )

                if (handledByCe) {
                    return@Runnable
                }
            }

            keyLoc.block.type = newMaterial

            val newBlock = keyLoc.block
            var newData = newBlock.blockData.clone()

            newData = copyOrientationProperties(oldData, newData)
            newData = applyExplicitPropertiesToVanilla(explicitProps, newData)

            newBlock.blockData = newData
            newBlock.state.update(true, false)
        })
    }

    private fun tryReplaceWithCraftEngineBridge(
        loc: Location,
        event: Event,
        oldData: BlockData,
        newMaterial: Material,
        explicitProps: Map<String, String>
    ): Boolean {
        return try {
            val clazz = Class.forName(
                "dev.wuason.unearthMechanic.system.compatibilities.ce.CraftEngineImpl"
            )

            val companion = clazz.getField("Companion").get(null)

            val method = companion.javaClass.getMethod(
                "tryReplaceCraftEngineBlockWithVanilla",
                Location::class.java,
                Event::class.java,
                BlockData::class.java,
                Material::class.java,
                Map::class.java
            )

            method.invoke(
                companion,
                loc,
                event,
                oldData,
                newMaterial,
                explicitProps
            ) as? Boolean ?: false
        } catch (_: Throwable) {
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