package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior

import dev.wuason.unearthMechanic.compatibilities.craftengine.types.CurtainYPos
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

class CurtainBlockBehavior(
    customBlock: BlockDefinition,
    private val yPosProperty: Property<CurtainYPos>,
    private val facingProperty: Property<Direction>,
    private val openProperty: Property<Boolean>,
    private val poweredProperty: Property<Boolean>
) : BaseConnectBlockBehavior<CurtainYPos>(customBlock, yPosProperty) {

    override fun updateShape(thisBlock: Any, args: Array<Any>): Any {
        val optionalState = BlockStateUtils.getOptionalCustomBlockState(args[0]) ?: return super.updateShape(thisBlock, args)
        val state = optionalState.get()
        val (world, pos) = resolveUpdateShapeContext(args) ?: return super.updateShape(thisBlock, args)

        return state
            .with(yPosProperty, calculateYPos(state, world, pos))
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

        return placedState.with(yPosProperty, calculateYPos(placedState, context.level, context.clickedPos))
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
        val column = collectColumn(context.level, context.clickedPos, state.get(facingProperty))
        if (column.isEmpty()) {
            return InteractionResult.PASS
        }

        val targetOpen = !state.get(openProperty)
        runBatch {
            for (pos in column) {
                val curtainState = getCurtainState(context.level, pos) ?: continue
                setBlockState(context.level, pos, curtainState.with(openProperty, targetOpen))
            }
        }
        return InteractionResult.SUCCESS_AND_CANCEL
    }

    override fun neighborChanged(thisBlock: Any, args: Array<Any>) {
        val optionalState = BlockStateUtils.getOptionalCustomBlockState(args[0]) ?: return
        val state = optionalState.get()
        val (world, pos) = resolveWorldAndPos(args.getOrNull(1), args.getOrNull(2)) ?: return
        syncColumnRedstone(world, pos, state.get(facingProperty))
    }

    override fun updateNeighbors(world: World, origin: BlockPos) {
        val processed = linkedSetOf<BlockPos>()
        val columns = mutableListOf<List<BlockPos>>()

        for (seed in arrayOf(origin, origin.above(), origin.below())) {
            if (seed in processed) continue
            val state = getCurtainState(world, seed) ?: continue
            val column = collectColumn(world, seed, state.get(facingProperty))
            if (column.isEmpty()) continue
            processed.addAll(column)
            columns.add(column)
        }

        runBatch {
            for (column in columns) {
                updateColumn(world, column)
            }
        }
    }

    private fun updateColumn(world: World, column: List<BlockPos>) {
        if (column.isEmpty()) return

        val columnPowered = column.any { isLocallyPowered(world, it) }

        for (pos in column) {
            val state = getCurtainState(world, pos) ?: continue
            var nextState = state.with(yPosProperty, calculateYPos(state, world, pos))
            val currentPowered = state.get(poweredProperty)

            if (currentPowered != columnPowered) {
                if (state.get(openProperty) != columnPowered) {
                    nextState = nextState.with(openProperty, columnPowered)
                }
                nextState = nextState.with(poweredProperty, columnPowered)
            }

            setBlockState(world, pos, nextState)
        }
    }

    private fun syncColumnRedstone(world: World, origin: BlockPos, facing: Direction) {
        val column = collectColumn(world, origin, facing)
        if (column.isEmpty()) return

        val oldPowered = getCurtainState(world, origin)?.get(poweredProperty) ?: false
        val rawNewPower = if (column.any { isLocallyPowered(world, it) }) 15 else 0
        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return
        val bukkitBlock = bukkitWorld.getBlockAt(origin.x(), origin.y(), origin.z())
        val event = BlockRedstoneEvent(bukkitBlock, if (oldPowered) 15 else 0, rawNewPower)
        Bukkit.getPluginManager().callEvent(event)
        val newPowered = event.newCurrent > 0

        runBatch {
            for (pos in column) {
                val state = getCurtainState(world, pos) ?: continue
                var nextState = state
                    .with(yPosProperty, calculateYPos(state, world, pos))
                    .with(poweredProperty, newPowered)

                if (state.get(poweredProperty) != newPowered && state.get(openProperty) != newPowered) {
                    nextState = nextState.with(openProperty, newPowered)
                }

                setBlockState(world, pos, nextState)
            }
        }
    }

    private fun calculateYPos(state: ImmutableBlockState, world: World, pos: BlockPos): CurtainYPos {
        val facing = state.get(facingProperty)
        val upConnected = getCurtainState(world, pos.above(), facing) != null
        val downConnected = getCurtainState(world, pos.below(), facing) != null

        return when {
            upConnected && downConnected -> CurtainYPos.middle
            upConnected -> CurtainYPos.bottom
            downConnected -> CurtainYPos.top
            else -> CurtainYPos.single
        }
    }

    private fun collectColumn(world: World, origin: BlockPos, facing: Direction): List<BlockPos> {
        val visited = linkedSetOf<BlockPos>()
        val queue = ArrayDeque<BlockPos>()

        for (seed in arrayOf(origin, origin.above(), origin.below())) {
            if (getCurtainState(world, seed, facing) != null && visited.add(seed)) {
                queue.add(seed)
            }
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (next in arrayOf(current.above(), current.below())) {
                if (getCurtainState(world, next, facing) != null && visited.add(next)) {
                    queue.add(next)
                }
            }
        }

        return visited.sortedBy { it.y() }
    }

    private fun getCurtainState(
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

        class Factory : BlockBehaviorFactory<CurtainBlockBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): CurtainBlockBehavior {
                val yPosProperty = BlockBehaviorFactory.getProperty(
                    section.path(),
                    block,
                    "ypos",
                    CurtainYPos::class.java
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
                return CurtainBlockBehavior(block, yPosProperty, facingProperty, openProperty, poweredProperty)
            }
        }
    }

}