package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior

import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.bukkit.util.BlockStateUtils
import net.momirealms.craftengine.bukkit.util.DirectionUtils
import net.momirealms.craftengine.bukkit.util.LocationUtils
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.util.Direction
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import net.momirealms.craftengine.core.world.context.BlockPlaceContext

class MultifaceAttachedBlockBehavior(
    customBlock: BlockDefinition,
    private val north: Property<Boolean>,
    private val east: Property<Boolean>,
    private val south: Property<Boolean>,
    private val west: Property<Boolean>,
    private val up: Property<Boolean>,
    private val down: Property<Boolean>
) : BukkitBlockBehavior(customBlock) {

    companion object {
        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<MultifaceAttachedBlockBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): MultifaceAttachedBlockBehavior {
                fun boolProp(name: String): Property<Boolean> {
                    return BlockBehaviorFactory.getProperty(
                        section.path(),
                        block,
                        name,
                        Boolean::class.javaObjectType
                    )
                }

                return MultifaceAttachedBlockBehavior(
                    block,
                    boolProp("north"),
                    boolProp("east"),
                    boolProp("south"),
                    boolProp("west"),
                    boolProp("up"),
                    boolProp("down")
                )
            }
        }

        private val airDefaultState: Any by lazy {
            val blocksClass = Class.forName("net.minecraft.world.level.block.Blocks")
            val airBlock = blocksClass.getField("AIR").get(null)
            airBlock.javaClass.getMethod("defaultBlockState").invoke(airBlock)
        }

        private val supportTypeFull: Any by lazy {
            val supportTypeClass = Class.forName("net.minecraft.world.level.block.SupportType")
            supportTypeClass.enumConstants.first { (it as Enum<*>).name == "FULL" }
        }
    }

    override fun canBeReplaced(context: BlockPlaceContext, state: ImmutableBlockState): Boolean {
        if (state.owner().value().id() != blockDefinition.id()) {
            return super.canBeReplaced(context, state)
        }

        return placementDirections(context).any { direction ->
            !state.get(propertyFor(direction)) && supportsFace(context.getLevel(), context.getClickedPos(), direction)
        }
    }

    override fun updateStateForPlacement(
        context: BlockPlaceContext,
        state: ImmutableBlockState
    ): ImmutableBlockState? {
        val world = context.getLevel()
        val pos = context.getClickedPos()

        val existingState = world.getBlock(pos).customBlockState()
        val baseState =
            if (existingState != null && existingState.owner().value().id() == blockDefinition.id()) {
                existingState
            } else {
                state
            }

        for (supportDirection in placementDirections(context)) {
            val property = propertyFor(supportDirection)
            if (baseState.get(property)) continue
            if (!supportsFace(world, pos, supportDirection)) continue

            return baseState.with(property, true)
        }

        return null
    }

    override fun updateShape(thisBlock: Any, args: Array<Any>): Any {
        val state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null)
            ?: return args[0]

        val level = args[`updateShape$level`]
        val pos = LocationUtils.fromBlockPos(args[`updateShape$blockPos`])
        val changedDirection = DirectionUtils.fromNMSDirection(args[`updateShape$direction`])

        val property = propertyFor(changedDirection)
        val newState =
            if (state.get(property) && !supportsFace(level, pos, changedDirection)) {
                state.with(property, false)
            } else {
                state
            }

        if (!hasAnyFace(newState)) {
            return airDefaultState
        }

        return newState.customBlockState().minecraftState()
    }

    override fun canSurvive(thisBlock: Any, args: Array<Any>): Boolean {
        val state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null)
            ?: return false

        val level = args[1]
        val pos = LocationUtils.fromBlockPos(args[2])

        return Direction.entries.any { direction ->
            state.get(propertyFor(direction)) && supportsFace(level, pos, direction)
        }
    }

    private fun supportsFace(world: World, pos: BlockPos, face: Direction): Boolean {
        return supportsFace(world.minecraftWorld(), pos, face)
    }

    private fun placementDirections(context: BlockPlaceContext): LinkedHashSet<Direction> {
        val directions = linkedSetOf<Direction>()
        directions += context.getClickedFace().opposite()
        directions += context.getClickedFace()
        context.getNearestLookingDirections().forEach { direction ->
            directions += direction
            directions += direction.opposite()
        }
        return directions
    }

    private fun supportsFace(level: Any, pos: BlockPos, face: Direction): Boolean {
        val supportPos = pos.relative(face)
        val nmsSupportPos = LocationUtils.toBlockPos(supportPos)
        val supportState = level.javaClass
            .methods
            .firstOrNull { method ->
                method.name == "getBlockState" &&
                        method.parameterCount == 1 &&
                        method.parameterTypes[0].isAssignableFrom(nmsSupportPos.javaClass)
            }
            ?.invoke(level, nmsSupportPos)
            ?: return false

        val nmsDirection = DirectionUtils.toNMSDirection(face.opposite())
        val isFaceSturdy = supportState.javaClass.methods.firstOrNull { method ->
            method.name == "isFaceSturdy" && method.parameterCount == 4
        } ?: return false

        return isFaceSturdy.invoke(
            supportState,
            level,
            nmsSupportPos,
            nmsDirection,
            supportTypeFull
        ) as Boolean
    }

    private fun hasAnyFace(state: ImmutableBlockState): Boolean {
        return state.get(north) ||
                state.get(east) ||
                state.get(south) ||
                state.get(west) ||
                state.get(up) ||
                state.get(down)
    }

    private fun propertyFor(direction: Direction): Property<Boolean> {
        return when (direction) {
            Direction.NORTH -> north
            Direction.EAST -> east
            Direction.SOUTH -> south
            Direction.WEST -> west
            Direction.UP -> up
            Direction.DOWN -> down
        }
    }

}