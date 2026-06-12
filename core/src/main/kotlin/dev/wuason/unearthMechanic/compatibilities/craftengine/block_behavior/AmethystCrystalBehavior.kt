package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior

import dev.wuason.unearthMechanic.compatibilities.craftengine.types.AmethystFacing
import dev.wuason.unearthMechanic.utils.FoliaUtils
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.bukkit.util.BlockStateUtils
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import net.momirealms.craftengine.core.world.context.BlockPlaceContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material

class AmethystCrystalBehavior(
    customBlock: BlockDefinition,
    private val facingProperty: Property<AmethystFacing>
) : BukkitBlockBehavior(customBlock) {

    override fun updateStateForPlacement(context: BlockPlaceContext, state: ImmutableBlockState): ImmutableBlockState {
        return state.with(facingProperty, readClickedFace(context) ?: AmethystFacing.up)
    }

    override fun canSurvive(thisBlock: Any, args: Array<Any>): Boolean {
        val state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null) ?: return true
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: return true
        return canSurvive(world, pos, state)
    }

    override fun updateShape(thisBlock: Any, args: Array<Any>): Any {
        val state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null)
            ?: return super.updateShape(thisBlock, args)
        val (world, pos) = worldAndPosFromUpdateShapeArgs(args)
            ?: return super.updateShape(thisBlock, args)

        if (!canSurvive(world, pos, state)) {
            scheduleBreak(world, pos, 1L)
        }

        return super.updateShape(thisBlock, args)
    }

    override fun neighborChanged(thisBlock: Any, args: Array<Any>) {
        val state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null) ?: return
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: return

        if (!canSurvive(world, pos, state)) {
            scheduleBreak(world, pos, 1L)
        }
    }

    private fun canSurvive(world: World, pos: BlockPos, state: ImmutableBlockState): Boolean {
        val facing = state.get(facingProperty)
        val support = pos.offset(-facing.dx, -facing.dy, -facing.dz)
        val location = toBukkitLocation(world, support) ?: return true
        return location.block.type.isSolid
    }

    private fun scheduleBreak(world: World, pos: BlockPos, delay: Long) {
        val location = toBukkitLocation(world, pos) ?: return

        FoliaUtils.runLater(delay) {
            FoliaUtils.runAtLocation(location) {
                val current = world.getBlock(pos).customBlockState() ?: return@runAtLocation
                if (current.owner().value().id() == blockDefinition.id()) {
                    location.block.type = Material.AIR
                }
            }
        }
    }

    private fun readClickedFace(context: BlockPlaceContext): AmethystFacing? {
        val candidates = arrayOf("clickedFace", "getClickedFace", "face", "getFace")

        for (name in candidates) {
            val value = runCatching {
                val method = context.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?: return@runCatching null
                method.invoke(context)
            }.getOrNull() ?: continue

            return AmethystFacing.fromName(value.toString().substringAfterLast('.').lowercase())
        }

        return null
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
        val nmsWorld = args.getOrNull(worldIndex) ?: return null
        val nmsPos = args.getOrNull(posIndex) ?: return null

        val craftWorld = Bukkit.getWorlds().firstOrNull { world ->
            val handle = world.javaClass.getMethod("getHandle").invoke(world)
            handle == nmsWorld
        } ?: return null

        val x = nmsPos.javaClass.getMethod("getX").invoke(nmsPos) as Int
        val y = nmsPos.javaClass.getMethod("getY").invoke(nmsPos) as Int
        val z = nmsPos.javaClass.getMethod("getZ").invoke(nmsPos) as Int

        return BukkitAdaptor.adapt(craftWorld) to BlockPos(x, y, z)
    }

    private fun toBukkitLocation(world: World, pos: BlockPos): Location? {
        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return null
        return Location(bukkitWorld, pos.x().toDouble(), pos.y().toDouble(), pos.z().toDouble())
    }

    companion object {
        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<AmethystCrystalBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): AmethystCrystalBehavior {
                val facing = block.getProperty("facing")
                    ?: throw IllegalArgumentException("Missing 'facing' property")

                @Suppress("UNCHECKED_CAST")
                return AmethystCrystalBehavior(block, facing as Property<AmethystFacing>)
            }
        }
    }
}