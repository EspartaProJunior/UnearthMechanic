package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior

import dev.wuason.unearthMechanic.compatibilities.craftengine.types.AmethystFacing
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.block.BukkitBlockManager
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.bukkit.world.BukkitWorld
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.UpdateFlags
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.util.Key
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import java.util.concurrent.ThreadLocalRandom

class BuddingAmethystBehavior(
    customBlock: BlockDefinition,
    private val smallBud: String,
    private val mediumBud: String,
    private val largeBud: String,
    private val cluster: String,
    private val chance: Int = 5
) : BukkitBlockBehavior(customBlock) {

    override fun randomTick(thisBlock: Any, args: Array<Any>) {
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: return
        if (ThreadLocalRandom.current().nextInt(chance.coerceAtLeast(1)) != 0) return

        val facing = AmethystFacing.entries[ThreadLocalRandom.current().nextInt(AmethystFacing.entries.size)]
        val targetPos = pos.offset(facing.dx, facing.dy, facing.dz)
        val targetState = world.getBlock(targetPos).customBlockState()

        when {
            targetState == null && isEmptyAt(world, targetPos) -> {
                setCustomBlock(world, targetPos, smallBud, facing)
            }
            targetState != null && targetState.getOrNullFacing() == facing -> {
                val nextBlock = when (targetState.owner().value().id().toString()) {
                    smallBud -> mediumBud
                    mediumBud -> largeBud
                    largeBud -> cluster
                    else -> null
                }

                if (nextBlock != null) {
                    setCustomBlock(world, targetPos, nextBlock, facing)
                }
            }
        }
    }

    private fun ImmutableBlockState.getOrNullFacing(): AmethystFacing? {
        val property = owner().value().getProperty("facing") ?: return null

        @Suppress("UNCHECKED_CAST")
        return get(property as Property<AmethystFacing>)
    }

    private fun setCustomBlock(world: World, pos: BlockPos, blockId: String, facing: AmethystFacing) {
        if (world !is BukkitWorld) return

        val definition = BukkitBlockManager.instance()
            .blockById(Key.of(blockId))
            .orElse(null)
            ?: return

        val facingProperty = definition.getProperty("facing") ?: return

        @Suppress("UNCHECKED_CAST")
        val state = definition.variantProvider().states().first()
            .with(facingProperty as Property<AmethystFacing>, facing)

        BukkitAdaptor.adapt(Bukkit.getWorld(world.name()) ?: return).setBlockState(
            pos.x(),
            pos.y(),
            pos.z(),
            state,
            UpdateFlags.UPDATE_ALL
        )
    }

    private fun isEmptyAt(world: World, pos: BlockPos): Boolean {
        val location = toBukkitLocation(world, pos) ?: return false
        return location.block.isEmpty || location.block.type == Material.WATER
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

        class Factory : BlockBehaviorFactory<BuddingAmethystBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): BuddingAmethystBehavior {
                return BuddingAmethystBehavior(
                    customBlock = block,
                    smallBud = section.getString("small-bud", "elitefantasy:small_amethyst_bud"),
                    mediumBud = section.getString("medium-bud", "elitefantasy:medium_amethyst_bud"),
                    largeBud = section.getString("large-bud", "elitefantasy:large_amethyst_bud"),
                    cluster = section.getString("cluster", "elitefantasy:amethyst_cluster"),
                    chance = section.getInt("chance", 5)
                )
            }
        }
    }
}