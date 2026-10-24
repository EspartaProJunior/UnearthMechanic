package dev.wuason.unearthMechanic.system

import com.tcoded.folialib.wrapper.task.WrappedTask
import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.Stage
import dev.wuason.unearthMechanic.UnearthMechanicPlugin
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import dev.wuason.unearthMechanic.utils.DebugSettings
import dev.wuason.unearthMechanic.utils.FoliaUtils
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event

class DelayTask(
    private val stageManager: StageManager,
    private val player: Player,
    private val compatibility: ICompatibility,
    private val event: Event,
    private val loc: Location,
    private val toolUsed: LiveTool,
    private val generic: IGeneric,
    private val stage: Stage,
    private val validation: Validation,
    private val currentStageData: StageData? = null
) {

    private var tick: Long = 0
    private var task: WrappedTask? = null
    private var cancelled = false
    private val debugDelayTask get() = dev.wuason.unearthMechanic.utils.DebugSettings.isEnabled()

    private fun dbgDelay(message: String) {
        if (!debugDelayTask) return
        UnearthMechanicPlugin.getInstance().logger.info("[UM-DELAY] $message")
    }

    private fun Location.debugShort(): String {
        val worldName = world?.name ?: "null"
        return "$worldName:${blockX},${blockY},${blockZ}"
    }

    fun start() {
        val keyLoc = loc.block.location

        stageManager.addTransitioning(keyLoc)
        (compatibility as? dev.wuason.unearthMechanic.system.compatibilities.FurnitureTransitionLifecycle)
            ?.onFurnitureDelayStarted(event, stage.getMaxCorrectDelay(toolUsed))
        if (dev.wuason.unearthMechanic.utils.DebugSettings.isEnabled()) dbgDelay(
            "start loc=${keyLoc.debugShort()} event=${event.javaClass.simpleName} " +
                    "tool=${toolUsed.getITool().getAdapterData()} generic=${generic.getId()} " +
                    "stage=${stage.getStage()} maxDelay=${stage.getMaxCorrectDelay(toolUsed)}"
        )

        task = FoliaUtils.runTimerAtLocation(
            keyLoc,
            0L,
            1L
        ) {
            runCatching { run() }.onFailure { error -> throw error }
        }

        task?.let {
            stageManager.getDelays()[keyLoc] = it
            if (DebugSettings.isEnabled()) dbgDelay("registered loc=${keyLoc.debugShort()} inDelays=${stageManager.getDelays().containsKey(keyLoc)}")
        }
    }

    private fun run() {
        if (cancelled) {
            if (DebugSettings.isEnabled()) dbgDelay("run skipped because already cancelled loc=${loc.block.location.debugShort()} tick=$tick")
            return
        }

        if (DebugSettings.isEnabled()) dbgDelay("run loc=${loc.block.location.debugShort()} tick=$tick")

        if (!ownsFurnitureTransition("before_process")) {
            cancel()
            return
        }

        if (!check()) {
            if (DebugSettings.isEnabled()) dbgDelay("run check failed, cancelling loc=${loc.block.location.debugShort()} tick=$tick")
            cancel()
            return
        }

        if (!ownsFurnitureTransition("before_process")) {
            cancel()
            return
        }

        stageManager.onProcessStage(
            tick++,
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

        if (tick >= stage.getMaxCorrectDelay(toolUsed)) {
            cancel()
        }
    }

    private fun check(): Boolean {
        val validationOk = validation.validate()
        val toolOk = toolUsed.isValid()
        val stageDataOk = StageData.compare(StageData(loc, stage.getStage(), generic), loc)
        val result = validationOk && toolOk && stageDataOk

        if (DebugSettings.isEnabled()) dbgDelay(
            "check loc=${loc.block.location.debugShort()} tick=$tick result=$result " +
                    "validation=$validationOk tool=$toolOk stageData=$stageDataOk " +
                    "hasStageData=${StageData.hasStageData(loc)} currentStageData=${currentStageData != null}"
        )

        return result
    }

    private fun ownsFurnitureTransition(point: String): Boolean {
        val alive = (compatibility as? dev.wuason.unearthMechanic.system.compatibilities.FurnitureTransitionLifecycle)
            ?.checkFurnitureTransitionOwnership(event, point) ?: true
        if (!alive && dev.wuason.unearthMechanic.utils.DebugSettings.isEnabled()) dbgDelay(
            "stale ownership point=$point loc=${loc.block.location.debugShort()} tick=$tick"
        )
        return alive
    }

    fun cancel() {
        if (cancelled) return
        cancelled = true

        val keyLoc = loc.block.location
        dbgDelay("cancel loc=${keyLoc.debugShort()} tick=$tick")

        stageManager.getDelays().remove(keyLoc)
        stageManager.removeTransitioning(keyLoc)

        task?.cancel()
        task = null

        (compatibility as? dev.wuason.unearthMechanic.system.compatibilities.FurnitureTransitionLifecycle)
            ?.onFurnitureDelayFinished(event)
    }
}