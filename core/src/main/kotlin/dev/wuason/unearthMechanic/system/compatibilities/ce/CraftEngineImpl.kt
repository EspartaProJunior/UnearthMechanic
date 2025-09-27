package dev.wuason.unearthMechanic.system.compatibilities.ce

import dev.wuason.libs.adapter.AdapterComp
import dev.wuason.libs.adapter.AdapterData
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.UnearthMechanicPlugin
import dev.wuason.unearthMechanic.config.*
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.StageData
import dev.wuason.unearthMechanic.system.StageManager
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import dev.wuason.unearthMechanic.utils.Utils
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent
import net.momirealms.craftengine.bukkit.api.event.CustomBlockInteractEvent
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent
import net.momirealms.craftengine.core.entity.player.InteractionHand
import net.momirealms.craftengine.libraries.nbt.CompoundTag
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.inventory.ItemStack

import net.momirealms.craftengine.core.util.Key
import net.momirealms.craftengine.core.entity.furniture.AnchorType
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import java.util.Collections
import java.util.UUID

class CraftEngineImpl(
    pluginName: String,
    private val core: UnearthMechanicPlugin,
    private val stageManager: StageManager,
    adapterComp: AdapterComp,
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
        private val rotationMap = mutableMapOf<Location, Pair<Float, Float>>()
        val itemFrameRotationMap = mutableMapOf<Location, org.bukkit.Rotation>()
    }

    fun removeStageData(location: Location){
        StageData.removeStageData(location)
    }

    override fun getFurnitureUUID(location: Location): UUID? {
        val world = location.world ?: return null

        val entities = world.getNearbyEntities(location, 1.0, 1.0, 1.0)
        for (entity in entities) {
            try {
                val furniture = CraftEngineFurniture.getLoadedFurnitureByBaseEntity(entity)
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

    fun isPossibleFurnitureEntity(entity: Entity): Boolean {
        return entity is ItemFrame || entity is ArmorStand || entity is ItemDisplay || entity is TextDisplay
                || entity is Interaction || entity is BlockDisplay
    }

    override fun isValidUUID(loc: Location, expectedAdapterId: String?, expectedUuid: UUID?): Boolean {
        val keyLoc = loc.block.location
        val world = keyLoc.world ?: return false

        //Bukkit.getConsoleSender().sendMessage("[CE][isValidFurniture] ENTER keyLoc=$keyLoc expectedUuid=$expectedUuid expectedAdapterId=$expectedAdapterId")

        val center = keyLoc.clone().add(0.5, 0.5, 0.5)
        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        //Bukkit.getConsoleSender().sendMessage("[CE][isValidFurniture] nearby.size=${nearby.size}")

        for (entity in nearby) {
            val entityBlock = entity.location.block.location
            //Bukkit.getConsoleSender().sendMessage("[CE][Entity] ${entity.type} UUID=${entity.uniqueId} block=$entityBlock")

            if (entityBlock != keyLoc) continue
            if (!entity.isValid || entity.isDead || !isPossibleFurnitureEntity(entity)) continue

            var adapterId: String? = null

            for (key in entity.persistentDataContainer.keys) {
                val value = try {
                    entity.persistentDataContainer.get(key, PersistentDataType.STRING)
                } catch (ex: IllegalArgumentException) {
                    null
                }

                if (value != null) {
                    //Bukkit.getConsoleSender().sendMessage("[CE][PDC] ${key.namespace}:${key.key} = $value")
                    if (adapterId == null) {
                        adapterId = value
                    }
                } else {
                    //Bukkit.getConsoleSender().sendMessage("[CE][PDC] ${key.namespace}:${key.key} (tipo no STRING o nulo)")
                }
            }

            //Bukkit.getConsoleSender().sendMessage("[CE][FurnitureData] id=$adapterId")

            if (expectedUuid != null && entity.uniqueId == expectedUuid) {
                //Bukkit.getConsoleSender().sendMessage("[CE][isValidFurniture] ✅ MATCH por UUID")
                return true
            }

            if (expectedAdapterId != null && adapterId != null && adapterId.equals(expectedAdapterId.removePrefix("ce:"), ignoreCase = true)) {
                //Bukkit.getConsoleSender().sendMessage("[CE][isValidFurniture] ✅ MATCH por adapterId")
                return true
            }

            //Bukkit.getConsoleSender().sendMessage("[CE][isValidFurniture] ❌ mismatch: id=$adapterId")
        }

        //Bukkit.getConsoleSender().sendMessage("[CE][isValidFurniture] ❌ SIN MATCH en $keyLoc")
        return false
    }

    override fun isValid(loc: Location, expectedAdapterId: String?): Boolean {
        val world = loc.world ?: return false

        val nearby = world.getNearbyEntities(loc, 0.5, 1.0, 0.5)
        for (entity in nearby) {
            try {
                val furniture = CraftEngineFurniture.getLoadedFurnitureByBaseEntity(entity)
                if (furniture != null && entity.isValid && !entity.isDead) {
                    return true
                }
            } catch (_: Exception) {
                continue
            }
        }
        if (loc.block.type != org.bukkit.Material.AIR) return true

        return false
    }

    @EventHandler
    fun onInteractBlock(event: CustomBlockInteractEvent) {
        if (event.hand() != InteractionHand.MAIN_HAND) return
        val adapterId = "ce:" + event.customBlock().id()

        stageManager.interact(event.player(),
            adapterId,
            event.location(),
            event,
            this)
    }

    @EventHandler
    fun onInteractFurniture(event: FurnitureInteractEvent) {
        if (stageManager.isTransitioning(event.furniture().baseEntity().location.block.location)) {
            event.isCancelled = true
            return
        }

        val uuid = event.furniture().baseEntity().uniqueId

        if (event.furniture().baseEntity() != null && event.furniture().baseEntity().uniqueId == uuid) {
            val adapterId = "ce:" + event.furniture().id()
            stageManager.interact(
                event.player,
                adapterId,
                event.location(),
                event,
                this)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBlockBreak(event: CustomBlockBreakEvent) {
        StageData.removeStageData(event.bukkitBlock())
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onFurnitureBreak(event: FurnitureBreakEvent) {
        if (stageManager.isTransitioning(event.furniture().baseEntity().location.block.location)) {
            event.isCancelled = true
            return
        }

        val loc = event.furniture().baseEntity().location.block.location

        removeStageData(loc)
        setRemoving(loc)

        if(!isRemoving(loc)){
            if(!stageManager.activeSequences.contains(loc)){
                clearRemoving(loc)
            }
        }
    }

    @EventHandler
    fun onFurniturePlace(event: FurniturePlaceEvent) {

        Bukkit.getScheduler().runTaskLater(UnearthMechanic.getInstance(), Runnable {
            clearRemoving(event.furniture().location().block.location)
            //Bukkit.getConsoleSender().sendMessage("[DEBUG] Furniture desbloqueado en ${event.furniture().location().block.location}")
        }, 3L)
    }

    private fun placeBlock(adapterId: String, location: Location?) {
        val furnitureId = Key.of(adapterId.replace("ce:", ""))
        val furniture = CraftEngineFurniture.byId(furnitureId)
        val anchor = furniture?.getAnyAnchorType() ?: AnchorType.GROUND

        CraftEngineFurniture.place(location,
            furnitureId,
            anchor,
            false)
    }

    private fun breakBlock(location: Location?) {
        if (location != null) {
            CraftEngineBlocks.remove(location.block)
        }
    }

    private fun replaceFurniture(adapterId: String, entity: Entity) {
        //val customFurniture = CustomFurniture.byAlreadySpawned(entity)
        //customFurniture!!.replaceFurniture(adapterId.replace("ce:", ""))
    }

    private fun breakFurniture(entity: Entity, player: Player?) {
        CraftEngineFurniture.remove(entity)
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
        CraftEngineBlocks.place(
            loc,
            Key.of(itemAdapterData.id.removePrefix("ce:")),
            CompoundTag(),
            false
        )
        //placeBlock(itemAdapterData.id, loc)
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
        if(isRemoving(loc.block.location.block.location)){
            if(!stageManager.activeSequences.contains(loc.block.location)){
                clearRemoving(loc.block.location) }
            return
        }

        if (event is FurnitureInteractEvent) {
            val entityEvent: Entity = event.furniture().baseEntity()

            if (!entityEvent.isValid || entityEvent.isDead) return

            if(isRemoving(loc.block.location)){
                if(!stageManager.activeSequences.contains(loc.block.location.block.location)){
                    clearRemoving(loc.block.location) }
                return
            }

            if(isValid(loc, itemAdapterData.toString())){
                CraftEngineFurniture.remove(event.furniture().baseEntity())
                event.furniture().baseEntity().remove()
                breakBlock(event.furniture().baseEntity().location.block.location)
            }else{
                breakBlock(event.furniture().baseEntity().location.block.location)
            }

            // Spawn of the new furniture
            val furnitureId = Key.of(itemAdapterData.id.removePrefix("ce:"))
            val furniture = CraftEngineFurniture.byId(furnitureId)
            val anchor = furniture?.getAnyAnchorType() ?: AnchorType.GROUND
            CraftEngineFurniture.place(loc,
                furnitureId,
                anchor,
                false)?.let { customFurniture ->

                val entity: Entity = customFurniture.baseEntity() ?: return
                entity.setRotation(entityEvent.location.yaw, entityEvent.location.pitch)

                CraftEngineFurniture.isFurniture(entity)?.let { entity ->
                    rotationMap[loc] = Pair(entityEvent.location.yaw, entityEvent.location.pitch)
                }

                if (entity is ItemFrame && entity is ItemFrame) {
                    entity.rotation = entity.rotation
                    itemFrameRotationMap[loc] = entity.rotation
                }
            }
            Bukkit.getScheduler().runTaskLater(UnearthMechanic.getInstance(), Runnable {
                if(!stageManager.activeSequences.contains(event.furniture().baseEntity().location.block.location)){
                    clearRemoving(event.furniture().baseEntity().location.block.location)
                }
            }, 5L)
        }else{
            // Sequence System
            val furnitureId = Key.of(itemAdapterData.id.removePrefix("ce:"))
            val furniture = CraftEngineFurniture.byId(furnitureId)
            val anchor = furniture?.getAnyAnchorType() ?: AnchorType.GROUND

            val rotation = rotationMap.remove(loc)
            val cachedFrameRotation = itemFrameRotationMap[loc]

            if(isRemoving(loc.block.location)){
                if(!stageManager.activeSequences.contains(loc.block.location)){
                    clearRemoving(loc.block.location) }
                return
            }
            CraftEngineFurniture.place(loc,
                furnitureId,
                anchor,
                false)?.let { customFurniture ->

                val entity: Entity = customFurniture.baseEntity() ?: return

                if (rotation != null) entity.setRotation(rotation.first, rotation.second)
                if (cachedFrameRotation != null && entity is ItemFrame) {
                    entity.rotation = cachedFrameRotation
                }
            }
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
        if (event is CustomBlockInteractEvent) {
            //breakBlock(loc)
            CraftEngineBlocks.remove(event.bukkitBlock())
            return
        }
        if (event is FurnitureInteractEvent) {
            event.furniture().baseEntity()?.let { entity ->
                rotationMap[entity.location] = Pair(entity.location.yaw, entity.location.pitch)
            }
            setRemoving(event.furniture().baseEntity().location.block.location)

            removeStageData(event.furniture().baseEntity().location.block.location)
            CraftEngineFurniture.remove(event.furniture().baseEntity())
            return
        }

        // Sequence System
        val center = loc.clone().add(0.5, 0.5, 0.5)
        val nearby = loc.world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        for (entity in nearby) {
            if (!isPossibleFurnitureEntity(entity) || !entity.isValid || entity.isDead) {
                continue
            }
            if (entity.location.block != loc.block) { continue }

            val furniture = CraftEngineFurniture.getLoadedFurnitureByBaseEntity(entity)
            if (furniture != null && entity.isValid && !entity.isDead) {
                CraftEngineFurniture.remove(entity)

                return
            }
        }
        if (loc.block.type != org.bukkit.Material.AIR) {
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
            val block: Block = event.bukkitBlock()
            return Utils.calculateHashCode(
                block.location.hashCode(),
                block.hashCode(),
                block.type.hashCode(),
                block.blockData.hashCode(),
                block.state.hashCode()
            )
        }
        if (event is FurnitureInteractEvent) {
            val entity: Entity = event.furniture().baseEntity()
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
            return event.item()
        }
        return null
    }

    override fun getBlockFace(event: Event): BlockFace? {
        if (event is CustomBlockInteractEvent) {
            return event.clickedFace()
        }
        return null
    }

}