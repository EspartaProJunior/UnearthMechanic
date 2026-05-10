package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior

import dev.wuason.unearthMechanic.compatibilities.craftengine.types.SofaTile
import net.momirealms.craftengine.bukkit.util.BlockStateUtils
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.entity.player.InteractionResult
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.util.Direction
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import net.momirealms.craftengine.core.world.context.BlockPlaceContext
import net.momirealms.craftengine.core.world.context.UseOnContext
import org.bukkit.Bukkit
import org.bukkit.event.block.BlockRedstoneEvent

class ShowerCurtainBlockBehavior(
    customBlock: BlockDefinition,
    private val posProperty: Property<SofaTile>,
    private val facingProperty: Property<Direction>,
    private val openProperty: Property<Boolean>,
    private val poweredProperty: Property<Boolean>
) : BaseConnectBlockBehavior<SofaTile>(customBlock, posProperty) {

    override fun updateShape(thisBlock: Any, args: Array<Any>): Any {
        val optionalState = BlockStateUtils.getOptionalCustomBlockState(args[0]) ?: return super.updateShape(thisBlock, args)
        val state = optionalState.get()
        val (world, pos) = resolveUpdateShapeContext(args) ?: return super.updateShape(thisBlock, args)

        return state
            .with(posProperty, calculatePos(state, world, pos))
            .customBlockState()
            .minecraftState()
    }

    override fun updateStateForPlacement(context: BlockPlaceContext, state: ImmutableBlockState): ImmutableBlockState {
        val facing = context.horizontalDirection
        val locallyPowered = isLocallyPowered(context.level, context.clickedPos)
        val placedState = state
            .with(facingProperty, facing)
            .with(openProperty, locallyPowered)
            .with(poweredProperty, locallyPowered)

        return placedState.with(posProperty, calculatePos(placedState, context.level, context.clickedPos))
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

    override fun useWithoutItem(context: UseOnContext, state: ImmutableBlockState): InteractionResult {
        val line = collectLine(context.level, context.clickedPos, state.get(facingProperty))
        if (line.isEmpty()) {
            return InteractionResult.PASS
        }

        val targetOpen = !state.get(openProperty)
        runBatch {
            for (pos in line) {
                val curtainState = getShowerCurtainState(context.level, pos) ?: continue
                setBlockState(context.level, pos, curtainState.with(openProperty, targetOpen))
            }
        }
        return InteractionResult.SUCCESS_AND_CANCEL
    }

    override fun neighborChanged(thisBlock: Any, args: Array<Any>) {
        val optionalState = BlockStateUtils.getOptionalCustomBlockState(args[0]) ?: return
        val state = optionalState.get()
        val (world, pos) = resolveWorldAndPos(args.getOrNull(1), args.getOrNull(2)) ?: return
        syncLineRedstone(world, pos, state.get(facingProperty))
    }

    override fun updateNeighbors(world: World, origin: BlockPos) {
        val processed = linkedSetOf<BlockPos>()
        val lines = mutableListOf<List<BlockPos>>()

        for (seed in arrayOf(origin, origin.east(), origin.west(), origin.south(), origin.north())) {
            if (seed in processed) continue
            val state = getShowerCurtainState(world, seed) ?: continue
            val line = collectLine(world, seed, state.get(facingProperty))
            if (line.isEmpty()) continue
            processed.addAll(line)
            lines.add(line)
        }

        runBatch {
            for (line in lines) {
                updateLine(world, line)
            }
        }
    }

    private fun updateLine(world: World, line: List<BlockPos>) {
        if (line.isEmpty()) return

        val linePowered = line.any { isLocallyPowered(world, it) }

        for (pos in line) {
            val state = getShowerCurtainState(world, pos) ?: continue
            var nextState = state.with(posProperty, calculatePos(state, world, pos))
            val currentPowered = state.get(poweredProperty)

            if (currentPowered != linePowered) {
                if (state.get(openProperty) != linePowered) {
                    nextState = nextState.with(openProperty, linePowered)
                }
                nextState = nextState.with(poweredProperty, linePowered)
            }

            setBlockState(world, pos, nextState)
        }
    }

    private fun syncLineRedstone(world: World, origin: BlockPos, facing: Direction) {
        val line = collectLine(world, origin, facing)
        if (line.isEmpty()) return

        val oldPowered = getShowerCurtainState(world, origin)?.get(poweredProperty) ?: false
        val rawNewPower = if (line.any { isLocallyPowered(world, it) }) 15 else 0
        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return
        val bukkitBlock = bukkitWorld.getBlockAt(origin.x(), origin.y(), origin.z())
        val event = BlockRedstoneEvent(bukkitBlock, if (oldPowered) 15 else 0, rawNewPower)
        Bukkit.getPluginManager().callEvent(event)
        val newPowered = event.newCurrent > 0

        runBatch {
            for (pos in line) {
                val state = getShowerCurtainState(world, pos) ?: continue
                var nextState = state
                    .with(posProperty, calculatePos(state, world, pos))
                    .with(poweredProperty, newPowered)

                if (state.get(poweredProperty) != newPowered && state.get(openProperty) != newPowered) {
                    nextState = nextState.with(openProperty, newPowered)
                }

                setBlockState(world, pos, nextState)
            }
        }
    }

    private fun calculatePos(state: ImmutableBlockState, world: World, pos: BlockPos): SofaTile {
        val facing = state.get(facingProperty)
        val leftConnected = isSameLineNeighbor(world, pos.relative(facing.counterClockWise()), facing)
        val rightConnected = isSameLineNeighbor(world, pos.relative(facing.clockWise()), facing)

        return when {
            leftConnected && rightConnected -> SofaTile.middle
            leftConnected -> SofaTile.left
            rightConnected -> SofaTile.right
            else -> SofaTile.single
        }
    }

    private fun isSameLineNeighbor(world: World, pos: BlockPos, facing: Direction): Boolean {
        val neighborState = getShowerCurtainState(world, pos) ?: return false
        return neighborState.get(facingProperty) == facing
    }

    private fun collectLine(world: World, origin: BlockPos, facing: Direction): List<BlockPos> {
        val visited = linkedSetOf<BlockPos>()
        val queue = ArrayDeque<BlockPos>()

        for (seed in arrayOf(origin, origin.east(), origin.west(), origin.south(), origin.north())) {
            if (getShowerCurtainState(world, seed, facing) != null && visited.add(seed)) {
                queue.add(seed)
            }
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (next in arrayOf(current.east(), current.west(), current.south(), current.north())) {
                if (getShowerCurtainState(world, next, facing) != null && visited.add(next)) {
                    queue.add(next)
                }
            }
        }

        return visited.toList()
    }

    private fun getShowerCurtainState(
        world: World,
        pos: BlockPos,
        facing: Direction? = null
    ): ImmutableBlockState? {
        val state = world.getBlock(pos).customBlockState() ?: return null
        if (state.owner().value().id() != block().id()) return null
        if (facing != null && state.get(facingProperty) != facing) return null
        return state
    }

    private fun isLocallyPowered(world: World, pos: BlockPos): Boolean {
        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return false
        val block = bukkitWorld.getBlockAt(pos.x(), pos.y(), pos.z())
        return block.blockPower > 0 || block.isBlockPowered || block.isBlockIndirectlyPowered
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

        return net.momirealms.craftengine.bukkit.api.BukkitAdaptor.adapt(craftWorld) to BlockPos(x, y, z)
    }

    companion object {
        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<ShowerCurtainBlockBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): ShowerCurtainBlockBehavior {
                val posProperty = BlockBehaviorFactory.getProperty(
                    section.path(),
                    block,
                    "pos",
                    SofaTile::class.java
                )
                val facingProperty = BlockBehaviorFactory.getProperty(
                    section.path(),
                    block,
                    "facing",
                    Direction::class.java
                )
                val openProperty: Property<Boolean> = BlockBehaviorFactory.getProperty(
                    section.path(),
                    block,
                    "open",
                    Boolean::class.javaObjectType
                )
                val poweredProperty: Property<Boolean> = BlockBehaviorFactory.getProperty(
                    section.path(),
                    block,
                    "powered",
                    Boolean::class.javaObjectType
                )
                return ShowerCurtainBlockBehavior(block, posProperty, facingProperty, openProperty, poweredProperty)
            }
        }
    }
}
