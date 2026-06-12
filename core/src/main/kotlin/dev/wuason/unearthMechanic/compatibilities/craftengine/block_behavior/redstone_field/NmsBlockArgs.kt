package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field

import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import org.bukkit.Bukkit
import org.bukkit.Location

internal fun worldAndPosFromNmsArgs(args: Array<Any>): Pair<World, BlockPos>? {
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

internal fun toBukkitLocation(world: World, pos: BlockPos): Location? {
    val bukkitWorld = Bukkit.getWorld(world.name()) ?: return null
    return Location(bukkitWorld, pos.x().toDouble(), pos.y().toDouble(), pos.z().toDouble())
}