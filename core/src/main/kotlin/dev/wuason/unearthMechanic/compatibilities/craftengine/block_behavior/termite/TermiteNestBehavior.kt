package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite

import dev.wuason.unearthMechanic.compatibilities.craftengine.types.TermiteNestStage
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import java.util.concurrent.ThreadLocalRandom

class TermiteNestBehavior(
    customBlock: BlockDefinition,
    private val stageProperty: Property<TermiteNestStage>,
    private val maxTermites: Int,
    private val absorbRadius: Double,
    private val releaseChance: Int
) : BukkitBlockBehavior(customBlock) {

    override fun randomTick(thisBlock: Any, args: Array<Any>) {
        val location = locationFromNmsArgs(args) ?: return
        val block = location.block
        val key = TermiteKeys.key(location)
        var changed = false

        val nearbyTermites = location.world
            ?.getNearbyEntities(location.clone().add(0.5, 0.5, 0.5), absorbRadius, absorbRadius, absorbRadius)
            ?.filterIsInstance<LivingEntity>()
            ?.filter { MythicTermites.isTermite(it) }
            ?.filterNot { TermiteGameplay.isRecentlyReleased(it) }
            ?: return

        for (termite in nearbyTermites) {
            if (TermiteDataStore.addTermites(key, 1, maxTermites) <= 0) break
            termite.remove()
            changed = true
        }

        if (ThreadLocalRandom.current().nextInt(releaseChance.coerceAtLeast(1)) == 0) {
            if (TermiteDataStore.takeTermite(key)) {
                val spawned = TermiteGameplay.spawnReleasedTermite(block, key, TermiteDataStore.peek(key)?.ownerUuid)
                if (spawned != null) TermiteGameplay.markRecentlyReleased(spawned)
                changed = true
            }
        }

        if (changed) TermiteGameplay.updateNestStage(block)
    }

    private fun locationFromNmsArgs(args: Array<Any>, worldIndex: Int = 1, posIndex: Int = 2): Location? {
        val nmsWorld = args.getOrNull(worldIndex) ?: return null
        val nmsPos = args.getOrNull(posIndex) ?: return null

        val craftWorld = Bukkit.getWorlds().firstOrNull { world ->
            val handle = world.javaClass.getMethod("getHandle").invoke(world)
            handle == nmsWorld
        } ?: return null

        val x = nmsPos.javaClass.getMethod("getX").invoke(nmsPos) as Int
        val y = nmsPos.javaClass.getMethod("getY").invoke(nmsPos) as Int
        val z = nmsPos.javaClass.getMethod("getZ").invoke(nmsPos) as Int

        return Location(craftWorld, x.toDouble(), y.toDouble(), z.toDouble())
    }

    companion object {
        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<TermiteNestBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): TermiteNestBehavior {
                val prop = block.getProperty("stage")
                    ?: throw IllegalArgumentException("Missing 'stage' property")

                TermiteGameplay.configureFromBehavior(section)

                @Suppress("UNCHECKED_CAST")
                return TermiteNestBehavior(
                    customBlock = block,
                    stageProperty = prop as Property<TermiteNestStage>,
                    maxTermites = section.getInt("max-termites", 1),
                    absorbRadius = section.getDouble("absorb-radius", 2.5),
                    releaseChance = section.getInt("release-chance", 16)
                )
            }
        }
    }
}
