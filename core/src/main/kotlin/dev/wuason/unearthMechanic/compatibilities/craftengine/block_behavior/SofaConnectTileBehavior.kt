package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior

import dev.wuason.unearthMechanic.compatibilities.craftengine.types.SofaTile
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.bukkit.util.BlockStateUtils
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.UpdateFlags
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.util.Direction
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import net.momirealms.craftengine.core.world.context.BlockPlaceContext
import org.bukkit.Bukkit
import java.util.concurrent.ConcurrentHashMap

class SofaConnectTileBehavior(
    customBlock: BlockDefinition,
    private val tileProperty: Property<SofaTile>,
    private val facingProperty: Property<Direction>?,
    private val axisProperty: Property<*>?
) : BukkitBlockBehavior(customBlock) {

    companion object {
        private val pending = ConcurrentHashMap<String, Boolean>()
        private val inBatch: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

        private fun k(w: World, p: BlockPos) = "${w.name()}:${p.x()},${p.y()},${p.z()}"

        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<SofaConnectTileBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): SofaConnectTileBehavior {
                val tileProp = block.getProperty("tile")
                    ?: throw IllegalArgumentException("Missing 'tile' property")

                @Suppress("UNCHECKED_CAST")
                val tileProperty = tileProp as Property<SofaTile>

                val facingProperty = try {
                    @Suppress("UNCHECKED_CAST")
                    (block.getProperty("facing") as? Property<Direction>)
                } catch (_: Throwable) {
                    null
                }

                val axisProperty = try {
                    block.getProperty("axis")
                } catch (_: Throwable) {
                    null
                }

                return SofaConnectTileBehavior(
                    block,
                    tileProperty,
                    facingProperty,
                    axisProperty
                )
            }
        }
    }

    private enum class SofaOrientation {
        X,
        Z
    }

    private fun isBatchUpdating(): Boolean {
        return inBatch.get() == true
    }

    private fun isSame(world: World, pos: BlockPos): Boolean {
        val state = world.getBlock(pos).customBlockState() ?: return false
        return state.owner().value().id() == blockDefinition.id()
    }

    @Suppress("UNCHECKED_CAST")
    private fun getAxisValue(state: ImmutableBlockState): String? {
        val prop = axisProperty ?: return null

        val comparableProp = prop as? Property<Comparable<Any>> ?: return null

        val value = runCatching {
            state.get(comparableProp)
        }.getOrNull() ?: return null

        return value.toString().lowercase()
    }

    private fun resolveOrientation(state: ImmutableBlockState): SofaOrientation {
        // facing
        if (facingProperty != null) {
            val facing = runCatching { state.get(facingProperty) }.getOrNull()
            if (facing != null) {
                return when (facing) {
                    Direction.EAST, Direction.WEST -> SofaOrientation.X
                    Direction.NORTH, Direction.SOUTH -> SofaOrientation.Z
                    else -> SofaOrientation.Z
                }
            }
        }

        // axis
        val axis = getAxisValue(state)
        if (axis != null) {
            return when (axis) {
                "x" -> SofaOrientation.X
                "z" -> SofaOrientation.Z
                else -> SofaOrientation.Z
            }
        }

        return SofaOrientation.Z
    }

    private fun findAxisValue(prop: Property<*>, wanted: String): Comparable<Any>? {
        val values = runCatching {
            prop.javaClass.methods.firstOrNull { it.name == "possibleValues" || it.name == "values" }
                ?.invoke(prop) as? Iterable<*>
        }.getOrNull() ?: return null

        val match = values.firstOrNull { it?.toString()?.equals(wanted, ignoreCase = true) == true }
            ?: return null

        @Suppress("UNCHECKED_CAST")
        return match as Comparable<Any>
    }

    @Suppress("UNCHECKED_CAST")
    private fun withAxis(state: ImmutableBlockState, axis: String): ImmutableBlockState {
        val prop = axisProperty ?: return state
        val comparableProp = prop as? Property<Comparable<Any>> ?: return state

        val current = runCatching {
            state.get(comparableProp)
        }.getOrNull() ?: return state

        /*Bukkit.getLogger().info(
            "[SOFA] withAxis current=$current currentClass=${(current as Any)::class.java.name} targetAxis=$axis"
        )*/

        val target = findAxisValue(prop, axis) ?: return state
        return state.with(comparableProp, target)
    }

    private fun resolvePlacedState(context: BlockPlaceContext, state: ImmutableBlockState): ImmutableBlockState {
        var placed = state

        if (facingProperty != null) {
            val horizontal = context.horizontalDirection ?: Direction.SOUTH
            placed = runCatching { placed.with(facingProperty, horizontal) }.getOrElse { placed }
        } else if (axisProperty != null) {
            val horizontal = context.horizontalDirection ?: Direction.SOUTH
            val axis = when (horizontal) {
                Direction.EAST, Direction.WEST -> "x"
                else -> "z"
            }
            placed = withAxis(placed, axis)
        }

        return placed
    }

    /**
     * - middle if both sides are on the same axis
     * - right if connected to +X or +Z
     * - left if connected to -X or -Z
     * - single if not connected
     */
    private fun calculateTile(state: ImmutableBlockState, world: World, pos: BlockPos): SofaTile {
        val axis = getAxisValue(state)

        val east = isSame(world, pos.offset(1, 0, 0))
        val west = isSame(world, pos.offset(-1, 0, 0))
        val north = isSame(world, pos.offset(0, 0, -1))
        val south = isSame(world, pos.offset(0, 0, 1))

        val result = when (axis) {
            // Row X
            "z" -> when {
                east && west -> SofaTile.middle
                east -> SofaTile.left
                west -> SofaTile.right
                else -> SofaTile.single
            }

            // Row Z
            "x" -> when {
                north && south -> SofaTile.middle
                south -> SofaTile.right
                north -> SofaTile.left
                else -> SofaTile.single
            }

            else -> SofaTile.single
        }

        /*Bukkit.getLogger().info(
            "[SOFA-FIX] pos=$pos axis=$axis east=$east west=$west north=$north south=$south result=$result"
        )*/

        return result
    }

    override fun updateShape(thisBlock: Any, args: Array<Any>): Any {
        val world = args.getOrNull(3) as? World ?: return super.updateShape(thisBlock, args)
        val pos = args.getOrNull(4) as? BlockPos ?: return super.updateShape(thisBlock, args)

        val optionalState = BlockStateUtils.getOptionalCustomBlockState(args[0])
            ?: return super.updateShape(thisBlock, args)

        val state = optionalState.get()
        val newTile = calculateTile(state, world, pos)

        return state
            .with(tileProperty, newTile)
            .customBlockState()
            .minecraftState()
    }

    override fun updateStateForPlacement(context: BlockPlaceContext, state: ImmutableBlockState): ImmutableBlockState {
        val placedState = resolvePlacedState(context, state)

        /*Bukkit.getLogger().info(
            "[SOFA] placement pos=${context.clickedPos} horizontal=${context.horizontalDirection} axis=${getAxisValue(placedState)}"
        )*/

        val newTile = calculateTile(placedState, context.level, context.clickedPos)
        return placedState.with(tileProperty, newTile)
    }

    override fun onPlace(thisBlock: Any, args: Array<Any>) {
        if (!isBatchUpdating()) {
            scheduleNeighborUpdateFromArgs(args, 1L)
        }
        super.onPlace(thisBlock, args)
    }

    override fun affectNeighborsAfterRemoval(thisBlock: Any, args: Array<Any>) {
        if (!isBatchUpdating()) {
            scheduleNeighborUpdateFromArgs(args, 1L)
        }
        super.affectNeighborsAfterRemoval(thisBlock, args)
    }

    private fun scheduleNeighborUpdateFromArgs(args: Array<Any>, delay: Long) {
        /*Bukkit.getLogger().info(
            "[SOFA] scheduleNeighborUpdateFromArgs size=${args.size} " +
                    "a0=${args.getOrNull(0)?.javaClass?.name} " +
                    "a1=${args.getOrNull(1)?.javaClass?.name} " +
                    "a2=${args.getOrNull(2)?.javaClass?.name}"
        )*/

        val nmsWorld = args.getOrNull(1) ?: return
        val nmsPos = args.getOrNull(2) ?: return

        val craftWorld = Bukkit.getWorlds().firstOrNull { world ->
            runCatching {
                val handle = world.javaClass.getMethod("getHandle").invoke(world)
                handle == nmsWorld
            }.getOrDefault(false)
        } ?: return

        val x = runCatching { nmsPos.javaClass.getMethod("getX").invoke(nmsPos) as Int }.getOrNull() ?: return
        val y = runCatching { nmsPos.javaClass.getMethod("getY").invoke(nmsPos) as Int }.getOrNull() ?: return
        val z = runCatching { nmsPos.javaClass.getMethod("getZ").invoke(nmsPos) as Int }.getOrNull() ?: return

        val ceWorld = BukkitAdaptor.adapt(craftWorld)
        val cePos = BlockPos(x, y, z)

        scheduleNeighborUpdate(ceWorld, cePos, delay)
    }

    private fun scheduleNeighborUpdate(world: World, pos: BlockPos, delay: Long) {
        if (isBatchUpdating()) return

        val key = k(world, pos)
        if (pending.putIfAbsent(key, true) != null) return

        val plugin = Bukkit.getPluginManager().getPlugin("UnearthMechanic") ?: run {
            pending.remove(key)
            return
        }

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            try {
                updateNeighbors(world, pos)
            } finally {
                pending.remove(key)
            }
        }, delay)
    }

    private fun updateNeighbors(world: World, origin: BlockPos) {
        val targets = collectConnectedTargets(world, origin)
        if (targets.isEmpty()) return

        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return
        val adaptedWorld = BukkitAdaptor.adapt(bukkitWorld)

        inBatch.set(true)
        try {
            for (target in targets) {
                val state = world.getBlock(target).customBlockState() ?: continue
                if (!isSame(world, target)) continue

                val newTile = calculateTile(state, world, target)
                val newState = runCatching { state.with(tileProperty, newTile) }.getOrNull() ?: continue

                adaptedWorld.setBlockState(
                    target.x(),
                    target.y(),
                    target.z(),
                    newState,
                    UpdateFlags.UPDATE_ALL
                )
            }
        } finally {
            inBatch.set(false)
        }
    }

    private fun collectConnectedTargets(world: World, origin: BlockPos): Set<BlockPos> {
        val seeds = arrayOf(
            origin,
            origin.offset(1, 0, 0),
            origin.offset(-1, 0, 0),
            origin.offset(0, 0, 1),
            origin.offset(0, 0, -1)
        )

        val visited = linkedSetOf<BlockPos>()
        val queue = ArrayDeque<BlockPos>()

        for (seed in seeds) {
            if (isSame(world, seed) && visited.add(seed)) {
                queue.add(seed)
            }
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()

            val state = world.getBlock(current).customBlockState() ?: continue
            val orientation = resolveOrientation(state)

            val neighbors = when (orientation) {
                SofaOrientation.X -> arrayOf(
                    current.offset(1, 0, 0),
                    current.offset(-1, 0, 0)
                )

                SofaOrientation.Z -> arrayOf(
                    current.offset(0, 0, 1),
                    current.offset(0, 0, -1)
                )
            }

            for (next in neighbors) {
                val nextState = world.getBlock(next).customBlockState() ?: continue

                if (nextState.owner().value().id() != blockDefinition.id()) continue
                if (resolveOrientation(nextState) != orientation) continue

                if (visited.add(next)) {
                    queue.add(next)
                }
            }
        }

        return visited
    }
}