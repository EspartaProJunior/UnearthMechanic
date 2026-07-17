package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior

import dev.wuason.unearthMechanic.compatibilities.craftengine.types.WallPos
import dev.wuason.unearthMechanic.compatibilities.craftengine.types.WallShape
import dev.wuason.unearthMechanic.compatibilities.craftengine.types.WallYPos
import net.momirealms.craftengine.bukkit.util.BlockStateUtils
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.util.Direction
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import net.momirealms.craftengine.core.world.context.BlockPlaceContext

class AdaptiveFacadeBlockBehavior(
    customBlock: BlockDefinition,
    private val posProperty: Property<WallPos>,
    private val yPosProperty: Property<WallYPos>,
    private val shapeProperty: Property<WallShape>,
    private val facingProperty: Property<Direction>?
) : BaseConnectBlockBehavior<WallYPos>(customBlock, yPosProperty) {

    private data class WallState(
        val pos: WallPos,
        val yPos: WallYPos,
        val shape: WallShape
    )

    override fun updateShape(thisBlock: Any, args: Array<Any>): Any {
        val optionalState = BlockStateUtils.getOptionalCustomBlockState(args.getOrNull(0))
            ?: return super.updateShape(thisBlock, args)
        val state = optionalState.orElse(null) ?: return super.updateShape(thisBlock, args)

        val (world, pos) = resolveUpdateShapeContext(args) ?: return super.updateShape(thisBlock, args)
        val wallState = calculateWallState(state, world, pos)
        return state
            .with(posProperty, wallState.pos)
            .with(yPosProperty, wallState.yPos)
            .with(shapeProperty, wallState.shape)
            .customBlockState()
            .minecraftState()
    }

    override fun updateStateForPlacement(context: BlockPlaceContext, state: ImmutableBlockState): ImmutableBlockState {
        val placedState = facingProperty?.let { property ->
            state.with(property, context.horizontalDirection)
        } ?: state
        val wallState = calculateWallState(placedState, context.level, context.clickedPos)
        return placedState
            .with(posProperty, wallState.pos)
            .with(yPosProperty, wallState.yPos)
            .with(shapeProperty, wallState.shape)
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


    private fun calculateWallState(state: ImmutableBlockState, world: World, pos: BlockPos): WallState {
        val facing = getFacing(state)
        val upConnected = isSameWallNeighbor(world, pos.above(), facing)
        val downConnected = isSameWallNeighbor(world, pos.below(), facing)
        val yPos = calculateVerticalPos(upConnected, downConnected)
        if (yPos != WallYPos.middle) {
            return WallState(pos = WallPos.single, yPos = yPos, shape = WallShape.straight)
        }

        val wallShape = if (facing != null) {
            calculateMiddleState(state, world, pos, facing)
        } else {
            calculateMiddleStateWithoutFacing(world, pos)
        }
        return WallState(pos = wallShape.pos, yPos = yPos, shape = wallShape.shape)
    }

    private fun calculateMiddleState(state: ImmutableBlockState, world: World, pos: BlockPos, facing: Direction): WallState {
        val leftDirection = facing.counterClockWise()
        val rightDirection = facing.clockWise()
        val leftConnected = isConnectedOnSide(world, pos.relative(leftDirection), facing, leftDirection)
        val rightConnected = isConnectedOnSide(world, pos.relative(rightDirection), facing, rightDirection)

        return when {
            isInnerCorner(state, world, pos, facing, leftDirection) -> WallState(WallPos.left, WallYPos.middle, WallShape.inner)
            isInnerCorner(state, world, pos, facing, rightDirection) -> WallState(WallPos.right, WallYPos.middle, WallShape.inner)
            leftConnected && rightConnected -> WallState(WallPos.middle, WallYPos.middle, WallShape.straight)
            rightConnected -> WallState(WallPos.left, WallYPos.middle, WallShape.straight)
            leftConnected -> WallState(WallPos.right, WallYPos.middle, WallShape.straight)
            else -> WallState(WallPos.single, WallYPos.middle, WallShape.straight)
        }
    }

    private fun calculateMiddleStateWithoutFacing(world: World, pos: BlockPos): WallState {
        val eastConnected = getSameWallState(world, pos.east()) != null
        val westConnected = getSameWallState(world, pos.west()) != null
        val southConnected = getSameWallState(world, pos.south()) != null
        val northConnected = getSameWallState(world, pos.north()) != null

        return when {
            eastConnected && westConnected -> WallState(WallPos.middle, WallYPos.middle, WallShape.straight)
            northConnected && southConnected -> WallState(WallPos.middle, WallYPos.middle, WallShape.straight)
            (eastConnected && southConnected) || (westConnected && northConnected) -> WallState(WallPos.left, WallYPos.middle, WallShape.inner)
            (eastConnected && northConnected) || (westConnected && southConnected) -> WallState(WallPos.right, WallYPos.middle, WallShape.inner)
            eastConnected || southConnected -> WallState(WallPos.right, WallYPos.middle, WallShape.straight)
            westConnected || northConnected -> WallState(WallPos.left, WallYPos.middle, WallShape.straight)
            else -> WallState(WallPos.single, WallYPos.middle, WallShape.straight)
        }
    }

    private fun calculateVerticalPos(upConnected: Boolean, downConnected: Boolean): WallYPos {
        return when {
            upConnected && downConnected -> WallYPos.middle
            upConnected -> WallYPos.bottom
            downConnected -> WallYPos.top
            else -> WallYPos.bottom
        }
    }

    private fun isInnerCorner(
        state: ImmutableBlockState,
        world: World,
        pos: BlockPos,
        facing: Direction,
        cornerDirection: Direction
    ): Boolean {
        val behindState = getSameWallState(world, pos.relative(facing.opposite())) ?: return false
        val behindFacing = getFacing(behindState) ?: return false
        if (behindFacing.axis() == facing.axis()) return false
        if (behindFacing != cornerDirection) return false
        return canTakeInnerShape(state, world, pos, behindFacing)
    }

    private fun canTakeInnerShape(
        state: ImmutableBlockState,
        world: World,
        pos: BlockPos,
        face: Direction
    ): Boolean {
        val sideState = getSameWallState(world, pos.relative(face)) ?: return true
        val stateFacing = getFacing(state) ?: return false
        val sideFacing = getFacing(sideState) ?: return true
        return sideFacing != stateFacing
    }

    private fun isConnectedOnSide(
        world: World,
        pos: BlockPos,
        facing: Direction,
        sideDirection: Direction
    ): Boolean {
        val neighborState = getSameWallState(world, pos) ?: return false
        val neighborFacing = getFacing(neighborState) ?: return false
        return neighborFacing == facing || neighborFacing == sideDirection
    }

    private fun isSameWallNeighbor(world: World, pos: BlockPos, facing: Direction?): Boolean {
        val neighborState = getSameWallState(world, pos) ?: return false
        return facing == null || getFacing(neighborState) == facing
    }

    private fun getFacing(state: ImmutableBlockState): Direction? {
        val property = facingProperty ?: return null
        return state.get(property)
    }

    private fun getSameWallState(world: World, pos: BlockPos): ImmutableBlockState? {
        val neighborState = world.getBlock(pos).customBlockState() ?: return null
        return if (neighborState.owner().value().id() == block().id()) neighborState else null
    }

    override fun updateNeighbors(world: World, origin: BlockPos) {
        val targets = collectConnectedTargets(world, origin)

        runBatch {
            for (target in targets) {
                val state = world.getBlock(target).customBlockState() ?: continue
                if (!isSameBlock(world, target)) continue

                val wallState = calculateWallState(state, world, target)
                setBlockState(
                    world,
                    target,
                    state
                        .with(posProperty, wallState.pos)
                        .with(yPosProperty, wallState.yPos)
                        .with(shapeProperty, wallState.shape)
                )
            }
        }
    }

    private fun collectConnectedTargets(
        world: World,
        origin: BlockPos,
        maxBlocks: Int = Int.MAX_VALUE
    ): Set<BlockPos> {
        val limit = maxBlocks.coerceAtLeast(1)
        val seeds = arrayOf(
            origin,
            origin.east(),
            origin.west(),
            origin.south(),
            origin.north(),
            origin.above(),
            origin.below()
        )

        val visited = linkedSetOf<BlockPos>()
        val queue = ArrayDeque<BlockPos>()

        for (seed in seeds) {
            if (visited.size >= limit) break
            if (isSameBlock(world, seed) && visited.add(seed)) {
                queue.add(seed)
            }
        }

        while (queue.isNotEmpty() && visited.size < limit) {
            val current = queue.removeFirst()
            val neighbors = arrayOf(
                current.east(),
                current.west(),
                current.south(),
                current.north(),
                current.above(),
                current.below()
            )

            for (next in neighbors) {
                if (visited.size >= limit) break
                if (!isSameBlock(world, next)) continue
                if (visited.add(next)) {
                    queue.add(next)
                }
            }
        }

        return visited
    }

    companion object {
        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<AdaptiveFacadeBlockBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): AdaptiveFacadeBlockBehavior {
                val posProperty = BlockBehaviorFactory.getProperty(
                    section.path(),
                    block,
                    "pos",
                    WallPos::class.java
                )
                val yPosProperty = BlockBehaviorFactory.getProperty(
                    section.path(),
                    block,
                    "ypos",
                    WallYPos::class.java
                )
                val shapeProperty = BlockBehaviorFactory.getProperty(
                    section.path(),
                    block,
                    "shape",
                    WallShape::class.java
                )
                val facingProperty = runCatching {
                    BlockBehaviorFactory.getProperty(
                        section.path(),
                        block,
                        "facing",
                        Direction::class.java
                    )
                }.getOrNull()
                return AdaptiveFacadeBlockBehavior(
                    block,
                    posProperty,
                    yPosProperty,
                    shapeProperty,
                    facingProperty
                )
            }
        }
    }
}
