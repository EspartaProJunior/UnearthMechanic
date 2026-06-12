package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.meerkat_cache

import com.tcoded.folialib.wrapper.task.WrappedTask
import dev.wuason.unearthMechanic.utils.FoliaUtils

object MeerkatBurrowTask {
    private var task: WrappedTask? = null

    fun start(periodTicks: Long = 20L * 10L) {
        if (task != null) return
        schedule(periodTicks)
    }

    private fun schedule(periodTicks: Long) {
        task = FoliaUtils.runLater(periodTicks) {
            MeerkatCacheGameplay.releaseDayBurrows()
            task = null
            schedule(periodTicks)
        }
    }

    fun stop() {
        task?.cancel()
        task = null
    }
}
