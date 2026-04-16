package dev.wuason.unearthMechanic.system

import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.Stage
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.scheduler.BukkitRunnable

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
) : BukkitRunnable() {

    private var tick: Long = 0

    fun start() {
        stageManager.addTransitioning(loc)
        stageManager.getDelays()[loc] = runTaskTimer(UnearthMechanic.getInstance(), 0, 1)
    }

    override fun run() {
        if (!check()) {
            cancel()
            return
        }
        stageManager.onProcessStage(tick++, player, compatibility,
            event, loc, toolUsed, generic, stage, validation,currentStageData)
        if (tick >= stage.getMaxCorrectDelay(toolUsed)) {
            cancel()
        }
    }

    private fun check(): Boolean {
        return validation.validate() && toolUsed.isValid() && StageData.compare(StageData(loc, stage.getStage(), generic), loc) //TEST this
    }

    override fun cancel() {
        stageManager.getDelays().remove(loc)
        stageManager.removeTransitioning(loc)
        super.cancel()
    }

}