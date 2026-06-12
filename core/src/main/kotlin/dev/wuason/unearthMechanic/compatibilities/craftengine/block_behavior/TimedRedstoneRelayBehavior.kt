package dev.wuason.unearthMechanic.compatibilities.craftengine

import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.bukkit.world.BukkitWorld
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.UpdateFlags
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap

class TimedRedstoneRelayBehavior(
    customBlock: BlockDefinition,
    private val secondsPerPower: Int = 60,
    private val propertyName: String = "redstone_power"
) : BukkitBlockBehavior(customBlock) {

    override fun neighborChanged(thisBlock: Any, args: Array<Any>) {
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: return
        val location = toBukkitLocation(world, pos) ?: return
        val key = RelayKey(world.name(), pos.x(), pos.y(), pos.z())

        val inputPower = location.block.blockPower.coerceIn(0, 15)

        if (inputPower <= 0) {
            inputLocks.remove(key)
            return
        }

        if (inputLocks.containsKey(key) || getPowerState(world, pos) > 0) return

        inputLocks[key] = true
        setPowerState(world, pos, inputPower)

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            setPowerState(world, pos, 0)
        }, inputPower * secondsPerPower.coerceAtLeast(1) * 20L)
    }

    override fun getSignal(thisBlock: Any, args: Array<Any>): Int {
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: return 0
        return getPowerState(world, pos)
    }

    override fun getDirectSignal(thisBlock: Any, args: Array<Any>): Int {
        return getSignal(thisBlock, args)
    }

    override fun isSignalSource(thisBlock: Any, args: Array<Any>): Boolean {
        return true
    }

    override fun affectNeighborsAfterRemoval(thisBlock: Any, args: Array<Any>) {
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: return
        inputLocks.remove(RelayKey(world.name(), pos.x(), pos.y(), pos.z()))
    }

    private fun getPowerState(world: World, pos: BlockPos): Int {
        val state = world.getBlock(pos).customBlockState() ?: return 0
        val property = state.owner().value().getProperty(propertyName) ?: return 0

        @Suppress("UNCHECKED_CAST")
        return state.get(property as Property<Int>).coerceIn(0, 15)
    }

    private fun setPowerState(world: World, pos: BlockPos, power: Int) {
        if (world !is BukkitWorld) return

        val state = world.getBlock(pos).customBlockState() ?: return
        val property = state.owner().value().getProperty(propertyName) ?: return

        @Suppress("UNCHECKED_CAST")
        val nextState = state.with(property as Property<Int>, power.coerceIn(0, 15))

        BukkitAdaptor.adapt(Bukkit.getWorld(world.name()) ?: return).setBlockState(
            pos.x(),
            pos.y(),
            pos.z(),
            nextState,
            UpdateFlags.UPDATE_ALL
        )
    }

    private fun worldAndPosFromNmsArgs(args: Array<Any>): Pair<World, BlockPos>? {
        val nmsWorld = args.getOrNull(1) ?: return null
        val nmsPos = args.getOrNull(2) ?: return null

        val bukkitWorld = Bukkit.getWorlds().firstOrNull { world ->
            world.javaClass.getMethod("getHandle").invoke(world) == nmsWorld
        } ?: return null

        val x = nmsPos.javaClass.getMethod("getX").invoke(nmsPos) as Int
        val y = nmsPos.javaClass.getMethod("getY").invoke(nmsPos) as Int
        val z = nmsPos.javaClass.getMethod("getZ").invoke(nmsPos) as Int

        return BukkitAdaptor.adapt(bukkitWorld) to BlockPos(x, y, z)
    }

    private fun toBukkitLocation(world: World, pos: BlockPos): Location? {
        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return null
        return Location(bukkitWorld, pos.x().toDouble(), pos.y().toDouble(), pos.z().toDouble())
    }

    companion object {
        private val plugin: JavaPlugin by lazy {
            JavaPlugin.getProvidingPlugin(TimedRedstoneRelayBehavior::class.java)
        }

        private val inputLocks = ConcurrentHashMap<RelayKey, Boolean>()

        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<TimedRedstoneRelayBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): TimedRedstoneRelayBehavior {
                return TimedRedstoneRelayBehavior(
                    customBlock = block,
                    secondsPerPower = section.getInt("seconds-per-power", 60),
                    propertyName = section.getString("property", "redstone_power")
                )
            }
        }
    }

    private data class RelayKey(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int
    )
}