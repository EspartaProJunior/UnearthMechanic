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
import dev.wuason.unearthMechanic.utils.Utils.Companion.toAdapter
import net.momirealms.antigrieflib.Flag
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.scheduler.BukkitTask
import java.util.*


class StageManager(private val core: UnearthMechanic) : IStageManager {

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
    private val activeSequenceUuids = mutableMapOf<Location, UUID?>()
    private val scheduledTasks = mutableMapOf<Location, MutableList<BukkitTask>>()

    private val transitioningLocations = mutableSetOf<Location>()
    fun isTransitioning(location: Location) = transitioningLocations.contains(location)
    fun addTransitioning(location: Location) = transitioningLocations.add(location)
    fun removeTransitioning(location: Location) = transitioningLocations.remove(location)

    init {

        compatibilitiesLoaded.add(MinecraftImpl("Vanilla", core, this, Adapter.getAdapterByName("Vanilla")))

        compCreator("Oraxen") { pluginName ->
            OraxenImpl(pluginName, core, this, Adapter.getAdapterByName(pluginName))
        } ?.let { compatibilitiesLoaded.add(it) }

        compCreator("ItemsAdder") { pluginName ->
            ItemsAdderImpl(pluginName, core, this, Adapter.getAdapterByName(pluginName))
        } ?.let { compatibilitiesLoaded.add(it) }

        compCreator("Nexo") { pluginName ->
            NexoImpl(pluginName, core, this, Adapter.getAdapterByName(pluginName))
        } ?.let { compatibilitiesLoaded.add(it) }

        compCreator("CraftEngine") { pluginName ->
            CraftEngineImpl(pluginName, core, this, Adapter.getAdapterByName(pluginName))
        } ?.let { compatibilitiesLoaded.add(it) }

        compCreator("MythicCrucible") { pluginName ->
            MythicCrucibleImpl(pluginName, core, this, Adapter.getAdapterByName(pluginName),
                CrucibleBridgeImpl())
        }?.let { compatibilitiesLoaded.add(it) }

        compatibilitiesLoaded.forEach { compatibility ->
            Bukkit.getPluginManager().registerEvents(compatibility, core)
        }

    }

    fun interact(player: Player, baseItemId: String, location: Location, event: Event, compatibility: ICompatibility) {
        if (player.isSneaking) return

        if(compatibility.isRemoving(location.block.location)) return

        if (StageData.hasStageData(location)) {
            val stageData: StageData = StageData.fromLoc(location) ?: return
            //Bukkit.getConsoleSender().sendMessage("el stagedata es "+stageData.getGeneric().isNotProtect())
            val toolUsed: String = Adapter.getAdapterId(
                animator.getAnimation(player)?.getItemMainHand() ?: player.inventory.itemInMainHand
            )
            interactExist(player, baseItemId, location, event, compatibility, stageData, toolUsed.toAdapter()!!)
            return
        }

        if (core.getConfigManager().validBaseItemId(baseItemId.toAdapter()!!)) {
            val toolUsed: String = Adapter.getAdapterId(
                animator.getAnimation(player)?.getItemMainHand() ?: player.inventory.itemInMainHand
            )
            interactNotExist(player, baseItemId.toAdapter()!!, location, event, compatibility, toolUsed.toAdapter()!!)
            return
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

        if (stageData.getActualAdapterData().adapter != compatibility.adapterComp()) return

        if (canInteractExist(player, location, stageData, core)) {

            val iTool: ITool = stageData.getGeneric().getTool(toolUsed) ?: throw NullPointerException(
                "Tool not found for $toolUsed in ${
                    stageData.getGeneric().getId()
                } mabye is duplicated config"
            )

            val liveTool: LiveTool = LiveTool(if (animator.isAnimating(player)) animator.getAnimation(player)!!.getItemMainHand() else player.inventory.itemInMainHand, iTool, player, this)

            if (stageData.getGeneric().getStages().size <= stageData.getStage()) {
                StageData.removeStageData(location)
                interact(player, itemId, location, event, compatibility)
                return
            }

            stageData.getGeneric().getStages()[stageData.getStage()]?.let {
                val stage: Stage = it as Stage
                onPreApplyStage(player, compatibility, event, location, liveTool, stageData.getGeneric(), stage, stageData)
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

        return player.isOp
                || LuckPermsPlugin.isLuckPermsEnabled() && core.getLuckPermsComb().hasPermission(player,"unearthMechanic.bypass")
                || player.hasPermission("unearthMechanic.bypass")
                || generic.isNotProtect()
                || (
                !WorldGuardPlugin.isWorldGuardEnabled() && core.getAntiGriefLib().test(player, Flag.INTERACT, location)
                )
                || (
                WorldGuardPlugin.isWorldGuardEnabled()
                        && core.getAntiGriefLib().test(player, Flag.INTERACT, location)
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
        if (!core.getConfigManager().validTool(baseAdapterData, toolUsed)) return
        val generic: IGeneric = core.getConfigManager().getGeneric(baseAdapterData, toolUsed) ?: return

        //Bukkit.getConsoleSender().sendMessage("No existe StageData y es "+ generic.isNotProtect())

        if (canInteractNotExist(player, location, generic, core)) {

            val iTool: ITool = generic.getTool(toolUsed)
                ?: throw NullPointerException("Tool not found for $toolUsed in ${generic.getId()} mabye is duplicated config")

            val liveTool: LiveTool = LiveTool(if (animator.isAnimating(player)) animator.getAnimation(player)!!.getItemMainHand() else player.inventory.itemInMainHand, iTool, player, this)

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
        return player.isOp
                || LuckPermsPlugin.isLuckPermsEnabled() && core.getLuckPermsComb().hasPermission(player,"unearthMechanic.bypass")
                || player.hasPermission("unearthMechanic.bypass")
                || generic.isNotProtect()
                || (
                !WorldGuardPlugin.isWorldGuardEnabled() && core.getAntiGriefLib().test(player, Flag.INTERACT, location)
                )
                || (
                WorldGuardPlugin.isWorldGuardEnabled()
                        && core.getAntiGriefLib().test(player, Flag.INTERACT, location)
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
        //send event
        val eventStage: PreApplyStageEvent =
            PreApplyStageEvent(player, compatibility, event, loc, toolUsed, generic, stage)
        Bukkit.getPluginManager().callEvent(eventStage)
        if (eventStage.isCancelled) return

        //try multiple interact
        multipleInteract(compatibility, event, player, loc, toolUsed)

        if (!animator.isAnimating(player) && toolUsed.getITool().getAnimation() != null && toolUsed.getITool().getAnimation()!!.getTicks() > 0) {
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
                DelayTask(this, player, compatibility, event, loc, toolUsed, generic, stage, validation,currentStageData)
            delayTask.start()
        } else {
            onApplyStage(player, compatibility, event, loc, toolUsed, generic, stage,null,currentStageData)
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
                } else { player.hasPermission(permission) || player.isOp }
                //Bukkit.getConsoleSender().sendMessage("The player has the $permission permission and is $hasPermLuckPerms of Tool.)
                if (!hasPermLuckPerms) return
            }
        }
        //Bukkit.getConsoleSender().sendMessage("secso Stage "+stage.getPermissionStage())
        stage.getPermissionStage()?.let { permission ->
            if (permission.isNotBlank()) {
                val hasPermLuckPerms = if (LuckPermsPlugin.isLuckPermsEnabled()) {
                    core.getLuckPermsComb().hasPermission(player, permission)
                } else { player.hasPermission(permission) || player.isOp }
                //Bukkit.getConsoleSender().sendMessage("The player has the $permission permission and is $hasPermLuckPerms from Stage.")
                if (!hasPermLuckPerms) return
            }
        }

        if (activeSequences.contains(loc)) {
            //Bukkit.getConsoleSender().sendMessage("[UM] There is already an active sequence in $loc, ignoring new click.")
            return
        }

        if (stage.getReduceItemHand() > 0 && player.gameMode != GameMode.CREATIVE) {
            val mainHand = toolUsed.getItemMainHand()
            if (mainHand == null || mainHand.type.isAir || mainHand.amount < stage.getReduceItemHand()) return
        }

        if ((validation != null && !validation.validate())
            || !toolUsed.isOriginalItem() || !toolUsed.isValid()
            || !StageData.compare(StageData(loc, stage.getStage(), generic), loc)) return

        val resolvedStage = stage.resolveStage()
        /*Bukkit.getConsoleSender().sendMessage(
            "[UM][SM] onApplyStage rawStageId=${stage.getAdapterData()?.id} resolvedStageId=${resolvedStage.getAdapterData()?.id} loc=$loc"
        )*/
        val applyStageEvent: ApplyStageEvent = ApplyStageEvent(player, compatibility, event, loc, toolUsed, generic, resolvedStage)
        Bukkit.getPluginManager().callEvent(applyStageEvent)
        if (applyStageEvent.isCancelled) return

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

        resolvedStage.getAdapterData()?.let {
            if (isSimilarCompatibility(it, compatibility)) {
                if (!isCurrentObjectValid(compatibility, loc, generic, currentStageData)) {
                    compatibility.handleRemove(player, event, loc, toolUsed, generic, resolvedStage)
                    //Bukkit.getConsoleSender().sendMessage("[UM] handleRemove aplicado para $furnitureUuid en $currentTick miwebo")
                }

                //Bukkit.getConsoleSender().sendMessage("[UM] handleStage aplicado para $furnitureUuid en $currentTick")
                //Bukkit.getConsoleSender().sendMessage("[UM] handleStage aplicado para ${stage.getAdapterData()?.adapter?.type}:${stage.getAdapterData()?.id} en ${Bukkit.getCurrentTick()}")
                compatibility.handleStage(player, it, event, loc, toolUsed, generic, resolvedStage)
                executeStageCommands(player, resolvedStage)

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

                c.handleRemove(player, event, loc, toolUsed, generic, resolvedStage)
                //Bukkit.getConsoleSender().sendMessage("[UM] handleRemove2 aplicado para $furnitureUuid en $currentTick")

                c.handleStage(player, it, event, loc, toolUsed, generic, resolvedStage)
                executeStageCommands(player, resolvedStage)
                //Bukkit.getConsoleSender().sendMessage("[UM] handleStage2 aplicado para ${stage.getAdapterData()?.adapter?.type}:${stage.getAdapterData()?.id} en ${Bukkit.getCurrentTick()}")
                //Bukkit.getConsoleSender().sendMessage("[UM] handleStage2 aplicado para $furnitureUuid en $currentTick")

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

        if (resolvedStage.isRemove() || generic.isLastStage(resolvedStage)) {
            if (resolvedStage.isRemove()) compatibility.handleRemove(player, event, loc, toolUsed, generic, resolvedStage)
            StageData.removeStageData(loc)
            return
        }

        StageData.saveStageData(loc, StageData(loc, resolvedStage.getStage() + 1, generic))
    }

    private val lastSequenceStageByLoc = mutableMapOf<Location, IStage>()

    private fun isStageObjectValid(
        compatibility: ICompatibility,
        loc: Location,
        stage: IStage
    ): Boolean {
        val expectedId = stage.getAdapterData()?.id

        return when (stage) {
            is IFurnitureStage -> compatibility.isValidFurniture(loc, expectedId)
            is IBlockStage -> compatibility.isValidBlock(loc, expectedId)
            else -> compatibility.isValid(loc, expectedId)
        }
    }

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

    private fun handleSequence(
        player: Player,
        compatibility: ICompatibility,
        loc: Location,
        toolUsed: LiveTool,
        generic: IGeneric,
        stage: Stage
    ) {
        val sequenceStages = stage.getSequenceStages()!!

        /*Bukkit.getConsoleSender().sendMessage(
            "[UM][SM] handleSequence start loc=$loc stageId=${stage.getAdapterData()?.id} uuid=${activeSequenceUuids[loc]} currentUuid=${compatibility.getFurnitureUUID(loc)}"
        )*/

        //Bukkit.getConsoleSender().sendMessage("[UM] Stage ${stage.getStage()} tiene ${sequenceStages.size} pasos de sequence.")

        val tasks = mutableListOf<BukkitTask>()
        activeSequences.add(loc)

        compatibility.getFurnitureUUID(loc)?.let { uuid ->
            activeSequenceUuids[loc] = uuid
        }

        lastSequenceStageByLoc[loc] = stage

        sequenceStages.forEach { (delayTicks, sequenceStage) ->
            val task = Bukkit.getScheduler().runTaskLater(core, Runnable {
                if (!activeSequences.contains(loc)) return@Runnable

                //val expectedUuid = compatibility.getFurnitureUUID(loc) //activeSequenceUuids[loc]

                /*Bukkit.getConsoleSender().sendMessage("[UM] sawebada "
                        +compatibility.isValid(loc,stage.getAdapterData()?.id))
                Bukkit.getConsoleSender().sendMessage("[UM] ñññ "
                        +compatibility.isValidUUID(loc,stage.getAdapterData()?.id,activeSequenceUuids[loc])
                )*/
                val prevStage = lastSequenceStageByLoc[loc]
                val expectedId = prevStage?.getAdapterData()?.id ?: stage.getAdapterData()?.id

                val isValidCurrentObject = when (prevStage) {
                    is IFurnitureStage -> compatibility.isValidFurniture(loc, expectedId)
                    is IBlockStage -> compatibility.isValidBlock(loc, expectedId)
                    else -> compatibility.isValid(loc, expectedId)
                }
                if (!isValidCurrentObject) {
                    //Bukkit.getConsoleSender().sendMessage("[UM] isValid false")
                    //Bukkit.getConsoleSender().sendMessage("[UM] asdasd "+loc.block.type)
                    cancelSequence(compatibility,loc)
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
                    if (!compatibility.isValidUUID(loc, expectedId, activeSequenceUuids[loc])) {
                        //Bukkit.getConsoleSender().sendMessage("[UM] isValidUUID=false mi webo gastronomico")
                        cancelSequence(compatibility, loc)
                        return@Runnable
                    }
                }

                //val adapterId = sequenceStage.getAdapterData()?.let { "${it.adapter?.type}:${it.id}" } ?: "null"
                //Bukkit.getConsoleSender().sendMessage("[UM] Ejecutando sequence del stage ${stage.getStage()} con delay $delayTicks ticks para furniture $adapterId")

                val fakeEvent = FakePlayerInteractEvent(player, loc.block, player.inventory.itemInMainHand, EquipmentSlot.HAND)

                if (prevStage is IFurnitureStage) {
                    val removed = compatibility.removeFurnitureByUUID(loc, activeSequenceUuids[loc])
                    if (!removed) {
                        compatibility.handleRemove(player, fakeEvent, loc, toolUsed, generic, prevStage)
                    }
                } else {
                    prevStage?.let {
                        compatibility.handleRemove(player, fakeEvent, loc, toolUsed, generic, it)
                    }
                }

                applySequenceStep(player, compatibility, fakeEvent, loc, toolUsed, generic, sequenceStage)

                if (delayTicks == sequenceStages.keys.maxOrNull()) {
                    //Bukkit.getConsoleSender().sendMessage("[UM] Secuencia finalizada en $loc.")
                    activeSequences.remove(loc)
                    activeSequenceUuids.remove(loc)
                    scheduledTasks.remove(loc)
                    lastSequenceStageByLoc.remove(loc)

                    val nextStage = stage.getStage() + 1
                    if (nextStage < generic.getStages().size) {
                        StageData.saveStageData(loc, StageData(loc, nextStage, generic))
                        compatibility.clearRemoving(loc.block.location)
                    } else {
                        StageData.removeStageData(loc)
                        compatibility.clearRemoving(loc.block.location)
                    }
                }
            }, delayTicks)
            tasks.add(task)
        }

        scheduledTasks[loc] = tasks
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
        if (!activeSequences.contains(loc)) {
            return
        }

        val resolvedStage = stage.resolveStage()

        resolvedStage.getAdapterData()?.let { adapterData ->
            if (isSimilarCompatibility(adapterData, compatibility)) {
                runPreApplyFeaturesForStage(player, compatibility, event, loc, toolUsed, generic, resolvedStage)
                runApplyFeaturesForStage(player, compatibility, event, loc, toolUsed, generic, resolvedStage)

                compatibility.handleSequenceStage(player, adapterData, event, loc, toolUsed, generic, resolvedStage)

                executeStageCommands(player, resolvedStage)
                lastSequenceStageByLoc[loc] = resolvedStage

                if (resolvedStage.isRemove()) {
                    compatibility.handleRemove(player, event, loc, toolUsed, generic, resolvedStage)
                    cancelSequence(compatibility, loc)
                    StageData.removeStageData(loc)
                    compatibility.clearRemoving(loc.block.location)
                    return
                }

                Bukkit.getScheduler().runTaskLater(UnearthMechanic.getInstance(), Runnable {
                    val newUuid = compatibility.getFurnitureUUID(loc)
                    activeSequenceUuids[loc] = newUuid
                }, 1L)
            } else {
                val c: ICompatibility =
                    getCompatibilityByAdapterId(adapterData)
                        ?: throw NullPointerException("Compatibility not found for $adapterData")

                runPreApplyFeaturesForStage(player, c, event, loc, toolUsed, generic, resolvedStage)
                runApplyFeaturesForStage(player, c, event, loc, toolUsed, generic, resolvedStage)

                c.handleSequenceStage(player, adapterData, event, loc, toolUsed, generic, resolvedStage)

                executeStageCommands(player, resolvedStage)
                lastSequenceStageByLoc[loc] = resolvedStage

                if (resolvedStage.isRemove()) {
                    c.handleRemove(player, event, loc, toolUsed, generic, resolvedStage)
                    cancelSequence(c, loc)
                    StageData.removeStageData(loc)
                    c.clearRemoving(loc.block.location)
                    return
                }

                Bukkit.getScheduler().runTaskLater(UnearthMechanic.getInstance(), Runnable {
                    val newUuid = c.getFurnitureUUID(loc)
                    activeSequenceUuids[loc] = newUuid
                }, 1L)
            }
        }
    }

    fun cancelSequence(compatibility: ICompatibility, loc: Location) {
        scheduledTasks[loc]?.forEach { it.cancel() }
        scheduledTasks.remove(loc)
        activeSequences.remove(loc)
        activeSequenceUuids.remove(loc)
        lastSequenceStageByLoc.remove(loc)
        //Bukkit.getConsoleSender().sendMessage("[UM] Secuencia cancelada en $loc.")

        compatibility.getFurnitureUUID(loc).let { furnitureuuid ->
            furnitureuuid?.let { compatibility.clearRemoving(loc.block.location) }
        }
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