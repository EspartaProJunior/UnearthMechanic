package dev.wuason.unearthMechanic.utils

import com.tcoded.folialib.FoliaLib
import com.tcoded.folialib.wrapper.task.WrappedTask
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

object FoliaUtils {

    private lateinit var foliaLib: FoliaLib

    val isInitialized: Boolean
        get() = ::foliaLib.isInitialized

    fun init(plugin: Plugin) {
        if (isInitialized) return
        foliaLib = FoliaLib(plugin)
    }

    fun shutdown() {
        if (!isInitialized) return
        foliaLib.scheduler.cancelAllTasks()
    }

    fun isFolia(): Boolean {
        return isInitialized && foliaLib.isFolia
    }

    fun isPaper(): Boolean {
        return isInitialized && foliaLib.isPaper
    }

    fun isSpigot(): Boolean {
        return isInitialized && foliaLib.isSpigot
    }

    /**
     * Folia: GlobalRegionScheduler.
     * Spigot/Paper: main thread.
     *
     * Do not use for players, entities, or blocks.
     */
    fun runNextTick(block: () -> Unit) {
        scheduler().runNextTick(asTask(block))
    }

    /**
     * Always async.
     */
    fun runAsync(block: () -> Unit) {
        scheduler().runAsync(asTask(block))
    }

    /**
     * Folia: GlobalRegionScheduler.
     * Spigot/Paper: main thread.
     *
     * Do not use for players, entities, or blocks.
     */
    fun runTimer(
        delayTicks: Long,
        periodTicks: Long,
        block: () -> Unit
    ): WrappedTask {
        return scheduler().runTimer(
            asRunnable(block),
            delayTicks,
            periodTicks
        )
    }

    /**
     * Folia: GlobalRegionScheduler.
     * Spigot/Paper: main thread.
     *
     * Do not use for players, entities, or blocks.
     */
    fun runLater(
        delayTicks: Long,
        block: () -> Unit
    ): WrappedTask {
        return scheduler().runLater(
            asRunnable(block),
            delayTicks
        )
    }

    /**
     * Folia: GlobalRegionScheduler.
     * Spigot/Paper: main thread.
     *
     * Do not use for players, entities, or blocks.
     */
    fun runTimerAtLocation(
        location: Location,
        delayTicks: Long,
        periodTicks: Long,
        block: () -> Unit
    ): WrappedTask {
        return scheduler().runTimer(
            asRunnable {
                runAtLocation(location) {
                    block()
                }
            },
            delayTicks,
            periodTicks
        )
    }

    /**
     * Always async.
     */
    fun runTimerAsync(
        delayTicks: Long,
        periodTicks: Long,
        block: () -> Unit
    ) {
        scheduler().runTimerAsync(
            asTask(block),
            delayTicks,
            periodTicks
        )
    }

    /**
     * Asynchronous timer using TimeUnit.
     *
     * Example:
     * 20 ticks = 1000 ms
     * 20 * 50L = 1000L
     */
    fun runTimerAsync(
        delay: Long,
        period: Long,
        timeUnit: TimeUnit,
        block: () -> Unit
    ) {
        scheduler().runTimerAsync(
            asTask(block),
            delay,
            period,
            timeUnit
        )
    }

    /**
     * Folia: runs in the correct region of that Location.
     * Spigot/Paper: main thread.
     *
     * Use for block changes, furniture by location, etc.
     */
    fun runAtLocation(
        location: Location,
        block: () -> Unit
    ) {
        scheduler().runAtLocation(
            location,
            asTask(block)
        )
    }

    /**
     * Folia: runs in the correct EntityScheduler.
     * Spigot/Paper: main thread.
     *
     * Use for players, entities, player inventory, entity effects, etc.
     */
    fun runAtEntity(
        entity: Entity,
        block: () -> Unit
    ) {
        scheduler().runAtEntity(
            entity,
            asTask(block)
        )
    }

    /**
     * Asynchronous teleport compatible with Folia/Paper/Spigot.
     */
    fun teleportAsync(
        entity: Entity,
        location: Location,
        cause: PlayerTeleportEvent.TeleportCause = PlayerTeleportEvent.TeleportCause.PLUGIN
    ): CompletableFuture<Boolean> {
        return scheduler().teleportAsync(entity, location, cause)
    }

    private fun asTask(block: () -> Unit): Consumer<WrappedTask> {
        return Consumer { block() }
    }

    private fun asRunnable(block: () -> Unit): Runnable {
        return Runnable { block() }
    }

    private fun scheduler() =
        if (isInitialized) {
            foliaLib.scheduler
        } else {
            error("FoliaUtils has not been initialized. Call FoliaUtils.init(plugin) in onEnable().")
        }
}