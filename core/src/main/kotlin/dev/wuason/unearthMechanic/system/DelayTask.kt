package dev.wuason.unearthMechanic.system

import com.tcoded.folialib.wrapper.task.WrappedTask
import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.Stage
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
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

    fun start() {
        val keyLoc = loc.block.location

        stageManager.addTransitioning(keyLoc)

        task = FoliaUtils.runTimerAtLocation(
            keyLoc,
            0L,
            1L
        ) {
            run()
        }

        task?.let {
            stageManager.getDelays()[keyLoc] = it
        }
    }

    private fun run() {
        if (cancelled) return

        if (!check()) {
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
        return validation.validate()
                && toolUsed.isValid()
                && StageData.compare(StageData(loc, stage.getStage(), generic), loc)
    }

    fun cancel() {
        if (cancelled) return
        cancelled = true

        val keyLoc = loc.block.location

        stageManager.getDelays().remove(keyLoc)
        stageManager.removeTransitioning(keyLoc)

        task?.cancel()
        task = null
    }
}