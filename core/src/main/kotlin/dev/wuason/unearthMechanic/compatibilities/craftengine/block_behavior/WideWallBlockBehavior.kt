package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior

import dev.wuason.unearthMechanic.compatibilities.craftengine.types.WallConnection
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.bukkit.util.BlockStateUtils
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.UpdateFlags
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import net.momirealms.craftengine.core.world.context.BlockPlaceContext
import org.bukkit.Bukkit
import org.bukkit.Material

class WideWallBlockBehavior(
    customBlock: BlockDefinition,
    private val northProperty: Property<WallConnection>,
    private val eastProperty: Property<WallConnection>,
    private val southProperty: Property<WallConnection>,
    private val westProperty: Property<WallConnection>,
    private val upProperty: Property<Boolean>? = null,
    private val waterloggedProperty: Property<Boolean>? = null,
) : BukkitBlockBehavior(customBlock) {

    override fun updateStateForPlacement(context: BlockPlaceContext, state: ImmutableBlockState): ImmutableBlockState {
        val placed = if (waterloggedProperty != null && isWaterAt(context.level, context.clickedPos)) {
            state.with(waterloggedProperty, true)
        } else {
            state
        }

        return recalculateState(context.level, context.clickedPos, placed)
    }

    override fun updateShape(thisBlock: Any, args: Array<Any>): Any {
        val optionalState = BlockStateUtils.getOptionalCustomBlockState(args.getOrNull(0))
            ?: return super.updateShape(thisBlock, args)
        val state = optionalState.orElse(null) ?: return super.updateShape(thisBlock, args)
        val (world, pos) = worldAndPosFromUpdateShapeArgs(args) ?: return super.updateShape(thisBlock, args)

        return recalculateState(world, pos, state).customBlockState().minecraftState()
    }

    override fun neighborChanged(thisBlock: Any, args: Array<Any>) {
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: return
        val state = world.getBlock(pos).customBlockState() ?: return
        if (!isSameBlock(state)) return

        setBlockState(world, pos, recalculateState(world, pos, state))
    }

    private fun recalculateState(world: World, pos: BlockPos, state: ImmutableBlockState): ImmutableBlockState {
        val north = connectionFor(world, pos, 0, 0, -1)
        val east = connectionFor(world, pos, 1, 0, 0)
        val south = connectionFor(world, pos, 0, 0, 1)
        val west = connectionFor(world, pos, -1, 0, 0)

        var next = state
            .with(northProperty, north)
            .with(eastProperty, east)
            .with(southProperty, south)
            .with(westProperty, west)

        if (upProperty != null) {
            next = next.with(upProperty, shouldRenderPost(north, east, south, west))
        }

        return next
    }

    private fun connectionFor(world: World, pos: BlockPos, dx: Int, dy: Int, dz: Int): WallConnection {
        val neighborPos = pos.offset(dx, dy, dz)
        val neighbor = world.getBlock(neighborPos).customBlockState()
        return if (canConnectTo(world, neighborPos, neighbor)) WallConnection.low else WallConnection.none
    }

    private fun shouldRenderPost(
        north: WallConnection,
        east: WallConnection,
        south: WallConnection,
        west: WallConnection,
    ): Boolean {
        val connected = listOf(north, east, south, west).count { it != WallConnection.none }
        if (connected == 0 || connected == 1) return true
        val northSouth = north != WallConnection.none || south != WallConnection.none
        val eastWest = east != WallConnection.none || west != WallConnection.none
        return northSouth && eastWest
    }

    private fun isWaterAt(world: World, pos: BlockPos): Boolean {
        val bukkitWorld = Bukkit.getWorlds().firstOrNull { bukkit ->
            BukkitAdaptor.adapt(bukkit) == world
        } ?: return false

        return bukkitWorld.getBlockAt(pos.x(), pos.y(), pos.z()).type.name == "WATER"
    }

    private fun worldAndPosFromUpdateShapeArgs(args: Array<Any>): Pair<World, BlockPos>? {
        val currentVersion = (args.getOrNull(1) as? World)?.let { world ->
            (args.getOrNull(3) as? BlockPos)?.let { pos -> world to pos }
        }
        if (currentVersion != null) return currentVersion

        return (args.getOrNull(3) as? World)?.let { world ->
            (args.getOrNull(4) as? BlockPos)?.let { pos -> world to pos }
        }
    }

    private fun worldAndPosFromNmsArgs(args: Array<Any>, worldIndex: Int = 1, posIndex: Int = 2): Pair<World, BlockPos>? {
        val world = worldFromNmsWorld(args.getOrNull(worldIndex)) ?: return null
        val pos = blockPosFromNms(args.getOrNull(posIndex)) ?: return null
        return world to pos
    }

    private fun worldFromNmsWorld(nmsWorld: Any?): World? {
        if (nmsWorld == null) return null

        val craftWorld = Bukkit.getWorlds().firstOrNull { world ->
            val handle = world.javaClass.getMethod("getHandle").invoke(world)
            handle == nmsWorld
        } ?: return null

        return BukkitAdaptor.adapt(craftWorld)
    }

    private fun blockPosFromNms(nmsPos: Any?): BlockPos? {
        if (nmsPos == null) return null

        val x = nmsPos.javaClass.getMethod("getX").invoke(nmsPos) as Int
        val y = nmsPos.javaClass.getMethod("getY").invoke(nmsPos) as Int
        val z = nmsPos.javaClass.getMethod("getZ").invoke(nmsPos) as Int

        return BlockPos(x, y, z)
    }

    private fun setBlockState(world: World, pos: BlockPos, state: ImmutableBlockState) {
        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return

        BukkitAdaptor.adapt(bukkitWorld).setBlockState(
            pos.x(),
            pos.y(),
            pos.z(),
            state,
            UpdateFlags.UPDATE_ALL
        )
    }

    private fun isSameBlock(state: ImmutableBlockState?): Boolean {
        return state != null && state.owner().value().id() == blockDefinition.id()
    }

    private fun canConnectTo(world: World, pos: BlockPos, state: ImmutableBlockState?): Boolean {
        if (state == null) return canConnectToVanilla(world, pos)
        if (isSameBlock(state)) return true

        val definition = state.owner().value()
        return definition.getProperty("north") != null
                && definition.getProperty("east") != null
                && definition.getProperty("south") != null
                && definition.getProperty("west") != null
    }

    private fun canConnectToVanilla(world: World, pos: BlockPos): Boolean {
        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return false
        val material = bukkitWorld.getBlockAt(pos.x(), pos.y(), pos.z()).type

        if (material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR) return false

        return material.name.endsWith("_WALL")
                || material.name.endsWith("_FENCE")
                || material.name.endsWith("_FENCE_GATE")
                || material.isOccluding
    }

    companion object {
        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<WideWallBlockBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): WideWallBlockBehavior {
                val north = block.getProperty("north")
                    ?: throw IllegalArgumentException("Missing 'north' property")
                val east = block.getProperty("east")
                    ?: throw IllegalArgumentException("Missing 'east' property")
                val south = block.getProperty("south")
                    ?: throw IllegalArgumentException("Missing 'south' property")
                val west = block.getProperty("west")
                    ?: throw IllegalArgumentException("Missing 'west' property")

                @Suppress("UNCHECKED_CAST")
                return WideWallBlockBehavior(
                    block,
                    north as Property<WallConnection>,
                    east as Property<WallConnection>,
                    south as Property<WallConnection>,
                    west as Property<WallConnection>,
                    block.getProperty("up") as? Property<Boolean>,
                    block.getProperty("waterlogged") as? Property<Boolean>,
                )
            }
        }
    }
}