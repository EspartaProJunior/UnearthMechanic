package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior

import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.utils.FoliaUtils
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.UpdateFlags
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import org.bukkit.Bukkit
import org.bukkit.Location
import java.util.concurrent.ConcurrentHashMap

abstract class BaseConnectBlockBehavior<T : Enum<T>>(
    customBlock: BlockDefinition,
    protected val connectProperty: Property<T>
) : BukkitBlockBehavior(customBlock) {

    private val pendingUpdates = ConcurrentHashMap<String, Boolean>()
    private val batchUpdating = ThreadLocal.withInitial { false }

    protected fun isBatchUpdating(): Boolean = batchUpdating.get() == true

    protected fun runBatch(action: () -> Unit) {
        batchUpdating.set(true)
        try {
            action()
        } finally {
            batchUpdating.set(false)
        }
    }

    protected fun isSameBlock(world: World, pos: BlockPos): Boolean {
        val wrapper = world.getBlockState(pos) ?: return false
        return wrapper.ownerId() == block().id()
    }

    protected fun setBlockState(world: World, pos: BlockPos, state: ImmutableBlockState) {
        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return
        BukkitAdaptor.adapt(bukkitWorld).setBlockState(
            pos.x(),
            pos.y(),
            pos.z(),
            state,
            UpdateFlags.UPDATE_ALL
        )
    }

    protected fun scheduleNeighborUpdate(world: World, pos: BlockPos, delay: Long = 1L) {
        if (isBatchUpdating()) return

        val key = "${world.name()}:${pos.x()},${pos.y()},${pos.z()}"
        if (pendingUpdates.putIfAbsent(key, true) != null) return

        val bukkitLoc = toBukkitLocation(world, pos)
        if (bukkitLoc == null) {
            pendingUpdates.remove(key)
            return
        }

        FoliaUtils.runLater(delay) {
            FoliaUtils.runAtLocation(bukkitLoc) {
                try {
                    updateNeighbors(world, pos)
                } finally {
                    pendingUpdates.remove(key)
                }
            }
        }
    }

    private fun toBukkitLocation(world: World, pos: BlockPos): Location? {
        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return null

        return Location(
            bukkitWorld,
            pos.x().toDouble(),
            pos.y().toDouble(),
            pos.z().toDouble()
        )
    }

    protected fun scheduleNeighborUpdateFromArgs(args: Array<Any>, delay: Long = 1L) {
        val (world, pos) = resolveWorldAndPos(args.getOrNull(1), args.getOrNull(2)) ?: return
        scheduleNeighborUpdate(world, pos, delay)
    }

    protected fun resolveUpdateShapeContext(args: Array<Any>): Pair<World, BlockPos>? {
        return when {
            args.size >= 8 -> resolveWorldAndPos(args.getOrNull(1), args.getOrNull(3))
            args.size >= 5 -> resolveWorldAndPos(args.getOrNull(3), args.getOrNull(4))
            else -> null
        }
    }

    private fun resolveWorldAndPos(rawWorld: Any?, rawPos: Any?): Pair<World, BlockPos>? {
        val nmsWorld = rawWorld ?: return null
        val nmsPos = rawPos ?: return null

        val craftWorld = Bukkit.getWorlds().firstOrNull { world ->
            val handle = world.javaClass.getMethod("getHandle").invoke(world)
            handle == nmsWorld
        } ?: return null

        val x = nmsPos.javaClass.getMethod("getX").invoke(nmsPos) as Int
        val y = nmsPos.javaClass.getMethod("getY").invoke(nmsPos) as Int
        val z = nmsPos.javaClass.getMethod("getZ").invoke(nmsPos) as Int

        return BukkitAdaptor.adapt(craftWorld) to BlockPos(x, y, z)
    }

    protected abstract fun updateNeighbors(world: World, origin: BlockPos)
}