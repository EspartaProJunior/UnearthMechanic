package dev.wuason.unearthMechanic.system

import com.tcoded.folialib.wrapper.task.WrappedTask
import dev.wuason.adapter.Adapter
import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.compatibilities.luckperms.LuckPermsPlugin
import dev.wuason.unearthMechanic.compatibilities.worldguard.WorldGuardPlugin
import dev.wuason.unearthMechanic.config.*
import dev.wuason.unearthMechanic.events.ApplyStageEvent
import dev.wuason.unearthMechanic.events.FakePlayerInteractEvent
import dev.wuason.unearthMechanic.events.PreApplyStageEvent
import dev.wuason.unearthMechanic.system.animations.AnimationManager
import dev.wuason.unearthMechanic.system.animations.IAnimationManager
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import dev.wuason.unearthMechanic.system.compatibilities.MinecraftImpl
import dev.wuason.unearthMechanic.system.compatibilities.ce.CraftEngineImpl
import dev.wuason.unearthMechanic.system.compatibilities.crucible.MythicCrucibleImpl
import dev.wuason.unearthMechanic.system.compatibilities.ia.ItemsAdderImpl
import dev.wuason.unearthMechanic.system.compatibilities.nexo.NexoImpl
import dev.wuason.unearthMechanic.system.compatibilities.or.OraxenImpl
import dev.wuason.unearthMechanic.system.features.BasicFeatures
import dev.wuason.unearthMechanic.system.features.DurabilityFeature
import dev.wuason.unearthMechanic.system.features.Features
import dev.wuason.unearthMechanic.system.features.FoodFeature
import dev.wuason.unearthMechanic.system.features.SwingHandFeature
import dev.wuason.unearthMechanic.system.features.TintFurnitureFeature
import dev.wuason.unearthMechanic.system.features.ToolSoundFeature
import dev.wuason.unearthMechanic.utils.FoliaUtils
import dev.wuason.unearthMechanic.utils.Utils
import net.momirealms.antigrieflib.Flag
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Door
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.*
import kotlin.jvm.optionals.getOrNull


class StageManager(private val core: UnearthMechanic) : IStageManager, org.bukkit.event.Listener {

    companion object {
        init {
            Features.registerFeature(BasicFeatures())
            Features.registerFeature(DurabilityFeature())
            Features.registerFeature(ToolSoundFeature())
            Features.registerFeature(TintFurnitureFeature())
            Features.registerFeature(FoodFeature())
            Features.registerFeature(SwingHandFeature())
        }
    }

    private val debugTimedSequence = false
    private val debugStageManager = false

    private val compatibilitiesLoaded: MutableList<ICompatibility> = ArrayList()

    private val delays: HashMap<Location, WrappedTask> = HashMap()

    private val animator: AnimationManager = AnimationManager(core)

    public val activeSequences = mutableSetOf<Location>()
    private val startingSequences = mutableSetOf<Location>()
    private val consumingTimedSequences = mutableSetOf<Location>()

    private val activeSequenceUuids = mutableMapOf<Location, UUID?>()
    private val scheduledTasks = mutableMapOf<Location, MutableList<WrappedTask>>()

    private val lastInteractionProps = mutableMapOf<Location, Map<String, String>>()

    private val transitioningLocations = mutableMapOf<Location, Long>()

    fun isTransitioning(location: Location): Boolean {
        val keyLoc = sequenceKey(location)
        val until = transitioningLocations[keyLoc] ?: return false

        if (Bukkit.getCurrentTick() > until) {
            transitioningLocations.remove(keyLoc)
            return false
        }

        return true
    }

    fun addTransitioning(location: Location, ticks: Long = 8L) {
        val keyLoc = sequenceKey(location)
        val until = Bukkit.getCurrentTick() + ticks

        val currentUntil = transitioningLocations[keyLoc]
        if (currentUntil == null || until > currentUntil) {
            transitioningLocations[keyLoc] = until
        }
    }

    fun removeTransitioning(location: Location) {
        transitioningLocations.remove(sequenceKey(location))
    }

    init {

        compatibilitiesLoaded.add(MinecraftImpl("Vanilla", core, this, Adapter.getAdapterByName("Vanilla")))

        compCreator("Oraxen") { pluginName ->
            OraxenImpl(pluginName, core, this, Adapter.getAdapterByName(pluginName))
        }?.let { compatibilitiesLoaded.add(it) }

        compCreator("ItemsAdder") { pluginName ->
            ItemsAdderImpl(pluginName, core, this, Adapter.getAdapterByName(pluginName))
        }?.let { compatibilitiesLoaded.add(it) }

        compCreator("Nexo") { pluginName ->
            NexoImpl(pluginName, core, this, Adapter.getAdapterByName(pluginName))
        }?.let { compatibilitiesLoaded.add(it) }

        compCreator("CraftEngine") { pluginName ->
            CraftEngineImpl(pluginName, core, this, Adapter.getAdapterByName(pluginName))
        }?.let { compatibilitiesLoaded.add(it) }

        compCreator("MythicCrucible") { pluginName ->
            MythicCrucibleImpl(pluginName, core, this, Adapter.getAdapterByName(pluginName))
        }?.let { compatibilitiesLoaded.add(it) }

        compatibilitiesLoaded.forEach { compatibility ->
            Bukkit.getPluginManager().registerEvents(compatibility, core)
        }

        Bukkit.getPluginManager().registerEvents(this, core)

    }

    private fun getCurrentBlockProps(
        event: Event,
        loc: Location,
        compatibility: ICompatibility
    ): Map<String, String> {
        return try {
            compatibility.getCurrentBlockPropsFromEvent(event, loc)
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun dbgTimed(message: String) {
        if (debugTimedSequence) {
            core.logger.info("[UM-TIMED][tick=${Bukkit.getCurrentTick()}] $message")
        }
    }

    private fun dbgStage(message: String) {
        if (!debugStageManager) return
        core.logger.info("[UM-STAGE][tick=${Bukkit.getCurrentTick()}] $message")
    }

    private fun Location.debugShort(): String {
        return "${world?.name ?: "null"}:$blockX,$blockY,$blockZ"
    }

    private fun dbgSequenceState(prefix: String, loc: Location) {
        if (!debugTimedSequence) return

        val keyLoc = sequenceKey(loc)

        dbgTimed(
            "$prefix " +
                    "loc=${keyLoc.toShortString()} " +
                    "active=${activeSequences.contains(keyLoc)} " +
                    "starting=${startingSequences.contains(keyLoc)} " +
                    "transitioning=${isTransitioning(keyLoc)} " +
                    "timed=${activeTimedSequences.containsKey(keyLoc)} " +
                    "consuming=${consumingTimedSequences.contains(keyLoc)} " +
                    "uuid=${activeSequenceUuids[keyLoc]} " +
                    "lastStage=${lastSequenceStageByLoc[keyLoc]?.getAdapterData()} " +
                    "scheduled=${scheduledTasks[keyLoc]?.size ?: 0}"
        )
    }

    private fun findAllToolsAndSlots(player: Player, baseAdapterData: AdapterData): List<Pair<AdapterData, Int>> {
        val toolFirstSlot = mutableMapOf<AdapterData, Int>()

        val mainHandItem = animator.getAnimation(player)?.getItemMainHand() ?: player.inventory.itemInMainHand
        val mainHandData = Adapter.getAdapterData(Adapter.getAdapterId(mainHandItem)).getOrNull()
        if (mainHandData != null && core.getConfigManager().validTool(baseAdapterData, mainHandData)) {
            toolFirstSlot[mainHandData] = -1
        }

        val heldSlot = player.inventory.heldItemSlot
        for (slot in 0..35) {
            if (slot == heldSlot) continue
            val item = player.inventory.getItem(slot) ?: continue
            if (item.type.isAir) continue
            val data = Adapter.getAdapterData(Adapter.getAdapterId(item)).getOrNull() ?: continue
            if (!core.getConfigManager().validTool(baseAdapterData, data)) continue
            if (data !in toolFirstSlot) toolFirstSlot[data] = slot
        }

        return toolFirstSlot.entries.map { Pair(it.key, it.value) }
    }

    private fun resolveToolItemStack(player: Player, toolSlot: Int): ToolData {
        return if (toolSlot == -1) {
            ToolData(animator.getAnimation(player)?.getItemMainHand() ?: player.inventory.itemInMainHand,player.inventory.heldItemSlot)
        } else {
            ToolData(player.inventory.getItem(toolSlot) ?: player.inventory.itemInMainHand, toolSlot)
        }
    }

    fun interact(player: Player, baseItemId: String, location: Location, event: Event, compatibility: ICompatibility) {
        val normalizedLocation = canonicalStageTarget(compatibility, location.block)

        if (normalizedLocation != location.block.location) {
            interact(player, baseItemId, normalizedLocation, event, compatibility)
            return
        }

        dbgStage(
            "interact ENTER player=${player.name} baseItemId=$baseItemId " +
                    "loc=${location.debugShort()} event=${event.javaClass.simpleName} " +
                    "comp=${compatibility.adapterComp()?.type} handItem=${Adapter.getAdapterId(player.inventory.itemInMainHand)}"
        )

        if (event is PlayerInteractEvent) {
            if (event.hand != EquipmentSlot.HAND) {
                dbgStage("STOP interact ignored offhand hand=${event.hand} loc=${location.debugShort()}")
                return
            }
            if (event.action != Action.RIGHT_CLICK_BLOCK) {
                dbgStage("STOP interact ignored action=${event.action} loc=${location.debugShort()}")
                return
            }
        }
        //core.logger.info("[UM-DBG] interact ENTER baseItemId=$baseItemId loc=${location.blockX},${location.blockY},${location.blockZ} comp=${compatibility.adapterComp()?.type}")
        //if (player.isSneaking) return

        if (compatibility.isRemoving(location.block.location)) {
            dbgStage("STOP interact compatibility isRemoving loc=${location.debugShort()} comp=${compatibility.adapterComp()?.type}")
            return
        }

        val keyLoc = sequenceKey(location)

        if (isTransitioning(keyLoc)) {
            (event as? Cancellable)?.isCancelled = true
            dbgStage("STOP interact transitioning, event cancelled loc=${keyLoc.debugShort()}")
            return
        }

        if (activeTimedSequences.containsKey(keyLoc)) {
            dbgTimed(
                "interact sees active timed sequence " +
                        "player=${player.name} " +
                        "loc=${keyLoc.toShortString()} " +
                        "item=${Adapter.getAdapterId(player.inventory.itemInMainHand)} " +
                        "activeKeys=${activeTimedSequences.keys.map { it.toShortString() }}"
            )
        }

        if (tryHandleTimedSequenceInteract(player, keyLoc, event)) {
            dbgTimed(
                "interact consumed by timed sequence " +
                        "player=${player.name} " +
                        "loc=${keyLoc.toShortString()}"
            )
            dbgStage("STOP interact consumed by timed sequence loc=${keyLoc.debugShort()}")
            return
        }

        if (StageData.hasStageData(location)) {
            dbgStage("interact existing StageData loc=${keyLoc.debugShort()}")
            val stageData: StageData = StageData.fromLoc(location) ?: run {
                dbgStage("STOP StageData.hasStageData true but fromLoc null loc=${keyLoc.debugShort()}")
                return
            }
            val baseAdapterData = stageData.getGeneric().getBaseStage().getAdapterData() ?: run {
                dbgStage("STOP existing stage base adapter null generic=${stageData.getGeneric().getId()} loc=${keyLoc.debugShort()}")
                return
            }
            val handItem = animator.getAnimation(player)?.getItemMainHand()
                ?: player.inventory.itemInMainHand

            val handTool = Adapter.getAdapterData(Adapter.getAdapterId(handItem)).getOrNull()

            val allTools = handTool
                ?.takeIf { stageData.getGeneric().existsTool(it) }
                ?.let { listOf(Pair(it, -1)) }
                ?: emptyList()
            dbgStage(
                "existing StageData tools=${allTools.map { "${it.first.id}@${it.second}" }} " +
                        "generic=${stageData.getGeneric().getId()} stage=${stageData.getStage()} actual=${stageData.getActualAdapterData().id}"
            )


            /*val toolUsed: String = Adapter.getAdapterId(
                animator.getAnimation(player)?.getItemMainHand() ?: player.inventory.itemInMainHand
            )
            val toolAdapter = Adapter.getAdapterData(toolUsed).getOrNull() ?: run {
                //core.logger.info("[UM-DBG] INVALID toolAdapter (exist) from $toolUsed")
                return
            }*/
            val (toolUsed, toolSlot) = allTools.firstOrNull { stageData.getGeneric().existsTool(it.first) } ?: run {
                dbgStage("STOP existing stage no matching tool generic=${stageData.getGeneric().getId()} loc=${keyLoc.debugShort()}")
                return
            }

            interactExist(
                player,
                baseItemId,
                location,
                event,
                compatibility,
                stageData,
                toolUsed, toolSlot
            )
            return
        }

        val toolUsed: String = Adapter.getAdapterId(
            animator.getAnimation(player)?.getItemMainHand() ?: player.inventory.itemInMainHand
        )

        val parsedBase = (core.getConfigManager())
            .parseBlockStateId(baseItemId)

        val baseAdapter = Adapter.getAdapterData(parsedBase.cleanId).getOrNull() ?: run {
            //core.logger.info("[UM-DBG] INVALID baseAdapter from $baseItemId parsed=${parsedBase.cleanId}")
            dbgStage("STOP no baseAdapter baseItemId=$baseItemId parsed=${parsedBase.cleanId} loc=${keyLoc.debugShort()}")
            return
        }

        val toolAdapter = Adapter.getAdapterData(toolUsed).getOrNull() ?: run {
            //core.logger.info("[UM-DBG] INVALID toolAdapter from $toolUsed")
            dbgStage("STOP no toolAdapter toolUsed=$toolUsed loc=${keyLoc.debugShort()}")
            return
        }
        val mode = InteractionMode.fromSneaking(player.isSneaking)
        val props = getCurrentBlockProps(event, location, compatibility)

        val configManager = core.getConfigManager()

        val generic = configManager.getGeneric(baseAdapter, toolAdapter, mode, props)
        dbgStage(
            "new interaction resolved base=${baseAdapter.id} tool=${toolAdapter.id} " +
                    "mode=$mode props=$props generic=${generic?.getId() ?: "NULL"}"
        )
        //core.logger.info("[UM-DBG] generic result=${generic?.getId() ?: "NULL"}")

        if (generic != null) {
            if (StageData.hasStageData(location)) {
                dbgStage("STOP new interaction because StageData already exists loc=${keyLoc.debugShort()}")
                return
            }

            interactNotExist(
                player,
                baseAdapter,
                location,
                event,
                compatibility,
                toolAdapter,
                -1
            )
        } else {
            dbgStage("STOP no generic matched base=${baseAdapter.id} tool=${toolAdapter.id} mode=$mode props=$props loc=${keyLoc.debugShort()}")
        }
    }

    private fun interactExist(
        player: Player,
        itemId: String,
        location: Location,
        event: Event,
        compatibility: ICompatibility,
        stageData: StageData,
        toolUsed: AdapterData,
        toolSlot: Int
    ) {
        dbgStage(
            "interactExist ENTER loc=${location.debugShort()} generic=${stageData.getGeneric().getId()} " +
                    "stageIndex=${stageData.getStage()} tool=${toolUsed.id} actual=${stageData.getActualAdapterData().id}"
        )

        if (!stageData.getGeneric().existsTool(toolUsed)) {
            dbgStage("STOP interactExist tool not in generic tool=${toolUsed.id} generic=${stageData.getGeneric().getId()}")
            return
        }
        lastInteractionProps[location.block.location] = getCurrentBlockProps(event, location, compatibility)

        // Check if the INTERACTION MODE matches the player
        if (!stageData.getGeneric().getInteractionMode().matches(player.isSneaking)) {
            dbgStage(
                "STOP interactExist mode mismatch required=${stageData.getGeneric().getInteractionMode()} " +
                        "sneaking=${player.isSneaking}"
            )
            return
        }

        if (stageData.getActualAdapterData().adapter != compatibility.adapterComp()) {
            dbgStage(
                "STOP interactExist compatibility mismatch actual=${stageData.getActualAdapterData().adapter.type} " +
                        "current=${compatibility.adapterComp()?.type}"
            )
            return
        }

        if (canInteractExist(player, location, stageData, core)) {

            val iTool: ITool = stageData.getGeneric().getTool(toolUsed) ?: throw NullPointerException(
                "Tool not found for $toolUsed in ${
                    stageData.getGeneric().getId()
                } mabye is duplicated config"
            )

            /*val liveTool: LiveTool = LiveTool(
                if (animator.isAnimating(player)) animator.getAnimation(player)!!
                    .getItemMainHand() else player.inventory.itemInMainHand, iTool, player, this
            )*/
            val liveTool = LiveTool(resolveToolItemStack(player, toolSlot), iTool, player, this)

            if (stageData.getGeneric().getStages().size <= stageData.getStage()) {
                dbgStage(
                    "interactExist stage index out of range, resetting StageData " +
                            "size=${stageData.getGeneric().getStages().size} stage=${stageData.getStage()}"
                )
                StageData.removeStageData(location)
                interact(player, itemId, location, event, compatibility)
                return
            }

            stageData.getGeneric().getStages()[stageData.getStage()]?.let {
                val stage: Stage = it as Stage
                dbgStage(
                    "interactExist applying next stage adapter=${stage.getAdapterData()} " +
                            "remove=${stage.isRemove()} generic=${stageData.getGeneric().getId()}"
                )
                onPreApplyStage(
                    player,
                    compatibility,
                    event,
                    location,
                    liveTool,
                    stageData.getGeneric(),
                    stage,
                    stageData
                )
            }
        } else {
            dbgStage(
                "STOP interactExist canInteract false player=${player.name} loc=${location.debugShort()} " +
                        "generic=${stageData.getGeneric().getId()}"
            )
        }
    }

    fun canInteractExist(
        player: Player,
        location: Location,
        stageData: StageData,
        core: UnearthMechanic
    ): Boolean {
        return canUseUnearthInteraction(player, location, stageData.getGeneric())
    }

    private fun interactNotExist(
        player: Player,
        baseAdapterData: AdapterData,
        location: Location,
        event: Event,
        compatibility: ICompatibility,
        toolUsed: AdapterData,
        toolSlot: Int
    ): Boolean {
        //.logger.info("[UM-DBG] interactNotExist ENTER base=$baseAdapterData tool=$toolUsed loc=${location.blockX},${location.blockY},${location.blockZ}")
        //if (!core.getConfigManager().validTool(baseAdapterData, toolUsed)) return
        dbgStage("interactNotExist ENTER loc=${location.debugShort()} base=${baseAdapterData.id} tool=${toolUsed.id}")

        val mode = InteractionMode.fromSneaking(player.isSneaking)
        val currentProps = getCurrentBlockProps(event, location, compatibility)

        lastInteractionProps[location.block.location] = currentProps

        val configManager = core.getConfigManager()

        if (!configManager.validTool(baseAdapterData, toolUsed, mode, currentProps)) {
            dbgStage(
                "STOP interactNotExist validTool precheck false base=${baseAdapterData.id} tool=${toolUsed.id} " +
                        "mode=$mode props=$currentProps"
            )
            return false
        }

        val valid = configManager.validTool(baseAdapterData, toolUsed, mode, currentProps)
        //core.logger.info("[UM-DBG] validTool=$valid mode=$mode props=$currentProps")
        if (!valid) {
            //core.logger.info("[UM-DBG] STOP validTool false")
            dbgStage(
                "STOP interactNotExist validTool false base=${baseAdapterData.id} tool=${toolUsed.id} " +
                        "mode=$mode props=$currentProps"
            )
            return false
        }

        val generic: IGeneric = configManager
            .getGeneric(baseAdapterData, toolUsed, mode, currentProps)
            ?: run {
                dbgStage(
                    "STOP interactNotExist generic null base=${baseAdapterData.id} tool=${toolUsed.id} " +
                            "mode=$mode props=$currentProps"
                )
                return false
            }
        //core.logger.info("[UM-DBG] interactNotExist generic=${generic.getId()} stages=${generic.getStages().size}")

        //Bukkit.getConsoleSender().sendMessage("No existe StageData y es "+ generic.isNotProtect())

        val canInteract = canInteractNotExist(player, location, generic, core)
        //core.logger.info("[UM-DBG] canInteractNotExist=$canInteract noProtect=${generic.isNotProtect()} op=${player.isOp}")
        dbgStage(
            "interactNotExist generic=${generic.getId()} canInteract=$canInteract " +
                    "notProtect=${generic.isNotProtect()} op=${player.isOp}"
        )

        if (canInteract) {

            val iTool: ITool = generic.getTool(toolUsed)
                ?: throw NullPointerException("Tool not found for $toolUsed in ${generic.getId()} mabye is duplicated config")

            /*val liveTool: LiveTool = LiveTool(
                if (animator.isAnimating(player)) animator.getAnimation(player)!!
                    .getItemMainHand() else player.inventory.itemInMainHand, iTool, player, this
            )*/
            val liveTool = LiveTool(resolveToolItemStack(player, toolSlot), iTool, player, this)

            generic.getStages()[0]?.let {
                val stage: Stage = it as Stage
                dbgStage(
                    "interactNotExist applying first stage adapter=${stage.getAdapterData()} " +
                            "remove=${stage.isRemove()} generic=${generic.getId()}"
                )
                onPreApplyStage(player, compatibility, event, location, liveTool, generic, stage, null)
                return true
            }
        } else {
            dbgStage(
                "STOP interactNotExist canInteract false player=${player.name} loc=${location.debugShort()} " +
                        "generic=${generic.getId()}"
            )
        }

        return false
    }

    fun canInteractNotExist(
        player: Player,
        location: Location,
        generic: IGeneric,
        core: UnearthMechanic
    ): Boolean {
        return canUseUnearthInteraction(player, location, generic)
    }

    private fun onPreApplyStage(
        player: Player,
        compatibility: ICompatibility,
        event: Event,
        loc: Location,
        toolUsed: LiveTool,
        generic: IGeneric,
        stage: Stage,
        currentStageData: StageData? = null
    ) {
        // Region Condition
        if (!stage.matchesRegionConditions(loc)) {
            dbgStage(
                "STOP onPreApplyStage region conditions false loc=${loc.debugShort()} " +
                        "generic=${generic.getId()} stageAdapter=${stage.getAdapterData()}"
            )
            return
        }

        //send event
        val eventStage: PreApplyStageEvent =
            PreApplyStageEvent(player, compatibility, event, loc, toolUsed, generic, stage)
        Bukkit.getPluginManager().callEvent(eventStage)
        if (eventStage.isCancelled) {
            dbgStage(
                "STOP onPreApplyStage PreApplyStageEvent cancelled loc=${loc.debugShort()} " +
                        "generic=${generic.getId()} stageAdapter=${stage.getAdapterData()}"
            )
            return
        }

        dbgStage(
            "onPreApplyStage OK loc=${loc.debugShort()} generic=${generic.getId()} " +
                    "stageAdapter=${stage.getAdapterData()} remove=${stage.isRemove()} currentStageData=${currentStageData != null}"
        )

        //try multiple interact
        multipleInteract(compatibility, event, player, loc, toolUsed)

        if (!animator.isAnimating(player) && toolUsed.getITool().getAnimation() != null && toolUsed.getITool()
                .getAnimation()!!.getTicks() > 0
        ) {
            animator.playAnimation(player, toolUsed.getITool().getAnimation()!!)
        }


        Features.getFeatures().forEach { feature ->
            try {
                feature.onPreApply(player, compatibility, event, loc, toolUsed, stage, generic)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (stage.getMaxCorrectDelay(toolUsed) > 0) {
            if (loc in delays) {
                dbgStage(
                    "STOP onPreApplyStage delay already running loc=${loc.debugShort()} " +
                            "generic=${generic.getId()} stageAdapter=${stage.getAdapterData()}"
                )
                return
            }
            if (event is Cancellable) {
                event.isCancelled = true
            }
            dbgStage(
                "onPreApplyStage starting delay loc=${loc.debugShort()} " +
                        "delay=${stage.getMaxCorrectDelay(toolUsed)} generic=${generic.getId()} " +
                        "stageAdapter=${stage.getAdapterData()}"
            )
            val validation: Validation = Validation(player, compatibility, event, loc, toolUsed, generic, stage)
            validation.start()
            val delayTask: DelayTask =
                DelayTask(
                    this,
                    player,
                    compatibility,
                    event,
                    loc,
                    toolUsed,
                    generic,
                    stage,
                    validation,
                    currentStageData
                )
            delayTask.start()
            dbgStage(
                "onPreApplyStage delayTask.start called loc=${loc.debugShort()} " +
                        "delay=${stage.getMaxCorrectDelay(toolUsed)} inDelays=${loc in delays} " +
                        "generic=${generic.getId()} stageAdapter=${stage.getAdapterData()}"
            )
        } else {
            dbgStage(
                "onPreApplyStage applying immediately loc=${loc.debugShort()} " +
                        "generic=${generic.getId()} stageAdapter=${stage.getAdapterData()}"
            )
            onApplyStage(player, compatibility, event, loc, toolUsed, generic, stage, null, currentStageData)
        }

    }

    fun onProcessStage(
        tick: Long,
        player: Player,
        compatibility: ICompatibility,
        event: Event,
        loc: Location,
        toolUsed: LiveTool,
        generic: IGeneric,
        stage: Stage,
        validation: Validation,
        currentStageData: StageData? = null
    ) {
        dbgStage(
            "onProcessStage tick=$tick loc=${loc.debugShort()} generic=${generic.getId()} " +
                    "stageAdapter=${stage.getAdapterData()} maxDelay=${stage.getMaxCorrectDelay(toolUsed)} " +
                    "validationActive=true"
        )

        Features.getFeatures().forEach { feature ->
            try {
                feature.onProcess(tick, player, compatibility, event, loc, toolUsed, stage, generic)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (tick >= (stage.getMaxCorrectDelay(toolUsed) - 1)) {
            dbgStage(
                "onProcessStage threshold reached, calling onApplyStage " +
                        "tick=$tick loc=${loc.debugShort()} stageAdapter=${stage.getAdapterData()}"
            )
            onApplyStage(player, compatibility, event, loc, toolUsed, generic, stage, validation, currentStageData)
            return
        }
    }

    private fun executeStageCommands(player: Player, stage: IStage) {
        val commands = stage.getExecuteCommands()
        if (commands.isEmpty()) return

        commands.forEach { stageCommand ->
            try {
                val parsedCommand = stageCommand.getCommand()
                    .replace("{player}", player.name)

                if (parsedCommand.isBlank()) return@forEach

                if (stageCommand.isAsConsole()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsedCommand)
                } else {
                    player.performCommand(parsedCommand)
                }
            } catch (ex: Exception) {
                core.logger.warning("Error executing stage command '${stageCommand.getCommand()}': ${ex.message}")
            }
        }
    }

    private fun runStageExtras(
        player: Player,
        loc: Location,
        stage: Stage
    ) {
        stage.getSounds().forEach { sound ->
            val task = Runnable {
                loc.world?.playSound(
                    loc.clone().add(0.5, 0.5, 0.5),
                    sound.soundId,
                    sound.volume,
                    sound.pitch
                )
            }

            if (sound.delay > 0) {
                runLaterAtLocation(loc, sound.delay) {
                    task.run()
                }
            } else {
                FoliaUtils.runAtLocation(loc) {
                    task.run()
                }
            }
        }
    }

    private fun getFurnitureUUIDSafe(
        compatibility: ICompatibility,
        loc: Location,
        expectedAdapterId: String?
    ): UUID? {
        val keyLoc = sequenceKey(loc)

        return if (!expectedAdapterId.isNullOrBlank()) {
            compatibility.getFurnitureUUID(keyLoc, expectedAdapterId)
        } else {
            compatibility.getFurnitureUUID(keyLoc)
        }
    }

    private fun onApplyStage(
        player: Player,
        compatibility: ICompatibility,
        event: Event,
        loc: Location,
        toolUsed: LiveTool,
        generic: IGeneric,
        stage: Stage,
        validation: Validation? = null,
        currentStageData: StageData? = null
    ) {
        dbgStage(
            "onApplyStage ENTER loc=${loc.debugShort()} generic=${generic.getId()} " +
                    "rawStage=${stage.getAdapterData()} validation=${validation != null} " +
                    "currentStageData=${currentStageData != null} tool=${toolUsed.getITool().getAdapterData()}"
        )
        //Bukkit.getConsoleSender().sendMessage("secso Tool "+toolUsed.getITool().getToolPermission().toString())
        toolUsed.getITool()?.getToolPermission()?.let { permission ->
            if (permission.isNotBlank()) {
                val hasPermLuckPerms = if (LuckPermsPlugin.isLuckPermsEnabled()) {
                    core.getLuckPermsComb().hasPermission(player, permission)
                } else {
                    player.hasPermission(permission) || player.isOp
                }
                //Bukkit.getConsoleSender().sendMessage("The player has the $permission permission and is $hasPermLuckPerms of Tool.)
                if (!hasPermLuckPerms) {
                    dbgStage("STOP onApplyStage tool permission denied permission=$permission player=${player.name}")
                    return
                }
            }
        }
        //Bukkit.getConsoleSender().sendMessage("secso Stage "+stage.getPermissionStage())
        stage.getPermissionStage()?.let { permission ->
            if (permission.isNotBlank()) {
                val hasPermLuckPerms = if (LuckPermsPlugin.isLuckPermsEnabled()) {
                    core.getLuckPermsComb().hasPermission(player, permission)
                } else {
                    player.hasPermission(permission) || player.isOp
                }
                //Bukkit.getConsoleSender().sendMessage("The player has the $permission permission and is $hasPermLuckPerms from Stage.")
                if (!hasPermLuckPerms) {
                    dbgStage("STOP onApplyStage stage permission denied permission=$permission player=${player.name}")
                    return
                }
            }
        }
        val keyLoc = sequenceKey(loc)

        if (activeSequences.contains(keyLoc) || startingSequences.contains(keyLoc)) {
            if (event is Cancellable) {
                event.isCancelled = true
            }

            dbgTimed(
                "onApplyStage blocked because sequence is already active/starting " +
                        "loc=${keyLoc.toShortString()} " +
                        "active=${activeSequences.contains(keyLoc)} " +
                        "starting=${startingSequences.contains(keyLoc)}"
            )
            dbgStage(
                "STOP onApplyStage active/starting loc=${keyLoc.debugShort()} " +
                        "active=${activeSequences.contains(keyLoc)} starting=${startingSequences.contains(keyLoc)}"
            )

            return
        }

        if (stage.getReduceItemHand() > 0 && player.gameMode != GameMode.CREATIVE) {
            val mainHand = toolUsed.getItemMainHand()
            if (mainHand == null || mainHand.type.isAir || mainHand.amount < stage.getReduceItemHand()) {
                dbgStage(
                    "STOP onApplyStage reduceItemHand failed required=${stage.getReduceItemHand()} " +
                            "mainHand=${mainHand?.type} amount=${mainHand?.amount}"
                )
                return
            }
        }

        if (stage.getReduceItemInventory() > 0 && player.gameMode != GameMode.CREATIVE) {
            val toolAdapterData = toolUsed.getITool().getAdapterData()
            val count = (0..35).sumOf { slot ->
                val item = player.inventory.getItem(slot) ?: return@sumOf 0
                if (item.type.isAir) return@sumOf 0
                val data = Adapter.getAdapterData(Adapter.getAdapterId(item)).getOrNull() ?: return@sumOf 0
                if (data == toolAdapterData) item.amount else 0
            }
            if (count < stage.getReduceItemInventory()) {
                dbgStage(
                    "STOP onApplyStage reduceItemInventory failed required=${stage.getReduceItemInventory()} " +
                            "count=$count tool=${toolAdapterData}"
                )
                return
            }
        }

        if (validation != null && !validation.validate()) {
            dbgStage("STOP onApplyStage validation failed loc=${keyLoc.debugShort()} stage=${stage.getAdapterData()}")
            return
        }

        if (!toolUsed.isOriginalItem()) {
            dbgStage(
                "STOP onApplyStage tool is not original item loc=${keyLoc.debugShort()} " +
                        "tool=${toolUsed.getITool().getAdapterData()}"
            )
            return
        }

        if (!toolUsed.isValid()) {
            dbgStage(
                "STOP onApplyStage tool is not valid loc=${keyLoc.debugShort()} " +
                        "tool=${toolUsed.getITool().getAdapterData()}"
            )
            return
        }

        if (!StageData.compare(StageData(loc, stage.getStage(), generic), loc)) {
            dbgStage(
                "STOP onApplyStage StageData.compare false loc=${keyLoc.debugShort()} " +
                        "stageIndex=${stage.getStage()} generic=${generic.getId()}"
            )
            return
        }

        if (stage.shouldRememberPrevious()) {
            val currentAdapter = currentStageData?.getActualAdapterData()
                ?: generic.getBaseStage().getAdapterData()

            currentAdapter?.let {
                val currentStage = if (currentStageData != null) {
                    generic.getStagesAdapterData()[it] as? Stage
                } else {
                    generic.getBaseStage() as? Stage
                }

                val realCurrentProps = lastInteractionProps[loc.block.location]
                    ?: getCurrentBlockProps(event, loc, compatibility)

                val configProps = currentStage?.getExplicitBlockProperties() ?: emptyMap()

                PreviousBlockMemory.save(
                    loc,
                    it,
                    realCurrentProps + configProps
                )
            }
        }

        val resolvedStage = if (stage.shouldUsePrevious()) {
            val previous = PreviousBlockMemory.get(loc)
            val fallback = stage.getFallbackAdapterData()

            val targetAdapter = previous?.adapterData ?: fallback ?: run {
                dbgStage("STOP onApplyStage usePrevious with no previous/fallback loc=${keyLoc.debugShort()}")
                return
            }

            val result = stage.resolveStage().copyWithAdapterData(targetAdapter)

            if (previous != null) {
                result.setExplicitBlockProperties(previous.props)
                PreviousBlockMemory.remove(loc)
            } else {
                result.setExplicitBlockProperties(stage.getFallbackProperties())
            }

            result
        } else {
            stage.resolveStage()
        }

        if (!stage.shouldUsePrevious()) {
            val inheritedProps = lastInteractionProps[loc.block.location]
                ?: getCurrentBlockProps(event, loc, compatibility)
            val explicitProps = resolvedStage.getExplicitBlockProperties()

            if (inheritedProps.isNotEmpty()) {
                resolvedStage.setExplicitBlockProperties(inheritedProps + explicitProps)
            }

            /*core.logger.info(
                "[UM-DBG] APPLY props inherited=$inheritedProps explicit=$explicitProps final=${resolvedStage.getExplicitBlockProperties()}"
            )*/
        }

        /*Bukkit.getConsoleSender().sendMessage(
            "[UM][SM] onApplyStage rawStageId=${stage.getAdapterData()?.id} resolvedStageId=${resolvedStage.getAdapterData()?.id} loc=$loc"
        )*/

        val willStartSequence = resolvedStage.getSequenceStages()?.isNotEmpty() == true

        val applyStageEvent: ApplyStageEvent =
            ApplyStageEvent(player, compatibility, event, loc, toolUsed, generic, resolvedStage)
        Bukkit.getPluginManager().callEvent(applyStageEvent)

        if (applyStageEvent.isCancelled) {
            startingSequences.remove(keyLoc)
            dbgStage(
                "STOP onApplyStage ApplyStageEvent cancelled loc=${keyLoc.debugShort()} " +
                        "resolved=${resolvedStage.getAdapterData()} generic=${generic.getId()}"
            )
            return
        }

        if (willStartSequence) {
            if (!startingSequences.add(keyLoc)) {
                if (event is Cancellable) {
                    event.isCancelled = true
                }

                dbgTimed(
                    "onApplyStage blocked duplicate sequence start " +
                            "loc=${keyLoc.toShortString()} " +
                            "stage=${resolvedStage.getAdapterData()}"
                )
                dbgStage(
                    "STOP onApplyStage duplicate sequence start loc=${keyLoc.debugShort()} " +
                            "resolved=${resolvedStage.getAdapterData()}"
                )

                return
            }
        }

        if (validation == null) {
            if (event is Cancellable) {
                event.isCancelled = true
            }
        }

        Features.getFeatures().forEach { feature ->
            try {
                feature.onApply(player, compatibility, event, loc, toolUsed, resolvedStage, generic)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        runStageExtras(player, loc, resolvedStage)
        executeStageCommands(player, resolvedStage)
        //core.logger.info("[UM-DBG] APPLY stageAdapter=${resolvedStage.getAdapterData()}")
        resolvedStage.getAdapterData()?.let {
            if (isSimilarCompatibility(it, compatibility)) {
                dbgStage(
                    "onApplyStage handle same compatibility loc=${keyLoc.debugShort()} " +
                            "target=${it.id} comp=${compatibility.adapterComp()?.type} " +
                            "resolved=${resolvedStage.getAdapterData()}"
                )
                //Bukkit.getConsoleSender().sendMessage("[UM] handleStage aplicado para $furnitureUuid en $currentTick")
                //Bukkit.getConsoleSender().sendMessage("[UM] handleStage aplicado para ${stage.getAdapterData()?.adapter?.type}:${stage.getAdapterData()?.id} en ${Bukkit.getCurrentTick()}")
                val currentAdapterData = currentStageData?.getActualAdapterData()
                    ?: generic.getBaseStage().getAdapterData()

                val currentAdapterId = currentAdapterData?.id
                val currentExpectedStage = currentAdapterData?.let { adapter ->
                    generic.getStagesAdapterData()[adapter] as? Stage
                } ?: generic.getBaseStage()

                if (
                    currentExpectedStage is IFurnitureStage &&
                    resolvedStage is IFurnitureStage &&
                    currentAdapterId != null &&
                    isCurrentObjectValid(compatibility, loc, generic, currentStageData)
                ) {
                    val oldUuid = getFurnitureUUIDSafe(
                        compatibility = compatibility,
                        loc = loc.block.location,
                        expectedAdapterId = currentAdapterId
                    )

                    if (oldUuid != null) {
                        val newUuid = compatibility.placeNewFurnitureThenRemoveOld(
                            loc = loc.block.location,
                            currentAdapterId = currentAdapterId,
                            targetAdapterId = it.id,
                            oldUuid = oldUuid
                        )

                        if (newUuid != null) {
                            lockTransition(loc.block.location, 3L)

                            if (resolvedStage.getSequenceStages()?.isNotEmpty() == true) {
                                activeSequenceUuids[loc.block.location] = newUuid

                                runLaterAtLocation(loc, 2L) {
                                    handleSequence(player, compatibility, loc, toolUsed, generic, resolvedStage)
                                }
                            }

                            // We skip handleStage because we've already set up the new furniture
                            return@let
                        }
                    }
                }

                if (!isCurrentObjectValid(compatibility, loc, generic, currentStageData)) {
                    dbgStage(
                        "onApplyStage current object invalid before handleStage, removing loc=${keyLoc.debugShort()} " +
                                "target=${it.id}"
                    )
                    compatibility.handleRemove(player, event, loc, toolUsed, generic, resolvedStage)
                }

                if (resolvedStage is IFurnitureStage) {
                    lockTransition(loc.block.location, 6L)
                }
                dbgStage(
                    "onApplyStage CALL handleStage loc=${keyLoc.debugShort()} " +
                            "target=${it.id} comp=${compatibility.adapterComp()?.type}"
                )
                compatibility.handleStage(player, it, event, loc, toolUsed, generic, resolvedStage)
                dbgStage(
                    "onApplyStage RETURN handleStage loc=${keyLoc.debugShort()} " +
                            "target=${it.id} comp=${compatibility.adapterComp()?.type}"
                )

                if (resolvedStage.getSequenceStages()?.isNotEmpty() == true) {
                    if (resolvedStage is IFurnitureStage) {
                        runLaterAtLocation(loc, 3L) {
                            compatibility.getFurnitureUUID(loc.block.location)?.let { uuid ->
                                activeSequenceUuids[loc.block.location] = uuid
                            }
                            handleSequence(player, compatibility, loc, toolUsed, generic, resolvedStage)
                        }
                    } else {
                        compatibility.getFurnitureUUID(loc.block.location)?.let { uuid ->
                            activeSequenceUuids[loc.block.location] = uuid
                        }
                        handleSequence(player, compatibility, loc, toolUsed, generic, resolvedStage)
                    }
                }
            } else {
                val c: ICompatibility =
                    getCompatibilityByAdapterId(it) ?: throw NullPointerException("Compatibility not found for $it")
                dbgStage(
                    "onApplyStage cross compatibility loc=${keyLoc.debugShort()} " +
                            "from=${compatibility.adapterComp()?.type} to=${c.adapterComp()?.type} target=${it.id}"
                )

                // Cross-compatibility replace is forced on purpose.
                // We do not validate with target compatibility because the current object
                // belongs to another compatibility system.
                val removedBySourceCompatibility =
                    if (compatibility != c) {
                        compatibility.handleCrossCompatibilityRemoveBeforeTarget(
                            player = player,
                            event = event,
                            loc = loc,
                            toolUsed = toolUsed,
                            generic = generic,
                            stage = resolvedStage,
                            targetCompatibility = c
                        )
                    } else {
                        false
                    }
                // remove the current compatibility
                if (!removedBySourceCompatibility) {
                    if (c is MinecraftImpl && compatibility.adapterComp().type.equals("ItemsAdder", ignoreCase = true)) {
                        compatibility.handleRemove(player, event, loc, toolUsed, generic, resolvedStage)
                    } else {
                        c.handleRemove(player, event, loc, toolUsed, generic, resolvedStage)
                    }
                }

                // place with destination compatibility
                if (resolvedStage is IFurnitureStage) {
                    lockTransition(loc.block.location, 6L)
                }
                dbgStage(
                    "onApplyStage CALL cross handleStage loc=${keyLoc.debugShort()} " +
                            "target=${it.id} comp=${c.adapterComp()?.type}"
                )
                c.handleStage(player, it, event, loc, toolUsed, generic, resolvedStage)
                dbgStage(
                    "onApplyStage RETURN cross handleStage loc=${keyLoc.debugShort()} " +
                            "target=${it.id} comp=${c.adapterComp()?.type}"
                )

                if (resolvedStage.getSequenceStages()?.isNotEmpty() == true) {
                    if (resolvedStage is IFurnitureStage) {
                        runLaterAtLocation(loc, 3L) {
                            compatibility.getFurnitureUUID(loc.block.location)?.let { uuid ->
                                activeSequenceUuids[loc.block.location] = uuid
                            }
                            handleSequence(player, compatibility, loc, toolUsed, generic, resolvedStage)
                        }
                    } else {
                        c.getFurnitureUUID(loc.block.location)?.let { uuid ->
                            activeSequenceUuids[loc.block.location] = uuid
                        }
                        handleSequence(player, c, loc, toolUsed, generic, resolvedStage)
                    }
                }
            }
        }
        lastInteractionProps.remove(loc.block.location)

        if (resolvedStage.isRemove() || generic.isLastStage(resolvedStage)) {
            if (resolvedStage.isRemove()) compatibility.handleRemove(
                player,
                event,
                loc,
                toolUsed,
                generic,
                resolvedStage
            )
            StageData.removeStageData(loc)
            dbgStage(
                "onApplyStage done and removed StageData loc=${keyLoc.debugShort()} " +
                        "remove=${resolvedStage.isRemove()} last=${generic.isLastStage(resolvedStage)}"
            )
            return
        }

        StageData.saveStageData(loc, StageData(loc, resolvedStage.getStage() + 1, generic))
        dbgStage(
            "onApplyStage saved StageData loc=${keyLoc.debugShort()} " +
                    "nextStage=${resolvedStage.getStage() + 1} generic=${generic.getId()}"
        )
    }

    private val lastSequenceStageByLoc = mutableMapOf<Location, IStage>()

    private data class ActiveTimedSequence(
        val compatibility: ICompatibility,
        val generic: IGeneric,
        val originalToolUsed: LiveTool,
        val readyStage: Stage,
        val timedInteraction: TimedSequenceInteraction,
        val openTick: Int,
        val closeTick: Int
    )

    private val activeTimedSequences = mutableMapOf<Location, ActiveTimedSequence>()

    private fun isCurrentObjectValid(
        compatibility: ICompatibility,
        loc: Location,
        generic: IGeneric,
        currentStageData: StageData?
    ): Boolean {
        val currentAdapterData = if (currentStageData != null) {
            currentStageData.getActualAdapterData()
        } else {
            generic.getBaseStage().getAdapterData()
        } ?: return false

        val expectedId = currentAdapterData.id
        val expectedStage = generic.getStagesAdapterData()[currentAdapterData]

        return when (expectedStage) {
            is IFurnitureStage -> compatibility.isValidFurniture(loc, expectedId)
            is IBlockStage -> compatibility.isValidBlock(loc, expectedId)
            else -> compatibility.isValid(loc, expectedId)
        }
    }

    private fun sequenceKey(loc: Location): Location {
        return loc.block.location
    }

    private fun Location.toShortString(): String {
        return "${world?.name}:${blockX},${blockY},${blockZ}"
    }

    private fun runLaterAtLocation(
        loc: Location,
        delayTicks: Long,
        block: () -> Unit
    ): WrappedTask {
        val keyLoc = sequenceKey(loc)

        return FoliaUtils.runLater(delayTicks) {
            FoliaUtils.runAtLocation(keyLoc) {
                block()
            }
        }
    }

    private fun transitionArea(loc: Location): List<Location> {
        val base = sequenceKey(loc)
        val world = base.world ?: return listOf(base)

        val result = ArrayList<Location>()

        for (x in -1..1) {
            for (y in -1..2) {
                for (z in -1..1) {
                    result.add(
                        Location(
                            world,
                            base.blockX + x.toDouble(),
                            base.blockY + y.toDouble(),
                            base.blockZ + z.toDouble()
                        )
                    )
                }
            }
        }

        return result
    }

    private fun lockTransition(loc: Location, ticks: Long = 8L) {
        transitionArea(loc).forEach { transitionLoc ->
            addTransitioning(transitionLoc, ticks)
        }

        runLaterAtLocation(loc, ticks + 1L) {
            transitionArea(loc).forEach { transitionLoc ->
                val keyLoc = sequenceKey(transitionLoc)
                val until = transitioningLocations[keyLoc] ?: return@forEach

                if (Bukkit.getCurrentTick() >= until) {
                    transitioningLocations.remove(keyLoc)
                }
            }
        }
    }

    private fun finishSequenceStepIfNeeded(
        delayTicks: Long,
        sequenceStages: Map<Long, IStage>,
        stage: Stage,
        generic: IGeneric,
        keyLoc: Location,
        compatibility: ICompatibility
    ) {
        if (delayTicks != sequenceStages.keys.maxOrNull()) return

        dbgTimed(
            "sequence FINISH " +
                    "loc=${keyLoc.toShortString()} " +
                    "delay=$delayTicks " +
                    "lastDelay=${sequenceStages.keys.maxOrNull()} " +
                    "lastStage=${lastSequenceStageByLoc[keyLoc]?.getAdapterData()} " +
                    "hasTimed=${activeTimedSequences.containsKey(keyLoc)}"
        )

        dbgSequenceState("sequence finish before cleanup", keyLoc)

        activeSequences.remove(keyLoc)
        startingSequences.remove(keyLoc)
        consumingTimedSequences.remove(keyLoc)

        activeSequenceUuids.remove(keyLoc)
        scheduledTasks.remove(keyLoc)
        lastSequenceStageByLoc.remove(keyLoc)
        activeTimedSequences.remove(keyLoc)

        val nextStage = stage.getStage() + 1
        if (nextStage < generic.getStages().size) {
            dbgTimed(
                "sequence cleanup done " +
                        "loc=${keyLoc.toShortString()} " +
                        "nextStage=$nextStage " +
                        "genericStages=${generic.getStages().size}"
            )

            StageData.saveStageData(keyLoc, StageData(keyLoc, nextStage, generic))
            compatibility.clearRemoving(keyLoc)
        } else {
            StageData.removeStageData(keyLoc)
            compatibility.clearRemoving(keyLoc)
        }
    }

    private fun scheduleFurnitureApplyWhenCleared(
        player: Player,
        compatibility: ICompatibility,
        fakeEvent: Event,
        keyLoc: Location,
        toolUsed: LiveTool,
        generic: IGeneric,
        resolvedSequenceStage: Stage,
        oldUuid: UUID?,
        delayTicks: Long,
        sequenceStages: Map<Long, IStage>,
        stage: Stage,
        attemptsLeft: Int = 5
    ) {
        val task = runLaterAtLocation(keyLoc, 1L) {
            if (!activeSequences.contains(keyLoc)) return@runLaterAtLocation

            val currentUuid = compatibility.getFurnitureUUID(keyLoc)

            dbgTimed(
                "sequence furniture transition WAIT CLEAR " +
                        "loc=${keyLoc.toShortString()} " +
                        "oldUuid=$oldUuid " +
                        "currentUuid=$currentUuid " +
                        "attemptsLeft=$attemptsLeft " +
                        "nextStage=${resolvedSequenceStage.getAdapterData()}"
            )

            if (oldUuid != null && currentUuid == oldUuid && attemptsLeft > 0) {
                lockTransition(keyLoc, 8L)

                scheduleFurnitureApplyWhenCleared(
                    player,
                    compatibility,
                    fakeEvent,
                    keyLoc,
                    toolUsed,
                    generic,
                    resolvedSequenceStage,
                    oldUuid,
                    delayTicks,
                    sequenceStages,
                    stage,
                    attemptsLeft - 1
                )
                return@runLaterAtLocation
            }

            if (oldUuid != null && currentUuid == oldUuid) {
                dbgTimed(
                    "sequence furniture transition FORCE CLEAN old furniture still registered " +
                            "loc=${keyLoc.toShortString()} " +
                            "oldUuid=$oldUuid"
                )

                compatibility.removeFurnitureByUUID(keyLoc, oldUuid)
            }

            dbgTimed(
                "sequence furniture transition APPLY PHASE " +
                        "loc=${keyLoc.toShortString()} " +
                        "currentBeforeApply=${compatibility.getFurnitureUUID(keyLoc)} " +
                        "nextStage=${resolvedSequenceStage.getAdapterData()}"
            )

            applySequenceStep(
                player,
                compatibility,
                fakeEvent,
                keyLoc,
                toolUsed,
                generic,
                resolvedSequenceStage
            )

            finishSequenceStepIfNeeded(
                delayTicks,
                sequenceStages,
                stage,
                generic,
                keyLoc,
                compatibility
            )
        }

        scheduledTasks.getOrPut(keyLoc) { mutableListOf() }.add(task)
    }

    private fun handleSequence(
        player: Player,
        compatibility: ICompatibility,
        loc: Location,
        toolUsed: LiveTool,
        generic: IGeneric,
        stage: Stage
    ) {
        val keyLoc = sequenceKey(loc)
        val sequenceStages = stage.getSequenceStages()!!

        dbgTimed(
            "handleSequence ENTER " +
                    "player=${player.name} " +
                    "loc=${keyLoc.toShortString()} " +
                    "comp=${compatibility.adapterComp().type} " +
                    "generic=${generic.getId()} " +
                    "stage=${stage.getStage()} " +
                    "stageAdapter=${stage.getAdapterData()} " +
                    "sequenceSteps=${sequenceStages.keys.sorted()}"
        )

        dbgSequenceState("handleSequence before add", keyLoc)

        if (!activeSequences.add(keyLoc)) {
            startingSequences.remove(keyLoc)

            dbgTimed(
                "handleSequence blocked duplicate start " +
                        "loc=${keyLoc.toShortString()} " +
                        "stage=${stage.getAdapterData()}"
            )

            return
        }

        startingSequences.remove(keyLoc)

        /*Bukkit.getConsoleSender().sendMessage(
            "[UM][SM] handleSequence start loc=$loc stageId=${stage.getAdapterData()?.id} uuid=${activeSequenceUuids[loc]} currentUuid=${compatibility.getFurnitureUUID(loc)}"
        )*/

        //Bukkit.getConsoleSender().sendMessage("[UM] Stage ${stage.getStage()} tiene ${sequenceStages.size} pasos de sequence.")

        val tasks = mutableListOf<WrappedTask>()

        val expectedId = stage.getAdapterData()?.id

        getFurnitureUUIDSafe(
            compatibility = compatibility,
            loc = keyLoc,
            expectedAdapterId = expectedId
        )?.let { uuid ->
            activeSequenceUuids[keyLoc] = uuid
        }
        dbgTimed(
            "handleSequence uuid captured " +
                    "loc=${keyLoc.toShortString()} " +
                    "uuid=${activeSequenceUuids[keyLoc]} " +
                    "currentFurnitureUuid=${compatibility.getFurnitureUUID(keyLoc)}"
        )

        lastSequenceStageByLoc[keyLoc] = stage

        sequenceStages.forEach { (delayTicks, sequenceStage) ->

            val previewResolvedStage = sequenceStage.resolveStage()
            val previewHasObjectChange =
                previewResolvedStage.getAdapterData() != null || previewResolvedStage.isRemove()

            val previewIsFurnitureTransition =
                previewHasObjectChange && (stage is IFurnitureStage || previewResolvedStage is IFurnitureStage)

            if (previewIsFurnitureTransition) {
                val preLockDelay = (delayTicks - 1L).coerceAtLeast(0L)

                val preLockTask = runLaterAtLocation(keyLoc, preLockDelay) {
                    if (!activeSequences.contains(keyLoc)) return@runLaterAtLocation

                    lockTransition(keyLoc, 8L)

                    dbgTimed(
                        "sequence PRE-LOCK transition " +
                                "loc=${keyLoc.toShortString()} " +
                                "delay=$delayTicks " +
                                "preLockDelay=$preLockDelay " +
                                "nextStage=${previewResolvedStage.getAdapterData()}"
                    )
                }

                tasks.add(preLockTask)
            }

            val task = runLaterAtLocation(keyLoc, delayTicks) {
                if (!activeSequences.contains(keyLoc)) return@runLaterAtLocation
                dbgTimed(
                    "sequence task ENTER " +
                            "loc=${keyLoc.toShortString()} " +
                            "delay=$delayTicks " +
                            "sequenceStage=${sequenceStage.getAdapterData()} " +
                            "remove=${sequenceStage.isRemove()}"
                )

                dbgSequenceState("sequence task state", keyLoc)

                //val expectedUuid = compatibility.getFurnitureUUID(loc) //activeSequenceUuids[loc]

                /*Bukkit.getConsoleSender().sendMessage("[UM] sawebada "
                        +compatibility.isValid(loc,stage.getAdapterData()?.id))
                Bukkit.getConsoleSender().sendMessage("[UM] ñññ "
                        +compatibility.isValidUUID(loc,stage.getAdapterData()?.id,activeSequenceUuids[loc])
                )*/
                val fakeEvent = FakePlayerInteractEvent(
                    player,
                    keyLoc.block,
                    player.inventory.itemInMainHand,
                    EquipmentSlot.HAND
                )

                val resolvedSequenceStage = sequenceStage.resolveStage()

                val hasObjectChange =
                    resolvedSequenceStage.getAdapterData() != null || resolvedSequenceStage.isRemove()

                if (!hasObjectChange) {
                    dbgTimed(
                        "sequence effects-only stage " +
                                "loc=${keyLoc.toShortString()} " +
                                "delay=$delayTicks " +
                                "sounds=${resolvedSequenceStage.getSounds().size} " +
                                "items=${resolvedSequenceStage.getItems().size} " +
                                "drops=${resolvedSequenceStage.getDrops().size} " +
                                "commands=${resolvedSequenceStage.getExecuteCommands().size}"
                    )

                    runPreApplyFeaturesForStage(
                        player,
                        compatibility,
                        fakeEvent,
                        keyLoc,
                        toolUsed,
                        generic,
                        resolvedSequenceStage
                    )

                    runApplyFeaturesForStage(
                        player,
                        compatibility,
                        fakeEvent,
                        keyLoc,
                        toolUsed,
                        generic,
                        resolvedSequenceStage
                    )

                    runStageExtras(player, keyLoc, resolvedSequenceStage)
                    executeStageCommands(player, resolvedSequenceStage)

                    finishSequenceStepIfNeeded(
                        delayTicks,
                        sequenceStages,
                        stage,
                        generic,
                        keyLoc,
                        compatibility
                    )

                    return@runLaterAtLocation
                }

                val prevStage = lastSequenceStageByLoc[keyLoc]
                val expectedId = prevStage?.getAdapterData()?.id ?: stage.getAdapterData()?.id

                val isValidCurrentObject = when (prevStage) {
                    is IFurnitureStage -> compatibility.isValidFurniture(keyLoc, expectedId)
                    is IBlockStage -> compatibility.isValidBlock(keyLoc, expectedId)
                    else -> compatibility.isValid(keyLoc, expectedId)
                }
                if (!isValidCurrentObject) {
                    dbgTimed(
                        "sequence task CANCEL invalid current object " +
                                "loc=${keyLoc.toShortString()} " +
                                "prevStage=${prevStage?.getAdapterData()} " +
                                "expectedId=$expectedId " +
                                "prevStageType=${prevStage?.javaClass?.simpleName} " +
                                "comp=${compatibility.adapterComp().type} " +
                                "currentUuid=${compatibility.getFurnitureUUID(keyLoc)} " +
                                "expectedUuid=${activeSequenceUuids[keyLoc]}"
                    )

                    cancelSequence(compatibility, keyLoc)
                    return@runLaterAtLocation
                }

                /*val center = loc.clone().add(0.5, 0.5, 0.5)
                val nearby = loc.world.getNearbyEntities(center, 1.5, 1.5, 1.5)
                for (entity in nearby) {
                    if (entity.location.block != loc.block || !entity.isValid || entity.isDead) { continue }

                    if (!compatibility.isValidUUID(loc,stage.getAdapterData()?.id,activeSequenceUuids[loc])) {
                        cancelSequence(compatibility, loc)
                        //Bukkit.getConsoleSender().sendMessage("[UM] isValidUUID=false mi webo gastronomico")
                        return@Runnable
                    }
                }*/

                if (prevStage is IFurnitureStage) {
                    val expectedUuid = activeSequenceUuids[keyLoc]
                    val currentUuid = compatibility.getFurnitureUUID(keyLoc)
                    val validUuid = compatibility.isValidUUID(keyLoc, expectedId, expectedUuid)

                    dbgTimed(
                        "sequence task UUID check " +
                                "loc=${keyLoc.toShortString()} " +
                                "expectedId=$expectedId " +
                                "expectedUuid=$expectedUuid " +
                                "currentUuid=$currentUuid " +
                                "validUuid=$validUuid"
                    )

                    if (!validUuid) {
                        dbgTimed(
                            "sequence task CANCEL invalid UUID " +
                                    "loc=${keyLoc.toShortString()} " +
                                    "expectedId=$expectedId " +
                                    "expectedUuid=$expectedUuid " +
                                    "currentUuid=$currentUuid"
                        )

                        cancelSequence(compatibility, keyLoc)
                        return@runLaterAtLocation
                    }
                }

                //val adapterId = sequenceStage.getAdapterData()?.let { "${it.adapter?.type}:${it.id}" } ?: "null"
                //Bukkit.getConsoleSender().sendMessage("[UM] Ejecutando sequence del stage ${stage.getStage()} con delay $delayTicks ticks para furniture $adapterId")

                dbgTimed(
                    "sequence removing previous object " +
                            "loc=${keyLoc.toShortString()} " +
                            "prevStage=${prevStage?.getAdapterData()} " +
                            "prevStageType=${prevStage?.javaClass?.simpleName} " +
                            "uuid=${activeSequenceUuids[keyLoc]}"
                )

                if (prevStage is IFurnitureStage) {
                    val oldUuid = activeSequenceUuids[keyLoc]
                    val targetAdapter = resolvedSequenceStage.getAdapterData()
                    val currentAdapterId = expectedId

                    if (
                        resolvedSequenceStage is IFurnitureStage &&
                        targetAdapter != null &&
                        currentAdapterId != null &&
                        isSimilarCompatibility(targetAdapter, compatibility)
                    ) {
                        val newUuid = compatibility.placeNewFurnitureThenRemoveOld(
                            loc = keyLoc,
                            currentAdapterId = currentAdapterId,
                            targetAdapterId = targetAdapter.id,
                            oldUuid = oldUuid
                        )

                        if (newUuid != null) {
                            lockTransition(keyLoc, 3L)

                            runPreApplyFeaturesForStage(
                                player,
                                compatibility,
                                fakeEvent,
                                keyLoc,
                                toolUsed,
                                generic,
                                resolvedSequenceStage
                            )

                            runApplyFeaturesForStage(
                                player,
                                compatibility,
                                fakeEvent,
                                keyLoc,
                                toolUsed,
                                generic,
                                resolvedSequenceStage
                            )

                            runStageExtras(player, keyLoc, resolvedSequenceStage)
                            executeStageCommands(player, resolvedSequenceStage)

                            lastSequenceStageByLoc[keyLoc] = resolvedSequenceStage
                            activeSequenceUuids[keyLoc] = newUuid

                            registerTimedSequenceIfNeeded(
                                compatibility,
                                keyLoc,
                                toolUsed,
                                generic,
                                resolvedSequenceStage
                            )

                            finishSequenceStepIfNeeded(
                                delayTicks,
                                sequenceStages,
                                stage,
                                generic,
                                keyLoc,
                                compatibility
                            )

                            return@runLaterAtLocation
                        }
                    }

                    lockTransition(keyLoc, 6L)

                    val removed = compatibility.removeFurnitureByUUID(keyLoc, oldUuid)

                    if (!removed) {
                        compatibility.handleRemove(player, fakeEvent, keyLoc, toolUsed, generic, prevStage)
                    }

                    val stillSameFurniture = oldUuid != null && compatibility.getFurnitureUUID(keyLoc) == oldUuid

                    if (stillSameFurniture) {
                        scheduleFurnitureApplyWhenCleared(
                            player = player,
                            compatibility = compatibility,
                            fakeEvent = fakeEvent,
                            keyLoc = keyLoc,
                            toolUsed = toolUsed,
                            generic = generic,
                            resolvedSequenceStage = resolvedSequenceStage,
                            oldUuid = oldUuid,
                            delayTicks = delayTicks,
                            sequenceStages = sequenceStages,
                            stage = stage,
                            attemptsLeft = 5
                        )
                    } else {
                        applySequenceStep(
                            player,
                            compatibility,
                            fakeEvent,
                            keyLoc,
                            toolUsed,
                            generic,
                            resolvedSequenceStage
                        )

                        finishSequenceStepIfNeeded(
                            delayTicks,
                            sequenceStages,
                            stage,
                            generic,
                            keyLoc,
                            compatibility
                        )
                    }

                    return@runLaterAtLocation
                }

                prevStage?.let {
                    compatibility.handleRemove(player, fakeEvent, keyLoc, toolUsed, generic, it)
                }

                applySequenceStep(player, compatibility, fakeEvent, keyLoc, toolUsed, generic, resolvedSequenceStage)

                finishSequenceStepIfNeeded(
                    delayTicks,
                    sequenceStages,
                    stage,
                    generic,
                    keyLoc,
                    compatibility
                )
            }
            tasks.add(task)
        }

        scheduledTasks[keyLoc] = tasks
    }

    private fun runPreApplyFeaturesForStage(
        player: Player,
        compatibility: ICompatibility,
        event: Event,
        loc: Location,
        toolUsed: LiveTool,
        generic: IGeneric,
        stage: Stage
    ) {
        Features.getFeatures().forEach { feature ->
            try {
                feature.onPreApply(player, compatibility, event, loc, toolUsed, stage, generic)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun runApplyFeaturesForStage(
        player: Player,
        compatibility: ICompatibility,
        event: Event,
        loc: Location,
        toolUsed: LiveTool,
        generic: IGeneric,
        stage: Stage
    ) {
        Features.getFeatures().forEach { feature ->
            try {
                feature.onApply(player, compatibility, event, loc, toolUsed, stage, generic)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun applySequenceStep(
        player: Player,
        compatibility: ICompatibility,
        event: Event,
        loc: Location,
        toolUsed: LiveTool,
        generic: IGeneric,
        stage: Stage,
        executeExtras: Boolean = true
    ) {
        val keyLoc = sequenceKey(loc)

        if (!activeSequences.contains(keyLoc)) {
            dbgTimed(
                "applySequenceStep blocked because activeSequences does not contain loc " +
                        "loc=${keyLoc.toShortString()} " +
                        "stage=${stage.getAdapterData()} " +
                        "activeSequences=${activeSequences.map { it.toShortString() }}"
            )
            return
        }

        dbgTimed(
            "applySequenceStep ENTER " +
                    "loc=${keyLoc.toShortString()} " +
                    "rawStage=${stage.getAdapterData()} " +
                    "remove=${stage.isRemove()}"
        )

        // Region Condition for Sequence
        /*if (!stage.matchesRegionConditions(loc)) {
            cancelSequence(compatibility, loc)
            return
        }*/

        val resolvedStage = stage.resolveStage()

        dbgTimed(
            "applySequenceStep resolved " +
                    "loc=${keyLoc.toShortString()} " +
                    "resolvedAdapter=${resolvedStage.getAdapterData()} " +
                    "remove=${resolvedStage.isRemove()} " +
                    "hasTimed=${resolvedStage.hasTimedSequenceInteraction()}"
        )

        if (resolvedStage.getAdapterData() == null) {
            runPreApplyFeaturesForStage(player, compatibility, event, keyLoc, toolUsed, generic, resolvedStage)
            runApplyFeaturesForStage(player, compatibility, event, keyLoc, toolUsed, generic, resolvedStage)

            if (executeExtras) {
                runStageExtras(player, keyLoc, resolvedStage)
                executeStageCommands(player, resolvedStage)
            }

            if (resolvedStage.isRemove()) {
                compatibility.handleRemove(player, event, keyLoc, toolUsed, generic, resolvedStage)
                cancelSequence(compatibility, keyLoc)
                StageData.removeStageData(keyLoc)
                compatibility.clearRemoving(keyLoc)
            }

            return
        }

        resolvedStage.getAdapterData()?.let { adapterData ->
            if (isSimilarCompatibility(adapterData, compatibility)) {
                runPreApplyFeaturesForStage(player, compatibility, event, keyLoc, toolUsed, generic, resolvedStage)
                runApplyFeaturesForStage(player, compatibility, event, keyLoc, toolUsed, generic, resolvedStage)

                if (resolvedStage is IFurnitureStage) {
                    lockTransition(keyLoc, 6L)
                }

                compatibility.handleSequenceStage(player, adapterData, event, keyLoc, toolUsed, generic, resolvedStage)

                if (executeExtras) {
                    runStageExtras(player, keyLoc, resolvedStage)
                    executeStageCommands(player, resolvedStage)
                }
                lastSequenceStageByLoc[keyLoc] = resolvedStage

                registerTimedSequenceIfNeeded(
                    compatibility,
                    keyLoc,
                    toolUsed,
                    generic,
                    resolvedStage
                )

                if (resolvedStage.isRemove()) {
                    compatibility.handleRemove(player, event, keyLoc, toolUsed, generic, resolvedStage)
                    cancelSequence(compatibility, keyLoc)
                    StageData.removeStageData(keyLoc)
                    compatibility.clearRemoving(keyLoc)
                    return
                }

                runLaterAtLocation(keyLoc, 1L) {
                    val previousUuid = activeSequenceUuids[keyLoc]
                    val newUuid = compatibility.getFurnitureUUID(keyLoc)
                    activeSequenceUuids[keyLoc] = newUuid

                    dbgTimed(
                        "sequence uuid updated after apply " +
                                "loc=${keyLoc.toShortString()} " +
                                "previousUuid=$previousUuid " +
                                "newUuid=$newUuid " +
                                "stage=${resolvedStage.getAdapterData()} " +
                                "comp=${compatibility.adapterComp().type}"
                    )
                }
            } else {
                val c: ICompatibility =
                    getCompatibilityByAdapterId(adapterData)
                        ?: throw NullPointerException("Compatibility not found for $adapterData")

                // Cross-compatibility replace is forced on purpose.
                // We do not validate with target compatibility because the current object
                // belongs to another compatibility system.

                runPreApplyFeaturesForStage(player, c, event, keyLoc, toolUsed, generic, resolvedStage)
                runApplyFeaturesForStage(player, c, event, keyLoc, toolUsed, generic, resolvedStage)

                if (resolvedStage is IFurnitureStage) {
                    lockTransition(keyLoc, 6L)
                }

                c.handleSequenceStage(player, adapterData, event, keyLoc, toolUsed, generic, resolvedStage)

                if (executeExtras) {
                    runStageExtras(player, keyLoc, resolvedStage)
                    executeStageCommands(player, resolvedStage)
                }
                lastSequenceStageByLoc[keyLoc] = resolvedStage

                registerTimedSequenceIfNeeded(
                    c,
                    keyLoc,
                    toolUsed,
                    generic,
                    resolvedStage
                )

                if (resolvedStage.isRemove()) {
                    c.handleRemove(player, event, keyLoc, toolUsed, generic, resolvedStage)
                    cancelSequence(c, keyLoc)
                    StageData.removeStageData(keyLoc)
                    c.clearRemoving(keyLoc.block.location)
                    return
                }

                runLaterAtLocation(keyLoc, 1L) {
                    val previousUuid = activeSequenceUuids[keyLoc]
                    val newUuid = c.getFurnitureUUID(keyLoc)
                    activeSequenceUuids[keyLoc] = newUuid

                    dbgTimed(
                        "sequence uuid updated after cross apply " +
                                "loc=${keyLoc.toShortString()} " +
                                "previousUuid=$previousUuid " +
                                "newUuid=$newUuid " +
                                "stage=${resolvedStage.getAdapterData()} " +
                                "comp=${c.adapterComp().type}"
                    )
                }
            }
        }
    }

    fun cancelSequence(compatibility: ICompatibility, loc: Location) {
        val keyLoc = sequenceKey(loc)

        dbgTimed(
            "cancelSequence " +
                    "loc=${keyLoc.toShortString()} " +
                    "scheduled=${scheduledTasks[keyLoc]?.size ?: 0} " +
                    "hasTimed=${activeTimedSequences.containsKey(keyLoc)} " +
                    "uuid=${activeSequenceUuids[keyLoc]}"
        )

        scheduledTasks[keyLoc]?.forEach { it.cancel() }
        scheduledTasks.remove(keyLoc)

        activeSequences.remove(keyLoc)
        startingSequences.remove(keyLoc)
        consumingTimedSequences.remove(keyLoc)

        activeSequenceUuids.remove(keyLoc)
        lastSequenceStageByLoc.remove(keyLoc)
        activeTimedSequences.remove(keyLoc)

        compatibility.getFurnitureUUID(keyLoc).let { furnitureuuid ->
            furnitureuuid?.let { compatibility.clearRemoving(keyLoc) }
        }
    }

    private fun registerTimedSequenceIfNeeded(
        compatibility: ICompatibility,
        loc: Location,
        toolUsed: LiveTool,
        generic: IGeneric,
        resolvedStage: Stage
    ) {
        val timedInteraction = resolvedStage.getTimedSequenceInteraction()
        if (timedInteraction == null) {
            dbgTimed(
                "no timed_interaction on sequence stage " +
                        "loc=${loc.block.location.toShortString()} " +
                        "stage=${resolvedStage.getStage()} " +
                        "adapter=${resolvedStage.getAdapterData()}"
            )
            return
        }

        val openTick = Bukkit.getCurrentTick()
        val closeTick = openTick + timedInteraction.collectWindowTicks.toInt()

        activeTimedSequences[loc.block.location] = ActiveTimedSequence(
            compatibility,
            generic,
            toolUsed,
            resolvedStage,
            timedInteraction,
            openTick,
            closeTick
        )

        dbgTimed(
            "registered timed_interaction " +
                    "loc=${loc.block.location.toShortString()} " +
                    "stage=${resolvedStage.getStage()} " +
                    "adapter=${resolvedStage.getAdapterData()} " +
                    "window=${timedInteraction.collectWindowTicks} " +
                    "openTick=$openTick " +
                    "closeTick=$closeTick " +
                    "outcomes=${timedInteraction.outcomes.map { (id, outcome) ->
                        "$id tools=${outcome.tools.map { it.getAdapterData() }} fallback=${outcome.isFallback()} success=${outcome.successStage.getAdapterData()}"
                    }}"
        )
    }

    private fun hasTimedOutcomeToolPermission(player: Player, tool: ITool): Boolean {
        val permission = tool.getToolPermission()

        if (permission.isNullOrBlank()) {
            return true
        }

        return if (LuckPermsPlugin.isLuckPermsEnabled()) {
            core.getLuckPermsComb().hasPermission(player, permission)
        } else {
            player.hasPermission(permission) || player.isOp
        }
    }

    private fun tryHandleTimedSequenceInteract(
        player: Player,
        loc: Location,
        event: Event
    ): Boolean {
        val keyLoc = sequenceKey(loc)
        val active = activeTimedSequences[keyLoc]
        if (active == null) {
            return false
        }

        if (!canUseUnearthInteraction(player, keyLoc, active.generic)) {
            if (event is Cancellable) {
                event.isCancelled = true
            }

            consumingTimedSequences.remove(keyLoc)
            activeTimedSequences.remove(keyLoc)
            cancelSequence(active.compatibility, keyLoc)

            return true
        }

        if (consumingTimedSequences.contains(keyLoc)) {
            if (event is Cancellable) {
                event.isCancelled = true
            }

            dbgTimed(
                "timed_interaction already consuming, ignoring duplicate click " +
                        "loc=${keyLoc.toShortString()}"
            )

            return true
        }

        val currentTick = Bukkit.getCurrentTick()

        dbgTimed(
            "tryHandleTimedSequenceInteract ENTER " +
                    "player=${player.name} " +
                    "loc=${loc.toShortString()} " +
                    "currentTick=$currentTick " +
                    "openTick=${active.openTick} " +
                    "closeTick=${active.closeTick} " +
                    "readyStage=${active.readyStage.getAdapterData()} " +
                    "event=${event.javaClass.simpleName}"
        )

        if (currentTick < active.openTick) {
            dbgTimed("click before window loc=${loc.toShortString()}")
            if (event is Cancellable) event.isCancelled = true
            return true
        }

        if (currentTick > active.closeTick) {
            dbgTimed(
                "click after window, removing expired timed_interaction and letting normal flow continue " +
                        "loc=${keyLoc.toShortString()} currentTick=$currentTick closeTick=${active.closeTick}"
            )

            activeTimedSequences.remove(keyLoc)
            return false
        }

        if (event is Cancellable) {
            event.isCancelled = true
        }

        player.swingMainHand()

        consumingTimedSequences.add(keyLoc)

        val clickedItem = if (animator.isAnimating(player)) {
            animator.getAnimation(player)?.getItemMainHand() ?: player.inventory.itemInMainHand
        } else {
            player.inventory.itemInMainHand
        }

        val clickedToolId = Adapter.getAdapterId(clickedItem)
        val clickedToolAdapter = Adapter.getAdapterData(clickedToolId).getOrNull()

        dbgTimed(
            "clicked item resolved " +
                    "player=${player.name} " +
                    "clickedToolId=$clickedToolId " +
                    "clickedToolAdapter=$clickedToolAdapter " +
                    "outcomes=${active.timedInteraction.outcomes.keys}"
        )

        val outcome = active.timedInteraction.findOutcome(clickedToolAdapter)
        if (outcome == null) {
            consumingTimedSequences.remove(keyLoc)

            dbgTimed(
                "NO OUTCOME matched " +
                        "clickedToolAdapter=$clickedToolAdapter " +
                        "outcomes=${active.timedInteraction.outcomes.map { (id, out) ->
                            "$id tools=${out.tools.map { it.getAdapterData() }} fallback=${out.isFallback()}"
                        }}"
            )

            return true
        }

        dbgTimed(
            "OUTCOME matched " +
                    "id=${outcome.id} " +
                    "fallback=${outcome.isFallback()} " +
                    "tools=${outcome.tools.map { it.getAdapterData() }} " +
                    "successAdapter=${outcome.successStage.getAdapterData()} " +
                    "successRemove=${outcome.successStage.isRemove()}"
        )

        val outcomeTool = outcome.getMatchingTool(clickedToolAdapter)
            ?: active.originalToolUsed.getITool()

        val outcomeLiveTool = LiveTool(
            ToolData(clickedItem, player.inventory.heldItemSlot),
            outcomeTool,
            player,
            this
        )

        if (!hasTimedOutcomeToolPermission(player, outcomeLiveTool.getITool())) {
            consumingTimedSequences.remove(keyLoc)

            dbgTimed(
                "permission denied " +
                        "player=${player.name} " +
                        "permission=${outcomeLiveTool.getITool().getToolPermission()} " +
                        "outcome=${outcome.id}"
            )

            return true
        }

        val fakeEvent = FakePlayerInteractEvent(
            player,
            keyLoc.block,
            clickedItem,
            EquipmentSlot.HAND
        )

        if (!animator.isAnimating(player)
            && outcomeLiveTool.getITool().getAnimation() != null
            && outcomeLiveTool.getITool().getAnimation()!!.getTicks() > 0
        ) {
            dbgTimed(
                "playing outcome animation " +
                        "outcome=${outcome.id} " +
                        "animation=${outcomeLiveTool.getITool().getAnimation()}"
            )

            animator.playAnimation(player, outcomeLiveTool.getITool().getAnimation()!!)
        }

        activeTimedSequences.remove(keyLoc)

        val successStage = outcome.successStage.resolveStage()

        dbgTimed(
            "successStage resolved " +
                    "outcome=${outcome.id} " +
                    "adapter=${successStage.getAdapterData()} " +
                    "remove=${successStage.isRemove()} " +
                    "items=${successStage.getItems().size} " +
                    "drops=${successStage.getDrops().size} " +
                    "sounds=${successStage.getSounds().size} " +
                    "commands=${successStage.getExecuteCommands().size}"
        )

        if (successStage.getAdapterData() != null || successStage.isRemove()) {
            dbgTimed(
                "applying success with object replacement " +
                        "loc=${keyLoc.toShortString()} " +
                        "outcome=${outcome.id} " +
                        "adapter=${successStage.getAdapterData()}"
            )

            removeCurrentSequenceObject(
                player,
                active.compatibility,
                fakeEvent,
                keyLoc,
                outcomeLiveTool,
                active.generic
            )

            applySequenceStep(
                player,
                active.compatibility,
                fakeEvent,
                keyLoc,
                outcomeLiveTool,
                active.generic,
                successStage,
                executeExtras = true
            )

            cancelSequence(active.compatibility, keyLoc)
            StageData.removeStageData(keyLoc)
            active.compatibility.clearRemoving(keyLoc)

            dbgTimed(
                "success applied and sequence cancelled " +
                        "loc=${loc.toShortString()} outcome=${outcome.id}"
            )
        } else {
            dbgTimed(
                "applying success without object replacement " +
                        "loc=${loc.toShortString()} outcome=${outcome.id}"
            )

            runPreApplyFeaturesForStage(
                player,
                active.compatibility,
                fakeEvent,
                keyLoc,
                outcomeLiveTool,
                active.generic,
                successStage
            )

            runApplyFeaturesForStage(
                player,
                active.compatibility,
                fakeEvent,
                keyLoc,
                outcomeLiveTool,
                active.generic,
                successStage
            )

            runStageExtras(player, keyLoc, successStage)
            executeStageCommands(player, successStage)
        }

        consumingTimedSequences.remove(keyLoc)
        return true
    }

    private fun removeCurrentSequenceObject(
        player: Player,
        compatibility: ICompatibility,
        event: Event,
        loc: Location,
        toolUsed: LiveTool,
        generic: IGeneric
    ) {
        val keyLoc = sequenceKey(loc)
        val prevStage = lastSequenceStageByLoc[keyLoc] ?: return

        if (prevStage is IFurnitureStage) {
            lockTransition(keyLoc, 8L)
        }

        if (prevStage is IFurnitureStage) {
            val removed = compatibility.removeFurnitureByUUID(keyLoc, activeSequenceUuids[keyLoc])
            if (!removed) {
                compatibility.handleRemove(
                    player,
                    event,
                    keyLoc,
                    toolUsed,
                    generic,
                    prevStage
                )
            }
        } else {
            compatibility.handleRemove(
                player,
                event,
                keyLoc,
                toolUsed,
                generic,
                prevStage
            )
        }
    }

    private fun hasUnearthBypass(player: Player): Boolean {
        return player.isOp
                || (LuckPermsPlugin.isLuckPermsEnabled()
                && core.getLuckPermsComb().hasPermission(player, "unearthMechanic.bypass"))
                || player.hasPermission("unearthMechanic.bypass")
    }

    private fun canUseUnearthInteraction(
        player: Player,
        location: Location,
        generic: IGeneric
    ): Boolean {
        val keyLoc = sequenceKey(location)

        if (hasUnearthBypass(player)) {
            dbgStage("canUseUnearthInteraction true bypass player=${player.name} loc=${keyLoc.debugShort()} generic=${generic.getId()}")
            return true
        }

        if (generic.isNotProtect()) {
            dbgStage("canUseUnearthInteraction true notProtect loc=${keyLoc.debugShort()} generic=${generic.getId()}")
            return true
        }

        val allowed = if (WorldGuardPlugin.isWorldGuardEnabled()) {
            core.getWorldGuardComp().canModify(player, keyLoc)
        } else {
            core.getAntiGriefLib().test(player, Flag.INTERACT, keyLoc)
        }

        dbgStage(
            "canUseUnearthInteraction result=$allowed worldGuard=${WorldGuardPlugin.isWorldGuardEnabled()} " +
                    "player=${player.name} loc=${keyLoc.debugShort()} generic=${generic.getId()}"
        )

        dbgStage(
            "[UM-WG] player=${player.name} loc=${keyLoc.world?.name}:${keyLoc.blockX},${keyLoc.blockY},${keyLoc.blockZ} " +
                    "generic=${generic.getId()} notProtect=${generic.isNotProtect()} wg=${WorldGuardPlugin.isWorldGuardEnabled()} allowed=$allowed"
        )

        return allowed
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onPreCancelCraftEngineBlockToolPlace(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) {
            dbgStage("preCancel ignored offhand hand=${event.hand}")
            return
        }
        if (event.action != Action.RIGHT_CLICK_BLOCK) {
            dbgStage("preCancel ignored action=${event.action}")
            return
        }

        val player = event.player
        val loc = event.clickedBlock?.location ?: run {
            dbgStage("preCancel STOP clickedBlock null player=${player.name}")
            return
        }

        val toolId = Adapter.getAdapterId(
            animator.getAnimation(player)?.getItemMainHand() ?: player.inventory.itemInMainHand
        )

        val toolAdapter = Adapter.getAdapterData(toolId).getOrNull() ?: run {
            dbgStage("preCancel STOP no toolAdapter toolId=$toolId player=${player.name} loc=${loc.debugShort()}")
            return
        }

        if (!toolAdapter.adapter.type.equals("CraftEngine", ignoreCase = true)
            && !toolAdapter.adapter.type.equals("ce", ignoreCase = true)
        ) {
            dbgStage("preCancel ignored non-CE tool=${toolAdapter.id} adapter=${toolAdapter.adapter.type} loc=${loc.debugShort()}")
            return
        }

        val craftEngineCompatibility = compatibilitiesLoaded.firstOrNull {
            it.adapterComp().type.equals("CraftEngine", ignoreCase = true)
                    || it.adapterComp().type.equals("ce", ignoreCase = true)
        } ?: run {
            dbgStage("preCancel STOP CraftEngine compatibility not loaded loc=${loc.debugShort()}")
            return
        }

        val baseAdapter = craftEngineCompatibility.getCurrentAdapterDataAt(event, loc) ?: run {
            dbgStage("preCancel STOP no baseAdapter at loc=${loc.debugShort()} tool=${toolAdapter.id}")
            return
        }

        val mode = InteractionMode.fromSneaking(player.isSneaking)

        val props = try {
            craftEngineCompatibility.getCurrentBlockPropsFromEvent(event, loc)
        } catch (_: Throwable) {
            emptyMap()
        }

        val generic = core.getConfigManager()
            .getGeneric(baseAdapter, toolAdapter, mode, props)
            ?: run {
                dbgStage(
                    "preCancel STOP no generic base=${baseAdapter.id} tool=${toolAdapter.id} " +
                            "mode=$mode props=$props loc=${loc.debugShort()}"
                )
                return
            }

        val canInteract = canUseUnearthInteraction(player, loc, generic)
        dbgStage(
            "preCancel matched base=${baseAdapter.id} tool=${toolAdapter.id} generic=${generic.getId()} " +
                    "mode=$mode props=$props canInteract=$canInteract loc=${loc.debugShort()}"
        )

        if (!canInteract) {
            event.isCancelled = true
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
            dbgStage("preCancel CANCEL denied by protection loc=${loc.debugShort()} generic=${generic.getId()}")
            return
        }

        event.isCancelled = true
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.ALLOW)
        dbgStage("preCancel CANCEL item use only, allow block interaction loc=${loc.debugShort()} generic=${generic.getId()}")
    }

    override fun getCompatibilitiesLoaded(): MutableList<ICompatibility> {
        return compatibilitiesLoaded
    }

    override fun getCompatibilityByAdapterId(adapterData: AdapterData): ICompatibility? {
        return compatibilitiesLoaded.firstOrNull { compatibility -> compatibility.adapterComp() == adapterData.adapter }
    }

    override fun isSimilarCompatibility(adapterData: AdapterData, compatibility: ICompatibility): Boolean {
        return compatibility.adapterComp() == adapterData.adapter
    }

    override fun getAnimator(): IAnimationManager {
        return animator
    }

    fun getDelays(): HashMap<Location, WrappedTask> {
        return delays
    }

    private fun canonicalStageTarget(comp: ICompatibility, block: Block): Location {
        val data = block.blockData

        if (data is Door) {
            return when (data.half) {
                Bisected.Half.TOP -> block.getRelative(BlockFace.DOWN).location.block.location
                Bisected.Half.BOTTOM -> block.location.block.location
            }
        }

        if (comp is CraftEngineImpl) {
            return CraftEngineImpl.getCraftEngineDoubleBlockLowerLocationStatic(block.location)
                ?: block.location.block.location
        }

        return block.location.block.location
    }

    private fun canonicalMultipleTarget(comp: ICompatibility, block: Block): Location {
        return canonicalStageTarget(comp, block)
    }

    private fun multipleInteract(comp: ICompatibility, event: Event, player: Player, location: Location, toolUsed: LiveTool) {
        if (toolUsed.getITool().isMultiple() && !StageData.hasMultiple(location)) {
            val blockFace: BlockFace = comp.getBlockFace(event) ?: player.getTargetBlockFace(999999999, FluidCollisionMode.NEVER) ?: return
            val visitedTargets = mutableSetOf<Location>()

            Utils.blockAround(
                location.block,
                toolUsed.getITool().getSize(),
                toolUsed.getITool().getDeep(),
                toolUsed.getITool().getDepth(),
                player,
                blockFace
            ).forEach { block ->
                if (block != null) {
                    val targetLoc = canonicalMultipleTarget(comp, block)

                    if (!visitedTargets.add(targetLoc)) {
                        return@forEach
                    }

                    val targetBlock = targetLoc.block

                    try {
                        StageData.applyMultiple(targetBlock)
                        val playerInteractEvent: Event = PlayerInteractEvent(
                            player,
                            Action.RIGHT_CLICK_BLOCK,
                            toolUsed.getItemMainHand(),
                            targetBlock,
                            blockFace,
                            EquipmentSlot.HAND
                        )
                        Bukkit.getPluginManager().callEvent(playerInteractEvent)
                    } finally {
                        StageData.removeMultiple(targetBlock)
                    }
                }
            }
        }
    }

    private fun compCreator(pluginName: String, compatibilityMaker: (pluginName: String) -> ICompatibility ) : ICompatibility? {
        if (Bukkit.getPluginManager().getPlugin(pluginName) == null) return null
        return compatibilityMaker.invoke(pluginName)
    }
}