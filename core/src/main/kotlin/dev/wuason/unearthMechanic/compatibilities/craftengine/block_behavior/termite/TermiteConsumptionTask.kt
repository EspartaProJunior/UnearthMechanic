package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite

import com.tcoded.folialib.wrapper.task.WrappedTask
import dev.wuason.unearthMechanic.utils.FoliaUtils
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import java.util.concurrent.ThreadLocalRandom

object TermiteConsumptionTask {
    @Volatile private var task: WrappedTask? = null

    fun start(listener: TermiteListener, periodTicks: Long = 20L * 30L) {
        if (task != null) return

        task = FoliaUtils.runTimer(periodTicks, periodTicks) {
            for (world in Bukkit.getWorlds()) {
                for (entity in world.livingEntities) {
                    if (!MythicTermites.isTermite(entity)) continue
                    tryConsumeNearbyWood(entity, listener)
                }
            }
        }
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    private fun tryConsumeNearbyWood(termite: LivingEntity, listener: TermiteListener) {
        val loc = termite.location
        val world = loc.world ?: return
        if (ThreadLocalRandom.current().nextInt(4) != 0) return

        val candidates = ArrayList<org.bukkit.block.Block>()
        for (dx in -2..2) for (dy in -1..1) for (dz in -2..2) {
            val block = world.getBlockAt(loc.blockX + dx, loc.blockY + dy, loc.blockZ + dz)
            if (block.type.name.endsWith("_LOG") || block.type.name.endsWith("_WOOD")) {
                candidates.add(block)
            }
        }

        val target = candidates.randomOrNull() ?: return
        listener.consumeWoodBlock(target)
    }
}
