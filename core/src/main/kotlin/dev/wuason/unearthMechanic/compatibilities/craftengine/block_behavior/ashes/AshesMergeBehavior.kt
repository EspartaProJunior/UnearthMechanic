package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior

import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.ashes.AshesEnvironmentListener
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.UpdateFlags
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.properties.Property
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.scheduler.BukkitRunnable
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

class AshesMergeBehavior(
    customBlock: BlockDefinition,
    private val layersProperty: Property<Int>
) : BukkitBlockBehavior(customBlock) {

    companion object {
        private const val MAX_LAYERS = 8
        private const val MERGE_WINDOW_TICKS = 30L

        private val pendingWindows = ConcurrentHashMap<String, Boolean>()

        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<AshesMergeBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): AshesMergeBehavior {
                val prop = block.getProperty("layers")
                    ?: throw IllegalArgumentException("Missing 'layers' property")
                @Suppress("UNCHECKED_CAST")
                return AshesMergeBehavior(block, prop as Property<Int>)
            }
        }

        private fun windowKey(world: World, pos: BlockPos): String {
            return "${world.name()}:${pos.x()},${pos.y()},${pos.z()}"
        }
    }

    override fun onPlace(thisBlock: Any, args: Array<Any>) {
        super.onPlace(thisBlock, args)

        val nmsWorld = args.getOrNull(1) ?: return
        val nmsPos = args.getOrNull(2) ?: return

        val craftWorld = Bukkit.getWorlds().firstOrNull { world ->
            val handle = world.javaClass.getMethod("getHandle").invoke(world)
            handle == nmsWorld
        } ?: return

        val x = nmsPos.javaClass.getMethod("getX").invoke(nmsPos) as Int
        val y = nmsPos.javaClass.getMethod("getY").invoke(nmsPos) as Int
        val z = nmsPos.javaClass.getMethod("getZ").invoke(nmsPos) as Int

        val ceWorld = BukkitAdaptor.adapt(craftWorld)
        val pos = BlockPos(x, y, z)

        syncTrackedAsh(craftWorld, pos)
        startMergeWindow(ceWorld, pos)
    }

    private fun ashesEnvironmentListener(): AshesEnvironmentListener? {
        val plugin = Bukkit.getPluginManager().getPlugin("UnearthMechanic") as? UnearthMechanic ?: return null
        return runCatching { plugin.ashesEnvironmentListener }.getOrNull()
    }

    private fun trackAsh(world: org.bukkit.World, pos: BlockPos, layers: Int) {
        ashesEnvironmentListener()?.trackAsh(
            Location(world, pos.x().toDouble(), pos.y().toDouble(), pos.z().toDouble()),
            layers
        )
    }

    private fun syncTrackedAsh(world: org.bukkit.World, pos: BlockPos) {
        val ceWorld = BukkitAdaptor.adapt(world)
        val block = ceWorld.getBlock(pos)
        val wrapper = block.blockState()

        if (wrapper == null || wrapper.ownerId() != blockDefinition.id()) {
            trackAsh(world, pos, 0)
            return
        }

        val state = block.customBlockState() ?: run {
            trackAsh(world, pos, 0)
            return
        }

        val layers = try {
            state.get(layersProperty, 1)
        } catch (_: Throwable) {
            1
        }

        trackAsh(world, pos, layers)
    }

    override fun updateShape(thisBlock: Any, args: Array<Any>): Any {
        val result = super.updateShape(thisBlock, args)

        val world = args.getOrNull(3) as? World ?: return result
        val pos = args.getOrNull(4) as? BlockPos ?: return result

        val bukkitWorld = Bukkit.getWorld(world.name())
        if (bukkitWorld != null) {
            syncTrackedAsh(bukkitWorld, pos)
        }

        startMergeWindow(world, pos)
        return result
    }

    private fun startMergeWindow(world: World, center: BlockPos) {
        val plugin = Bukkit.getPluginManager().getPlugin("UnearthMechanic") ?: return
        val key = windowKey(world, center)

        if (pendingWindows.putIfAbsent(key, true) != null) return

        object : BukkitRunnable() {
            var lived = 0L

            override fun run() {
                try {
                    mergeAround(world, center)
                    lived++

                    if (lived >= MERGE_WINDOW_TICKS) {
                        cancel()
                        pendingWindows.remove(key)
                    }
                } catch (_: Throwable) {
                    cancel()
                    pendingWindows.remove(key)
                }
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    private fun mergeAround(world: World, center: BlockPos) {
        var changed: Boolean
        var safety = 0

        do {
            changed = false

            changed = mergeWithBelow(world, center.offset(0, 1, 0)) || changed
            changed = mergeWithBelow(world, center) || changed
            changed = mergeWithBelow(world, center.offset(0, -1, 0)) || changed

            safety++
        } while (changed && safety < 8)
    }

    private fun mergeWithBelow(world: World, pos: BlockPos): Boolean {
        val selfBlock = world.getBlock(pos)
        val selfWrapper = selfBlock.blockState() ?: return false
        if (selfWrapper.ownerId() != blockDefinition.id()) return false

        val belowPos = pos.offset(0, -1, 0)
        val belowBlock = world.getBlock(belowPos)
        val belowWrapper = belowBlock.blockState() ?: return false
        if (belowWrapper.ownerId() != blockDefinition.id()) return false

        val selfState = selfBlock.customBlockState() ?: return false
        val belowState = belowBlock.customBlockState() ?: return false

        val selfLayers = try {
            selfState.get(layersProperty, 1)
        } catch (_: Throwable) {
            1
        }

        val belowLayers = try {
            belowState.get(layersProperty, 1)
        } catch (_: Throwable) {
            1
        }

        if (selfLayers <= 0) return false
        if (belowLayers >= MAX_LAYERS) return false

        val total = selfLayers + belowLayers
        val newBelowLayers = minOf(MAX_LAYERS, total)
        val overflow = total - newBelowLayers

        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return false
        val ceWorld = BukkitAdaptor.adapt(bukkitWorld)

        val newBelowState = belowState.with(layersProperty, newBelowLayers)
        ceWorld.setBlockState(
            belowPos.x(),
            belowPos.y(),
            belowPos.z(),
            newBelowState,
            UpdateFlags.UPDATE_ALL
        )
        trackAsh(bukkitWorld, belowPos, newBelowLayers)

        if (overflow <= 0) {
            val removed = CraftEngineBlocks.remove(
                bukkitWorld.getBlockAt(pos.x(), pos.y(), pos.z())
            )
            if (removed) {
                trackAsh(bukkitWorld, pos, 0)
            } else {
                syncTrackedAsh(bukkitWorld, pos)
            }
        } else {
            val newSelfState = selfState.with(layersProperty, overflow)
            ceWorld.setBlockState(
                pos.x(),
                pos.y(),
                pos.z(),
                newSelfState,
                UpdateFlags.UPDATE_ALL
            )
            trackAsh(bukkitWorld, pos, overflow)
        }

        return true
    }
}


