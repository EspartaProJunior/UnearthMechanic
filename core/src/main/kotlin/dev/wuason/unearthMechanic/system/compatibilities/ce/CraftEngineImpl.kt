package dev.wuason.unearthMechanic.system.compatibilities.ce

import dev.wuason.libs.adapter.AdapterComp
import dev.wuason.libs.adapter.AdapterData
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.UnearthMechanicPlugin
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.fishtank.FishTankBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.fishtank.FishTankDataStore
import dev.wuason.unearthMechanic.compatibilities.craftengine.types.FishType
import dev.wuason.unearthMechanic.config.IBlockStage
import dev.wuason.unearthMechanic.config.IFurnitureStage
import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.IStage
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.StageData
import dev.wuason.unearthMechanic.system.StageManager
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import dev.wuason.unearthMechanic.utils.Utils
import net.momirealms.craftengine.bukkit.api.BukkitAdaptors
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture
import net.momirealms.craftengine.bukkit.api.event.*
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.UpdateOption
import net.momirealms.craftengine.core.block.properties.Property
import net.momirealms.craftengine.core.block.properties.type.DoubleBlockHalf
import net.momirealms.craftengine.core.entity.furniture.AnchorType
import net.momirealms.craftengine.core.entity.player.InteractionHand
import net.momirealms.craftengine.core.util.Key
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.libraries.nbt.CompoundTag
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.*
import org.bukkit.block.data.type.Door
import org.bukkit.entity.*
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.*


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
                //val furniture = CraftEngineFurniture.getLoadedFurnitureByBaseEntity(entity)
                val furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity)
                if (furniture != null) {
                    return entity.uniqueId
                }
            } catch (e: Exception) {
                // If it throws an error, it is because that entity is not a valid piece of furniture.
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
                //Bukkit.getConsoleSender().sendMessage("[CE][isValidFurniture] MATCH por UUID")
                return true
            }

            if (expectedAdapterId != null && adapterId != null && adapterId.equals(expectedAdapterId.removePrefix("ce:"), ignoreCase = true)) {
                //Bukkit.getConsoleSender().sendMessage("[CE][isValidFurniture] MATCH por adapterId")
                return true
            }

            //Bukkit.getConsoleSender().sendMessage("[CE][isValidFurniture] mismatch: id=$adapterId")
        }

        //Bukkit.getConsoleSender().sendMessage("[CE][isValidFurniture] SIN MATCH en $keyLoc")
        return false
    }

    override fun isValid(loc: Location, expectedAdapterId: String?): Boolean {
        val world = loc.world ?: return false

        val nearby = world.getNearbyEntities(loc, 0.5, 1.0, 0.5)
        for (entity in nearby) {
            try {
                val furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity)
                if (furniture != null && entity.isValid && !entity.isDead) {
                    return true
                }
            } catch (_: Exception) {
                continue
            }
        }
        if (loc.block.type != Material.AIR) return true

        return false
    }

    @EventHandler
    fun onInteractBlock(event: CustomBlockInteractEvent) {
        if (event.hand() != InteractionHand.MAIN_HAND) return
        val adapterId = "ce:" + event.customBlock().id()
        //Bukkit.getConsoleSender().sendMessage("[DEBUG] onInteractBlock")

        stageManager.interact(event.player(),
            adapterId,
            event.location(),
            event,
            this)

        // FishTankBehavior
        onFishTankBehavior(event)
    }

    fun onFishTankBehavior(event: CustomBlockInteractEvent){
        //val log = Bukkit.getLogger()
        //log.info("[FishTank] event class=${event.javaClass.name}")
        //log.info("[FishTank] methods=" + event.javaClass.methods.joinToString(",") { it.name }.take(800))
        //log.info("[DBG] CE Interact item=${event.item()?.type} block=${event.customBlock().id()} loc=${event.location().blockX},${event.location().blockY},${event.location().blockZ}")
        try {
            val state = event.blockState()
            val ownerBlock = state.owner().value() ?: return

            val loc = event.location()
            val bw = loc.world

            val ceWorld = BukkitAdaptors.adapt(bw)
            val pos = BlockPos(loc.blockX, loc.blockY, loc.blockZ)

            val fishPropAny = ownerBlock.getProperty("fish") ?: return
            @Suppress("UNCHECKED_CAST")
            val fishProp = fishPropAny as Property<FishType>

            val currentFish = try { state.get(fishProp, FishType.none) } catch (_: Throwable) { return }

            val player = event.player
            val inv = player.inventory
            val hand = inv.itemInMainHand
            val mat = hand.type

            // tankKey
            val rootState = CraftEngineBlocks.getCustomBlockState(bw.getBlockAt(pos.x(), pos.y(), pos.z()).blockData)
            val expectedId = if (rootState != null) FishTankBehavior.ownerIdString(rootState) else "elitefantasy:aquarium_block"
            val tankKey = FishTankBehavior.resolveTankKey(bw, ceWorld, pos, expectedId)

            // REMOVE (empty bucket)
            if (mat == Material.BUCKET) {
                if (currentFish == FishType.none) return

                val out = FishTankBehavior.bucketToGive(bw, tankKey, pos, currentFish)

                val newState = state.with(fishProp, FishType.none)
                ceWorld.setBlockState(pos, newState, UpdateOption.UPDATE_ALL.flags())

                FishTankDataStore.removeCellSnapshot(tankKey, FishTankBehavior.packPos(pos))

                FishTankBehavior.ensureTaskRunning()
                FishTankBehavior.syncFishDisplay(ceWorld, pos, FishType.none, forceRescan = true)

                if (player.gameMode != GameMode.CREATIVE) {
                    // consume 1 empty bucket
                    if (hand.amount > 1) {
                        hand.amount -= 1
                    } else {
                        inv.setItemInMainHand(ItemStack(Material.AIR))
                    }

                    // give fish bucket (or drop)
                    val leftover = inv.addItem(out).values
                    leftover.forEach { player.world.dropItemNaturally(player.location, it) }
                }

                event.isCancelled = true
                return
            }

            // INSERT / SWAP (fish bucket)
            val fishFromBucket = FishTankBehavior.fishFromBucket(mat)
            if (fishFromBucket != null) {

                // snapshot of the bucket IN HAND
                val inSnapshot = hand.clone().also { it.amount = 1 }

                val out = if (currentFish == FishType.none) ItemStack(Material.BUCKET)
                else FishTankBehavior.bucketToGive(bw, tankKey,pos,currentFish)

                // set block
                FishTankBehavior.suppressPos(ceWorld, pos)
                val newState = state.with(fishProp, fishFromBucket)
                ceWorld.setBlockState(pos, newState, UpdateOption.UPDATE_ALL.flags())

                // sync display once
                /*FishTankBehavior.ensureTaskRunning()
                FishTankBehavior.syncFishDisplay(ceWorld, pos, fishFromBucket, inSnapshot)*/

                FishTankBehavior.ensureTaskRunning()
                FishTankBehavior.syncFishDisplay(ceWorld,
                    pos, fishFromBucket,
                    bucketSnapshot = inSnapshot, forceRescan = true)

                if (player.gameMode != GameMode.CREATIVE) {
                    // consumes 1 fish bucket
                    if (hand.amount > 1) {
                        hand.amount -= 1
                        // give out bucket
                        val leftover = inv.addItem(out).values
                        leftover.forEach { player.world.dropItemNaturally(player.location, it) }
                    } else {
                        // replace in hand
                        inv.setItemInMainHand(out)
                    }
                }

                event.isCancelled = true
                return
            }
        } catch (_: Throwable) {
        }
    }

    @EventHandler
    fun onInteractFurniture(event: FurnitureInteractEvent) {
        if (stageManager.isTransitioning(event.furniture().bukkitEntity.location.block.location)) {
            event.isCancelled = true
            return
        }

        val uuid = event.furniture().bukkitEntity.uniqueId

        if (event.furniture().bukkitEntity != null && event.furniture().bukkitEntity.uniqueId == uuid) {
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
        if (stageManager.isTransitioning(event.furniture().bukkitEntity.location.block.location)) {
            event.isCancelled = true
            return
        }

        val loc = event.furniture().bukkitEntity.location.block.location

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
        val anchor = furniture?.anyVariantName() ?: AnchorType.WALL.variantName()

        CraftEngineFurniture.place(location,
            furnitureId,
            anchor,
            false)
    }

    private fun breakBlock(location: Location?) {
        if (location != null) {
            val data = location.block.blockData
            lastBlockData[location.block.location.block.location] = data
            //Bukkit.getConsoleSender().sendMessage("💾 [DEBUG] Guardado BlockData en $location: $data")

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

    private fun createDoorProperties(door: Door): CompoundTag {
        return CompoundTag().apply {
            putString("facing", door.facing.name.lowercase())
            putString("half", if (door.half == Bisected.Half.BOTTOM) "lower" else "upper")
            putString("hinge", door.hinge.name.lowercase())
            putBoolean("open", door.isOpen)
            putBoolean("powered", door.isPowered)
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
        //Bukkit.getConsoleSender().sendMessage("💾 [DEBUG] handleBlockStage $loc:")
        //val data = loc.block.blockData
        //lastBlockData[loc.block.location.block.location] = data

        val state1 = loc.block.blockData //lastBlockData[loc]
        if(state1 != null){
            val previousBlockState : ImmutableBlockState? = CraftEngineBlocks.getCustomBlockState(state1);
            if (previousBlockState == null) {
                val block = loc.block
                if (CraftEngineBlocks.isCustomBlock(block)) return;
                val blockData = block.blockData
                if (blockData is Door) {
                    val customBlock = CraftEngineBlocks.byId(Key.of(itemAdapterData.id.removePrefix("ce:"))) ?: return
                    if (customBlock == null) return;
                    val otherHalf = when (blockData.half) {
                        Bisected.Half.BOTTOM -> block.getRelative(BlockFace.UP)
                        Bisected.Half.TOP -> block.getRelative(BlockFace.DOWN)
                    }
                    val relativeDoor = otherHalf.blockData as? Door ?: return
                    //Bukkit.getConsoleSender().sendMessage(" [DEBUG] previousBlockState == null && blockData is Door")

                    block.setType(Material.AIR, false)
                    otherHalf.setType(Material.AIR, false)

                    val properties1 = createDoorProperties(blockData)
                    val newState1 = customBlock.getBlockState(properties1)
                    CraftEngineBlocks.place(block.location, newState1, UpdateOption.UPDATE_NONE, false)

                    val properties2 = createDoorProperties(relativeDoor)
                    val newState2 = customBlock.getBlockState(properties2)
                    CraftEngineBlocks.place(otherHalf.location, newState2, UpdateOption.UPDATE_NONE, false)
                }else{
                    //Bukkit.getConsoleSender().sendMessage(" [DEBUG] previousBlockState == null && !(blockData is Door)")
                    CraftEngineBlocks.place(
                        loc,
                        Key.of(itemAdapterData.id.removePrefix("ce:")),
                        CompoundTag(),
                        false
                    )
                }

                return
            }

            var doubleBlockProperty : Property<*>? = null;
            for (property in previousBlockState.properties) {
                if (property.valueClass() == DoubleBlockHalf::class.java) {
                    doubleBlockProperty = property
                    break
                }
            }

            if (doubleBlockProperty != null) {
                //Bukkit.getConsoleSender().sendMessage(" [DEBUG] doubleBlockProperty != null")
                val half = previousBlockState.get(doubleBlockProperty) as DoubleBlockHalf;
                when(half) {
                    DoubleBlockHalf.UPPER -> {
                        val previousLowerState = ImmutableBlockState.with(previousBlockState, doubleBlockProperty, DoubleBlockHalf.LOWER)

                        val newUpperState = CraftEngineBlocks.byId(
                            Key.of(itemAdapterData.id.removePrefix("ce:"))
                        )?.getPossibleStates(previousBlockState.propertiesNbt())?.firstOrNull()
                        val newLowerState = CraftEngineBlocks.byId(
                            Key.of(itemAdapterData.id.removePrefix("ce:"))
                        )?.getPossibleStates(previousLowerState.propertiesNbt())?.firstOrNull()
                        val lowerLoc = Location(loc.world, loc.x, loc.y - 1, loc.z)

                        CraftEngineBlocks.remove(loc.block)
                        CraftEngineBlocks.remove(lowerLoc.block)

                        if (newUpperState == null || newLowerState == null) return;
                        BukkitAdaptors.adapt(loc.world).setBlockState(
                            loc.blockX, loc.blockY, loc.blockZ, newUpperState, 512)
                        BukkitAdaptors.adapt(lowerLoc.world).setBlockState(
                            lowerLoc.blockX, lowerLoc.blockY, lowerLoc.blockZ, newLowerState, UpdateOption.UPDATE_ALL.flags())
                    }
                    DoubleBlockHalf.LOWER -> {
                        val previousUpperState = ImmutableBlockState.with(previousBlockState, doubleBlockProperty, DoubleBlockHalf.UPPER)

                        val newUpperState = CraftEngineBlocks.byId(
                            Key.of(itemAdapterData.id.removePrefix("ce:"))
                        )?.getPossibleStates(previousUpperState.propertiesNbt())?.firstOrNull()
                        val newLowerState = CraftEngineBlocks.byId(
                            Key.of(itemAdapterData.id.removePrefix("ce:"))
                        )?.getPossibleStates(previousBlockState.propertiesNbt())?.firstOrNull()
                        val upperLoc = Location(loc.world, loc.x, loc.y + 1, loc.z)

                        if (newUpperState == null || newLowerState == null) return;
                        BukkitAdaptors.adapt(loc.world).setBlockState(loc.blockX, loc.blockY, loc.blockZ, newLowerState, 512)
                        BukkitAdaptors.adapt(loc.world).setBlockState(
                            upperLoc.blockX, upperLoc.blockY, upperLoc.blockZ, newUpperState, UpdateOption.UPDATE_ALL.flags())
                    }
                }
            }else{
                //Bukkit.getConsoleSender().sendMessage(" [DEBUG] doubleBlockProperty == null")
                val properties = previousBlockState.propertiesNbt()
                if(properties != null){
                    //CraftEngineBlocks.remove(loc.block)

                    val newBlockState = CraftEngineBlocks.byId(
                        Key.of(itemAdapterData.id.removePrefix("ce:"))
                    )?.getPossibleStates(properties)?.firstOrNull();
                    newBlockState?.let{ state ->
                        CraftEngineBlocks.place(loc, state, false
                        )
                    }
                }
            }
        }else{
            //Bukkit.getConsoleSender().sendMessage(" [DEBUG] state1 == null")
            CraftEngineBlocks.place(
                loc,
                Key.of(itemAdapterData.id.removePrefix("ce:")),
                CompoundTag(),
                false
            )
        }
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
            val entityEvent: Entity = event.furniture().bukkitEntity

            if (!entityEvent.isValid || entityEvent.isDead) return

            if(isRemoving(loc.block.location)){
                if(!stageManager.activeSequences.contains(loc.block.location.block.location)){
                    clearRemoving(loc.block.location) }
                return
            }

            if(isValid(loc, itemAdapterData.toString())){
                CraftEngineFurniture.remove(event.furniture().bukkitEntity)
                event.furniture().bukkitEntity.remove()
                breakBlock(event.furniture().bukkitEntity.location.block.location)
            }else{
                breakBlock(event.furniture().bukkitEntity.location.block.location)
            }

            // Spawn of the new furniture
            val furnitureId = Key.of(itemAdapterData.id.removePrefix("ce:"))
            val furniture = CraftEngineFurniture.byId(furnitureId)
            val anchor = furniture?.anyVariantName() ?: AnchorType.WALL.variantName()
            CraftEngineFurniture.place(loc,
                furnitureId,
                anchor,
                false)?.let { customFurniture ->

                val entity: Entity = customFurniture.bukkitEntity ?: return
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
                if(!stageManager.activeSequences.contains(event.furniture().bukkitEntity.location.block.location)){
                    clearRemoving(event.furniture().bukkitEntity.location.block.location)
                }
            }, 5L)
        }else{
            // Sequence System
            val furnitureId = Key.of(itemAdapterData.id.removePrefix("ce:"))
            val furniture = CraftEngineFurniture.byId(furnitureId)
            //val anchor = furniture?.getAnyAnchorType() ?: AnchorType.GROUND
            val anchor = furniture?.anyVariantName() ?: AnchorType.WALL.variantName()

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

                val entity: Entity = customFurniture.bukkitEntity ?: return

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
        //Bukkit.getConsoleSender().sendMessage("🚨 [DEBUG] Se llamó a handleRemove con loc=$loc y event=${event::class.simpleName}")

        if (event is CustomBlockInteractEvent) {
            breakBlock(loc)
            return
        }
        if (event is FurnitureInteractEvent) {
            event.furniture().bukkitEntity?.let { entity ->
                rotationMap[entity.location] = Pair(entity.location.yaw, entity.location.pitch)
            }
            setRemoving(event.furniture().bukkitEntity.location.block.location)

            removeStageData(event.furniture().bukkitEntity.location.block.location)
            CraftEngineFurniture.remove(event.furniture().bukkitEntity)
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

            val furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity)
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
            val entity: Entity = event.furniture().bukkitEntity
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