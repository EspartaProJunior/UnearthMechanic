package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior

import dev.wuason.unearthMechanic.compatibilities.craftengine.types.SofaTile
import dev.wuason.unearthMechanic.compatibilities.craftengine.types.WindowTile
import net.momirealms.craftengine.bukkit.api.BukkitAdaptors
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.bukkit.nms.FastNMS
import net.momirealms.craftengine.bukkit.world.BukkitWorld
import net.momirealms.craftengine.core.block.CustomBlock
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.UpdateOption
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.properties.Property
import net.momirealms.craftengine.core.item.context.BlockPlaceContext
import net.momirealms.craftengine.core.registry.Holder
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import org.bukkit.Bukkit
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

class SofaConnectTileBehavior(
    customBlock: CustomBlock,
    private val tileProperty: Property<SofaTile>
) : BukkitBlockBehavior(customBlock) {


    companion object {
        private val pending = ConcurrentHashMap<String, Boolean>()
        private fun k(w: World, p: BlockPos) = "${'$'}{w.name()}:${'$'}{p.x()},${'$'}{p.y()},${'$'}{p.z()}"


        private val inBatch: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }


        val FACTORY = Factory()
        class Factory : BlockBehaviorFactory {
            override fun create(block: CustomBlock, arguments: Map<String, Any>): SofaConnectTileBehavior {
                val prop = block.getProperty("tile")
                    ?: throw IllegalArgumentException("Missing 'tile' property")
                @Suppress("UNCHECKED_CAST")
                val tileProperty = prop as Property<SofaTile>
                return SofaConnectTileBehavior(block, tileProperty)
            }
        }
    }

    private fun isSame(world: World, pos: BlockPos): Boolean {
        val wrap = world.getBlockAt(pos).blockState() ?: return false
        val neighborStr = try { wrap.ownerId()?.toString() } catch (_: Throwable) { null }
        val selfStr = try { customBlock.id().asString() } catch (_: Throwable) { customBlock.id().toString() }
        return neighborStr != null && neighborStr == selfStr
    }

    private fun ceStateId(state: ImmutableBlockState?): String? {
        if (state == null) return null
        val ref = state.owner() as? Holder.Reference<CustomBlock> ?: return null
        return try { ref.key().location().asString() } catch (_: Throwable) { null }
    }

    private fun ceFacing(state: ImmutableBlockState?, fallback: String = "south"): String {
        if (state == null) return fallback
        return try {
            state.propertyEntries().entries
                .firstOrNull { it.key.name() == "facing" }
                ?.value?.toString()?.lowercase() ?: fallback
        } catch (_: Throwable) { fallback }
    }

    /**
     * Asigna SOLO: single, left, middle, right
     * e invierte L/R según la orientación (facing).
     */
    private fun calculateTile(world: World, pos: BlockPos): SofaTile {
        val east = isSame(world, pos.offset( 1, 0, 0)) // +X
        val west = isSame(world, pos.offset(-1, 0, 0)) // -X
        val north = isSame(world, pos.offset( 0, 0,-1)) // -Z
        val south = isSame(world, pos.offset( 0, 0, 1)) // +Z


        val hasX = east || west
        val hasZ = north || south


        // If there is any horizontal run in either axis, decide L/M/R ignoring vertical "families".
        return when {
            (east && west) || (north && south) -> SofaTile.middle
            east || south -> SofaTile.right // neighbor to the +axis side → this becomes "right"
            west || north -> SofaTile.left // neighbor to the -axis side → this becomes "left"
            else -> SofaTile.single
        }
    }


    // ================== Hooks ==================
    override fun updateShape(thisBlock: Any, args: Array<Any>, superMethod: Callable<Any>): Any {
        val world = args.getOrNull(3) as? World ?: return superMethod.call()
        val pos = args.getOrNull(4) as? BlockPos ?: return superMethod.call()


        val optState = net.momirealms.craftengine.bukkit.util.BlockStateUtils.getOptionalCustomBlockState(args[0])
            ?: return superMethod.call()
        val state = optState.get()
        val newTile = calculateTile(world, pos)
        return state.with(tileProperty, newTile)
            .customBlockState()
            .literalObject()
    }

    override fun updateStateForPlacement(context: BlockPlaceContext, state: ImmutableBlockState): ImmutableBlockState {
        val world = context.level
        val pos = context.clickedPos
        val newTile = calculateTile(world, pos)
        return state.with(tileProperty, newTile)
    }

    private fun updateNeighbors(world: World, origin: BlockPos) {
// Solo recalcula este bloque y sus 4 vecinos cardinales si son del mismo tipo.
        if (world !is BukkitWorld) return
        val bWorld = Bukkit.getWorld(world.name()) ?: return


        val targets = arrayOf(
            origin,
            origin.offset( 1, 0, 0), // east
            origin.offset(-1, 0, 0), // west
            origin.offset( 0, 0, 1), // south
            origin.offset( 0, 0,-1) // north
        )


        inBatch.set(true)
        try {
            for (p in targets) {
                val state = world.getBlockAt(p).customBlockState() ?: continue
                // Para vecinos: solo si son el mismo bloque; para el origen, siempre.
                if (p != origin && !isSame(world, p)) continue


                val newTile = calculateTile(world, p)
                val newState = try { state.with(tileProperty, newTile) } catch (_: Throwable) { continue }
                BukkitAdaptors.adapt(bWorld).setBlockAt(p.x(), p.y(), p.z(), newState, UpdateOption.UPDATE_ALL.flags())
            }
        } finally {
            inBatch.set(false)
        }
    }

    private fun scheduleFromNMS(args: Array<Any>, delay: Long) {
        val nmsWorld = args.getOrNull(1) ?: return
        val nmsPos   = args.getOrNull(2) ?: return

        val craftWorld = Bukkit.getWorlds().firstOrNull { w ->
            val handle = w.javaClass.getMethod("getHandle").invoke(w)
            handle == nmsWorld
        } ?: return

        val x = nmsPos.javaClass.getMethod("getX").invoke(nmsPos) as Int
        val y = nmsPos.javaClass.getMethod("getY").invoke(nmsPos) as Int
        val z = nmsPos.javaClass.getMethod("getZ").invoke(nmsPos) as Int

        val ceWorld = BukkitAdaptors.adapt(craftWorld)
        val cePos   = BlockPos(x, y, z)
        scheduleNeighborUpdate(ceWorld, cePos, delay)
    }

    override fun onPlace(thisBlock: Any, args: Array<Any>, superMethod: Callable<Any>) {
        if (inBatch.get() == true) { superMethod.call(); return }
        val nmsWorld = args.getOrNull(1) ?: run { superMethod.call(); return }
        val nmsPos = args.getOrNull(2) ?: run { superMethod.call(); return }


        val craftWorld = Bukkit.getWorlds().firstOrNull { w ->
            val handle = w.javaClass.getMethod("getHandle").invoke(w)
            handle == nmsWorld
        } ?: run { superMethod.call(); return }


        val x = nmsPos.javaClass.getMethod("getX").invoke(nmsPos) as Int
        val y = nmsPos.javaClass.getMethod("getY").invoke(nmsPos) as Int
        val z = nmsPos.javaClass.getMethod("getZ").invoke(nmsPos) as Int


        val ceWorld = BukkitAdaptors.adapt(craftWorld)
        val cePos = BlockPos(x, y, z)
        scheduleFromNMS(args, 1L)
        //scheduleNeighborUpdate(ceWorld, cePos, 1L)
        superMethod.call()
    }

    override fun onRemove(thisBlock: Any, args: Array<Any>, superMethod: Callable<Any>) {
        if (inBatch.get() == true) { superMethod.call(); return }
        scheduleFromNMS(args, 1L)
        handleRemoval(thisBlock, args); superMethod.call()
    }


    override fun affectNeighborsAfterRemoval(thisBlock: Any, args: Array<Any>, superMethod: Callable<Any>) {
        if (inBatch.get() == true) { superMethod.call(); return }
        scheduleFromNMS(args, 1L)
        handleRemoval(thisBlock, args); superMethod.call()
    }


    private fun handleRemoval(thisBlock: Any, args: Array<Any>) {
        val nmsWorld = args.getOrNull(1) ?: return
        val nmsPos = args.getOrNull(2) ?: return
        FastNMS.INSTANCE.`method$ScheduledTickAccess$scheduleBlockTick`(nmsWorld, nmsPos, thisBlock, 2)
    }

    private fun scheduleNeighborUpdate(world: World, pos: BlockPos, delay: Long) {
        if (inBatch.get() == true) return
        val key = k(world, pos)
        if (pending.putIfAbsent(key, true) != null) return
        val plugin = Bukkit.getPluginManager().getPlugin("UnearthMechanic") ?: return
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            try { updateNeighbors(world, pos) } finally { pending.remove(key) }
        }, delay)
    }
}


