package dev.wuason.unearthMechanic.system

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
import dev.wuason.unearthMechanic.system.compatibilities.crucible.CrucibleBridgeImpl
import dev.wuason.unearthMechanic.system.compatibilities.crucible.MythicCrucibleImpl
import dev.wuason.unearthMechanic.system.compatibilities.ia.ItemsAdderImpl
import dev.wuason.unearthMechanic.system.compatibilities.nexo.NexoImpl
import dev.wuason.unearthMechanic.system.compatibilities.or.OraxenImpl
import dev.wuason.unearthMechanic.system.features.BasicFeatures
import dev.wuason.unearthMechanic.system.features.DurabilityFeature
import dev.wuason.unearthMechanic.system.features.Features
import dev.wuason.unearthMechanic.system.features.TintFurnitureFeature
import dev.wuason.unearthMechanic.system.features.ToolSoundFeature
import dev.wuason.unearthMechanic.utils.Utils
import net.momirealms.antigrieflib.Flag
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.scheduler.BukkitTask
import java.util.*
import kotlin.jvm.optionals.getOrNull


class StageManager(private val core: UnearthMechanic) : IStageManager, org.bukkit.event.Listener {

    companion object {
        init {
            Features.registerFeature(BasicFeatures())
            Features.registerFeature(DurabilityFeature())
            Features.registerFeature(ToolSoundFeature())
            Features.registerFeature(TintFurnitureFeature())
        }
    }

    private val compatibilitiesLoaded: MutableList<ICompatibility> = ArrayList()

    private val delays: HashMap<Location, BukkitTask> = HashMap()

    private val animator: AnimationManager = AnimationManager(core)

    public val activeSequences = mutableSetOf<Location>()
    private val startingSequences = mutableSetOf<Location>()
    private val consumingTimedSequences = mutableSetOf<Location>()

    private val activeSequenceUuids = mutableMapOf<Location, UUID?>()
    private val scheduledTasks = mutableMapOf<Location, MutableList<BukkitTask>>()

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
            MythicCrucibleImpl(
                pluginName, core, this, Adapter.getAdapterByName(pluginName),
                CrucibleBridgeImpl()
            )
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

    private val debugTimedSequence = false

    private fun dbgTimed(message: String) {
        if (debugTimedSequence) {
            core.logger.info("[UM-TIMED][tick=${Bukkit.getCurrentTick()}] $message")
        }
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

    fun interact(player: Player, baseItemId: String, location: Location, event: Event, compatibility: ICompatibility) {
        if (event is PlayerInteractEvent) {
            if (event.hand != EquipmentSlot.HAND) return
            if (event.action != Action.RIGHT_CLICK_BLOCK) return
        }
        //core.logger.info("[UM-DBG] interact ENTER baseItemId=$baseItemId loc=${location.blockX},${location.blockY},${location.blockZ} comp=${compatibility.adapterComp()?.type}")
        //if (player.isSneaking) return

        if (compatibility.isRemoving(location.block.location)) return

        val keyLoc = sequenceKey(location)

        if (isTransitioning(keyLoc)) {
            if (event is Cancellable) {
                event.isCancelled = true
            }
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
            return
        }

        if (StageData.hasStageData(location)) {
            val stageData: StageData = StageData.fromLoc(location) ?: return
            //Bukkit.getConsoleSender().sendMessage("el stagedata es "+stageData.getGeneric().isNotProtect())
            val toolUsed: String = Adapter.getAdapterId(
                animator.getAnimation(player)?.getItemMainHand() ?: player.inventory.itemInMainHand
            )
            val toolAdapter = Adapter.getAdapterData(toolUsed).getOrNull() ?: run {
                //core.logger.info("[UM-DBG] INVALID toolAdapter (exist) from $toolUsed")
                return
            }

            interactExist(
                player,
                baseItemId,
                location,
                event,
                compatibility,
                stageData,
                toolAdapter
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
            return
        }

        val toolAdapter = Adapter.getAdapterData(toolUsed).getOrNull() ?: run {
            //core.logger.info("[UM-DBG] INVALID toolAdapter from $toolUsed")
            return
        }
        val mode = InteractionMode.fromSneaking(player.isSneaking)
        val props = getCurrentBlockProps(event, location, compatibility)

        val configManager = core.getConfigManager()

        val generic = configManager.getGeneric(baseAdapter, toolAdapter, mode, props)

        //core.logger.info("[UM-DBG] generic result=${generic?.getId() ?: "NULL"}")

        if (generic != null) {
            //core.logger.info("[UM-DBG] calling interactNotExist generic=${generic.getId()}")
            interactNotExist(player, baseAdapter, location, event, compatibility, toolAdapter)
        } else {
            //core.logger.info("[UM-DBG] STOP no generic matched")
        }
    }

    private fun interactExist(
        player: Player,
        itemId: String,
        location: Location,
        event: Event,
        compatibility: ICompatibility,
        stageData: StageData,
        toolUsed: AdapterData
    ) {
        if (!stageData.getGeneric().existsTool(toolUsed)) return
        lastInteractionProps[location.block.location] = getCurrentBlockProps(event, location, compatibility)

        // Check if the INTERACTION MODE matches the player
        if (!stageData.getGeneric().getInteractionMode().matches(player.isSneaking)) return

        if (stageData.getActualAdapterData().adapter != compatibility.adapterComp()) return

        if (canInteractExist(player, location, stageData, core)) {

            val iTool: ITool = stageData.getGeneric().getTool(toolUsed) ?: throw NullPointerException(
                "Tool not found for $toolUsed in ${
                    stageData.getGeneric().getId()
                } mabye is duplicated config"
            )

            val liveTool: LiveTool = LiveTool(
                if (animator.isAnimating(player)) animator.getAnimation(player)!!
                    .getItemMainHand() else player.inventory.itemInMainHand, iTool, player, this
            )

            if (stageData.getGeneric().getStages().size <= stageData.getStage()) {
                StageData.removeStageData(location)
                interact(player, itemId, location, event, compatibility)
                return
            }

            stageData.getGeneric().getStages()[stageData.getStage()]?.let {
                val stage: Stage = it as Stage
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
        }
    }

    fun canInteractExist(
        player: Player,
        location: Location,
        stageData: StageData,
        core: UnearthMechanic
    ): Boolean {
        val generic = stageData.getGeneric()

        return hasUnearthBypass(player)
                || generic.isNotProtect()
                || (
                !WorldGuardPlugin.isWorldGuardEnabled()
                        && core.getAntiGriefLib().test(player, Flag.INTERACT, location)
                        && core.getAntiGriefLib().test(player, Flag.BREAK, location)
                        && core.getAntiGriefLib().test(player, Flag.PLACE, location)
                )
                || (
                WorldGuardPlugin.isWorldGuardEnabled()
                        && core.getAntiGriefLib().test(player, Flag.INTERACT, location)
                        && core.getAntiGriefLib().test(player, Flag.BREAK, location)
                        && core.getAntiGriefLib().test(player, Flag.PLACE, location)
                        && core.getWorldGuardComp().canInteractCustom(player, location)
                )
    }

    private fun interactNotExist(
        player: Player,
        baseAdapterData: AdapterData,
        location: Location,
        event: Event,
        compatibility: ICompatibility,
        toolUsed: AdapterData
    ) {
        //.logger.info("[UM-DBG] interactNotExist ENTER base=$baseAdapterData tool=$toolUsed loc=${location.blockX},${location.blockY},${location.blockZ}")
        //if (!core.getConfigManager().validTool(baseAdapterData, toolUsed)) return

        val mode = InteractionMode.fromSneaking(player.isSneaking)
        val currentProps = getCurrentBlockProps(event, location, compatibility)

        lastInteractionProps[location.block.location] = currentProps

        val configManager = core.getConfigManager()

        if (!configManager.validTool(baseAdapterData, toolUsed, mode, currentProps)) return

        val valid = configManager.validTool(baseAdapterData, toolUsed, mode, currentProps)
        //core.logger.info("[UM-DBG] validTool=$valid mode=$mode props=$currentProps")
        if (!valid) {
            //core.logger.info("[UM-DBG] STOP validTool false")
            return
        }

        val generic: IGeneric = configManager
            .getGeneric(baseAdapterData, toolUsed, mode, currentProps)
            ?: return
        //core.logger.info("[UM-DBG] interactNotExist generic=${generic.getId()} stages=${generic.getStages().size}")

        //Bukkit.getConsoleSender().sendMessage("No existe StageData y es "+ generic.isNotProtect())

        val canInteract = canInteractNotExist(player, location, generic, core)
        //core.logger.info("[UM-DBG] canInteractNotExist=$canInteract noProtect=${generic.isNotProtect()} op=${player.isOp}")

        if (canInteract) {

            val iTool: ITool = generic.getTool(toolUsed)
                ?: throw NullPointerException("Tool not found for $toolUsed in ${generic.getId()} mabye is duplicated config")

            val liveTool: LiveTool = LiveTool(
                if (animator.isAnimating(player)) animator.getAnimation(player)!!
                    .getItemMainHand() else player.inventory.itemInMainHand, iTool, player, this
            )

            generic.getStages()[0]?.let {
                val stage: Stage = it as Stage
                onPreApplyStage(player, compatibility, event, location, liveTool, generic, stage, null)
            }
        }
    }

    fun canInteractNotExist(
        player: Player,
        location: Location,
        generic: IGeneric,
        core: UnearthMechanic
    ): Boolean {
        return hasUnearthBypass(player)
                || generic.isNotProtect()
                || (
                !WorldGuardPlugin.isWorldGuardEnabled()
                        && core.getAntiGriefLib().test(player, Flag.INTERACT, location)
                        && core.getAntiGriefLib().test(player, Flag.BREAK, location)
                        && core.getAntiGriefLib().test(player, Flag.PLACE, location)
                )
                || (
                WorldGuardPlugin.isWorldGuardEnabled()
                        && core.getAntiGriefLib().test(player, Flag.INTERACT, location)
                        && core.getAntiGriefLib().test(player, Flag.BREAK, location)
                        && core.getAntiGriefLib().test(player, Flag.PLACE, location)
                        && core.getWorldGuardComp().canInteractCustom(player, location)
                )
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
            return
        }

        //send event
        val eventStage: PreApplyStageEvent =
            PreApplyStageEvent(player, compatibility, event, loc, toolUsed, generic, stage)
        Bukkit.getPluginManager().callEvent(eventStage)
        if (eventStage.isCancelled) return

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
            if (loc in delays) return
            if (event is Cancellable) {
                event.isCancelled = true
            }
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
        } else {
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
        Features.getFeatures().forEach { feature ->
            try {
                feature.onProcess(tick, player, compatibility, event, loc, toolUsed, stage, generic)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (tick >= (stage.getMaxCorrectDelay(toolUsed) - 1)) {
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
        //Bukkit.getConsoleSender().sendMessage("secso Tool "+toolUsed.getITool().getToolPermission().toString())
        toolUsed.getITool()?.getToolPermission()?.let { permission ->
            if (permission.isNotBlank()) {
                val hasPermLuckPerms = if (LuckPermsPlugin.isLuckPermsEnabled()) {
                    core.getLuckPermsComb().hasPermission(player, permission)
                } else {
                    player.hasPermission(permission) || player.isOp
                }
                //Bukkit.getConsoleSender().sendMessage("The player has the $permission permission and is $hasPermLuckPerms of Tool.)
                if (!hasPermLuckPerms) return
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
                if (!hasPermLuckPerms) return
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

            return
        }

        if (stage.getReduceItemHand() > 0 && player.gameMode != GameMode.CREATIVE) {
            val mainHand = toolUsed.getItemMainHand()
            if (mainHand == null || mainHand.type.isAir || mainHand.amount < stage.getReduceItemHand()) return
        }

        if ((validation != null && !validation.validate())
            || !toolUsed.isOriginalItem() || !toolUsed.isValid()
            || !StageData.compare(StageData(loc, stage.getStage(), generic), loc)
        ) return

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

            val targetAdapter = previous?.adapterData ?: fallback ?: return

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

        executeStageCommands(player, resolvedStage)
        //core.logger.info("[UM-DBG] APPLY stageAdapter=${resolvedStage.getAdapterData()}")
        resolvedStage.getAdapterData()?.let {
            if (isSimilarCompatibility(it, compatibility)) {
                if (!isCurrentObjectValid(compatibility, loc, generic, currentStageData)) {
                    compatibility.handleRemove(player, event, loc, toolUsed, generic, resolvedStage)
                    //Bukkit.getConsoleSender().sendMessage("[UM] handleRemove aplicado para $furnitureUuid en $currentTick miwebo")
                }

                //Bukkit.getConsoleSender().sendMessage("[UM] handleStage aplicado para $furnitureUuid en $currentTick")
                //Bukkit.getConsoleSender().sendMessage("[UM] handleStage aplicado para ${stage.getAdapterData()?.adapter?.type}:${stage.getAdapterData()?.id} en ${Bukkit.getCurrentTick()}")
                compatibility.handleStage(player, it, event, loc, toolUsed, generic, resolvedStage)

                if (resolvedStage.getSequenceStages()?.isNotEmpty() == true) {
                    if (resolvedStage is IFurnitureStage) {
                        Bukkit.getScheduler().runTaskLater(core, Runnable {
                            compatibility.getFurnitureUUID(loc.block.location)?.let { uuid ->
                                activeSequenceUuids[loc.block.location] = uuid
                            }
                            handleSequence(player, compatibility, loc, toolUsed, generic, resolvedStage)
                        }, 3L)
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
                    if (c is MinecraftImpl && compatibility.adapterComp()?.type.equals("ItemsAdder", ignoreCase = true)) {
                        compatibility.handleRemove(player, event, loc, toolUsed, generic, resolvedStage)
                    } else {
                        c.handleRemove(player, event, loc, toolUsed, generic, resolvedStage)
                    }
                }

                // place with destination compatibility
                c.handleStage(player, it, event, loc, toolUsed, generic, resolvedStage)

                if (resolvedStage.getSequenceStages()?.isNotEmpty() == true) {
                    if (resolvedStage is IFurnitureStage) {
                        Bukkit.getScheduler().runTaskLater(core, Runnable {
                            c.getFurnitureUUID(loc.block.location)?.let { uuid ->
                                activeSequenceUuids[loc.block.location] = uuid
                            }
                            handleSequence(player, c, loc, toolUsed, generic, resolvedStage)
                        }, 3L)
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
            return
        }

        StageData.saveStageData(loc, StageData(loc, resolvedStage.getStage() + 1, generic))
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

    private fun hasUnearthBypass(player: Player): Boolean {
        return player.isOp
                || (LuckPermsPlugin.isLuckPermsEnabled()
                && core.getLuckPermsComb().hasPermission(player, "unearthMechanic.bypass"))
                || player.hasPermission("unearthMechanic.bypass")
    }

    private fun canModifyAt(player: Player, location: Location, generic: IGeneric? = null): Boolean {
        val keyLoc = sequenceKey(location)

        if (hasUnearthBypass(player)) {
            dbgTimed(
                "canModifyAt ALLOW bypass " +
                        "player=${player.name} " +
                        "loc=${keyLoc.toShortString()}"
            )
            return true
        }

        if (generic?.isNotProtect() == true && !isSequenceLocked(location)) {
            dbgTimed(
                "canModifyAt ALLOW notProtect and not locked " +
                        "player=${player.name} " +
                        "loc=${keyLoc.toShortString()} " +
                        "generic=${generic.getId()}"
            )
            return true
        }

        val canBreak = core.getAntiGriefLib().test(player, Flag.BREAK, keyLoc)
        val canPlace = core.getAntiGriefLib().test(player, Flag.PLACE, keyLoc)

        val result = if (WorldGuardPlugin.isWorldGuardEnabled()) {
            val wgCustom = core.getWorldGuardComp().canInteractCustom(player, keyLoc)

            dbgTimed(
                "canModifyAt CHECK WorldGuard " +
                        "player=${player.name} " +
                        "loc=${keyLoc.toShortString()} " +
                        "canBreak=$canBreak " +
                        "canPlace=$canPlace " +
                        "wgCustom=$wgCustom " +
                        "generic=${generic?.getId()} " +
                        "locked=${isSequenceLocked(keyLoc)}"
            )

            canBreak && canPlace && wgCustom
        } else {
            dbgTimed(
                "canModifyAt CHECK AntiGrief " +
                        "player=${player.name} " +
                        "loc=${keyLoc.toShortString()} " +
                        "canBreak=$canBreak " +
                        "canPlace=$canPlace " +
                        "generic=${generic?.getId()} " +
                        "locked=${isSequenceLocked(keyLoc)}"
            )

            canBreak && canPlace
        }

        dbgTimed(
            "canModifyAt RESULT " +
                    "player=${player.name} " +
                    "loc=${keyLoc.toShortString()} " +
                    "result=$result"
        )

        return result
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

        Bukkit.getScheduler().runTaskLater(core, Runnable {
            transitionArea(loc).forEach { transitionLoc ->
                val keyLoc = sequenceKey(transitionLoc)
                val until = transitioningLocations[keyLoc] ?: return@forEach

                if (Bukkit.getCurrentTick() >= until) {
                    transitioningLocations.remove(keyLoc)
                }
            }
        }, ticks + 1L)
    }

    private fun isSequenceLocked(location: Location): Boolean {
        val keyLoc = sequenceKey(location)

        return isTransitioning(keyLoc)
                || activeSequences.contains(keyLoc)
                || startingSequences.contains(keyLoc)
                || activeTimedSequences.containsKey(keyLoc)
                || consumingTimedSequences.contains(keyLoc)
    }

    private fun isTransitioningNearby(loc: Location): Boolean {
        val base = sequenceKey(loc)

        for (x in -1..1) {
            for (y in -2..2) {
                for (z in -1..1) {
                    val check = base.clone().add(x.toDouble(), y.toDouble(), z.toDouble())
                    if (isTransitioning(check)) return true
                }
            }
        }

        return false
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
        val task = Bukkit.getScheduler().runTaskLater(core, Runnable {
            if (!activeSequences.contains(keyLoc)) return@Runnable

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
                return@Runnable
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
        }, 1L)

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
                    "comp=${compatibility.adapterComp()?.type} " +
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

        val tasks = mutableListOf<BukkitTask>()

        compatibility.getFurnitureUUID(keyLoc)?.let { uuid ->
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
            val previewIsFurnitureTransition =
                stage is IFurnitureStage || previewResolvedStage is IFurnitureStage

            if (previewIsFurnitureTransition) {
                val preLockDelay = (delayTicks - 1L).coerceAtLeast(0L)

                val preLockTask = Bukkit.getScheduler().runTaskLater(core, Runnable {
                    if (!activeSequences.contains(keyLoc)) return@Runnable

                    lockTransition(keyLoc, 8L)

                    dbgTimed(
                        "sequence PRE-LOCK transition " +
                                "loc=${keyLoc.toShortString()} " +
                                "delay=$delayTicks " +
                                "preLockDelay=$preLockDelay " +
                                "nextStage=${previewResolvedStage.getAdapterData()}"
                    )
                }, preLockDelay)

                tasks.add(preLockTask)
            }

            val task = Bukkit.getScheduler().runTaskLater(core, Runnable {
                if (!activeSequences.contains(keyLoc)) return@Runnable
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
                                "comp=${compatibility.adapterComp()?.type} " +
                                "currentUuid=${compatibility.getFurnitureUUID(keyLoc)} " +
                                "expectedUuid=${activeSequenceUuids[keyLoc]}"
                    )

                    cancelSequence(compatibility, keyLoc)
                    return@Runnable
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
                        return@Runnable
                    }
                }

                //val adapterId = sequenceStage.getAdapterData()?.let { "${it.adapter?.type}:${it.id}" } ?: "null"
                //Bukkit.getConsoleSender().sendMessage("[UM] Ejecutando sequence del stage ${stage.getStage()} con delay $delayTicks ticks para furniture $adapterId")

                val fakeEvent = FakePlayerInteractEvent(player, keyLoc.block, player.inventory.itemInMainHand, EquipmentSlot.HAND)

                val resolvedSequenceStage = sequenceStage.resolveStage()

                val isFurnitureTransition =
                    prevStage is IFurnitureStage || resolvedSequenceStage is IFurnitureStage

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

                    if (
                        resolvedSequenceStage is IFurnitureStage &&
                        compatibility is ItemsAdderImpl &&
                        targetAdapter != null
                    ) {
                        val replaced = compatibility.tryReplaceFurnitureAt(keyLoc, targetAdapter.id)

                        if (replaced) {
                            dbgTimed(
                                "sequence furniture transition REPLACE INLINE " +
                                        "loc=${keyLoc.toShortString()} " +
                                        "oldUuid=$oldUuid " +
                                        "nextStage=${resolvedSequenceStage.getAdapterData()}"
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

                            lastSequenceStageByLoc[keyLoc] = resolvedSequenceStage

                            registerTimedSequenceIfNeeded(
                                compatibility,
                                keyLoc,
                                toolUsed,
                                generic,
                                resolvedSequenceStage
                            )

                            executeStageCommands(player, resolvedSequenceStage)

                            Bukkit.getScheduler().runTaskLater(core, Runnable {
                                compatibility.getFurnitureUUID(keyLoc)?.let { uuid ->
                                    activeSequenceUuids[keyLoc] = uuid
                                }

                                finishSequenceStepIfNeeded(
                                    delayTicks,
                                    sequenceStages,
                                    stage,
                                    generic,
                                    keyLoc,
                                    compatibility
                                )
                            }, 1L)

                            return@Runnable
                        }
                    }

                    val removed = compatibility.removeFurnitureByUUID(keyLoc, oldUuid)

                    dbgTimed(
                        "sequence removeFurnitureByUUID result " +
                                "loc=${keyLoc.toShortString()} " +
                                "removed=$removed " +
                                "uuid=$oldUuid " +
                                "currentAfterRemove=${compatibility.getFurnitureUUID(keyLoc)}"
                    )

                    if (!removed) {
                        compatibility.handleRemove(player, fakeEvent, keyLoc, toolUsed, generic, prevStage)
                    }

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

                    return@Runnable
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
            }, delayTicks)
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
        stage: Stage
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

            executeStageCommands(player, resolvedStage)

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

                compatibility.handleSequenceStage(player, adapterData, event, keyLoc, toolUsed, generic, resolvedStage)

                executeStageCommands(player, resolvedStage)
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

                Bukkit.getScheduler().runTaskLater(UnearthMechanic.getInstance(), Runnable {
                    val previousUuid = activeSequenceUuids[keyLoc]
                    val newUuid = compatibility.getFurnitureUUID(keyLoc)
                    activeSequenceUuids[keyLoc] = newUuid

                    dbgTimed(
                        "sequence uuid updated after apply " +
                                "loc=${keyLoc.toShortString()} " +
                                "previousUuid=$previousUuid " +
                                "newUuid=$newUuid " +
                                "stage=${resolvedStage.getAdapterData()} " +
                                "comp=${compatibility.adapterComp()?.type}"
                    )
                }, 1L)
            } else {
                val c: ICompatibility =
                    getCompatibilityByAdapterId(adapterData)
                        ?: throw NullPointerException("Compatibility not found for $adapterData")

                // Cross-compatibility replace is forced on purpose.
                // We do not validate with target compatibility because the current object
                // belongs to another compatibility system.

                runPreApplyFeaturesForStage(player, c, event, keyLoc, toolUsed, generic, resolvedStage)
                runApplyFeaturesForStage(player, c, event, keyLoc, toolUsed, generic, resolvedStage)

                c.handleSequenceStage(player, adapterData, event, keyLoc, toolUsed, generic, resolvedStage)

                executeStageCommands(player, resolvedStage)
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

                Bukkit.getScheduler().runTaskLater(UnearthMechanic.getInstance(), Runnable {
                    val previousUuid = activeSequenceUuids[keyLoc]
                    val newUuid = c.getFurnitureUUID(keyLoc)
                    activeSequenceUuids[keyLoc] = newUuid

                    dbgTimed(
                        "sequence uuid updated after cross apply " +
                                "loc=${keyLoc.toShortString()} " +
                                "previousUuid=$previousUuid " +
                                "newUuid=$newUuid " +
                                "stage=${resolvedStage.getAdapterData()} " +
                                "comp=${c.adapterComp()?.type}"
                    )
                }, 1L)
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

        if (!canModifyAt(player, keyLoc, active.generic)) {
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
            clickedItem,
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
                        "successAdapter=${successStage.getAdapterData()}"
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
                successStage
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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onSequenceBlockBreak(event: BlockBreakEvent) {
        val loc = event.block.location

        if (!isSequenceLocked(loc) && !isTransitioningNearby(loc)) return

        event.isCancelled = true
        event.isDropItems = false
        event.expToDrop = 0

        dbgTimed(
            "BLOCK BREAK blocked " +
                    "player=${event.player.name} " +
                    "loc=${sequenceKey(loc).toShortString()} " +
                    "locked=${isSequenceLocked(loc)} " +
                    "nearTransition=${isTransitioningNearby(loc)}"
        )
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onSequenceBlockDamage(event: BlockDamageEvent) {
        val loc = event.block.location

        if (!isSequenceLocked(loc) && !isTransitioningNearby(loc)) return

        event.isCancelled = true

        dbgTimed(
            "BLOCK DAMAGE blocked " +
                    "player=${event.player.name} " +
                    "loc=${sequenceKey(loc).toShortString()} " +
                    "locked=${isSequenceLocked(loc)} " +
                    "nearTransition=${isTransitioningNearby(loc)}"
        )
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onSequenceEntityDamage(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        val loc = event.entity.location

        if (!isSequenceLocked(loc) && !isTransitioningNearby(loc)) return

        event.isCancelled = true

        dbgTimed(
            "ENTITY DAMAGE blocked " +
                    "player=${player.name} " +
                    "entity=${event.entity.type} " +
                    "entityLoc=${sequenceKey(loc).toShortString()} " +
                    "locked=${isSequenceLocked(loc)} " +
                    "nearTransition=${isTransitioningNearby(loc)}"
        )
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

    fun getDelays(): HashMap<Location, BukkitTask> {
        return delays
    }


    private fun multipleInteract(comp: ICompatibility, event: Event, player: Player, location: Location, toolUsed: LiveTool) {
        if (toolUsed.getITool().isMultiple() && !StageData.hasMultiple(location)) {
            val blockFace: BlockFace = comp.getBlockFace(event) ?: player.getTargetBlockFace(999999999, FluidCollisionMode.NEVER) ?: return
            Utils.blockAround(
                location.block,
                toolUsed.getITool().getSize(),
                toolUsed.getITool().getDeep(),
                toolUsed.getITool().getDepth(),
                player,
                blockFace
            ).forEach { block ->
                if (block != null) {
                    StageData.applyMultiple(block)
                    val playerInteractEvent: Event = PlayerInteractEvent(
                        player,
                        Action.RIGHT_CLICK_BLOCK,
                        toolUsed.getItemMainHand(),
                        block,
                        blockFace,
                        EquipmentSlot.HAND
                    )
                    Bukkit.getPluginManager().callEvent(playerInteractEvent)
                    StageData.removeMultiple(block)
                }
            }
        }
    }

    private fun compCreator(pluginName: String, compatibilityMaker: (pluginName: String) -> ICompatibility ) : ICompatibility? {
        if (Bukkit.getPluginManager().getPlugin(pluginName) == null) return null
        return compatibilityMaker.invoke(pluginName)
    }
}