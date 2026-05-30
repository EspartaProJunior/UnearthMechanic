package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.mini_cubes

import net.momirealms.craftengine.core.util.Direction
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import org.bukkit.Bukkit
import org.bukkit.block.BlockFace
import org.bukkit.util.BoundingBox
import java.util.UUID

object MiniCubeRaytrace {

    data class Hit(
        val bit: Int,
        val face: Direction
    )

    fun findTargetedHit(
        player: Any,
        world: World,
        pos: BlockPos,
        mask: Int
    ): Hit? {
        val bukkitPlayer = resolveBukkitPlayer(player) ?: return fallback(mask)

        val eye = bukkitPlayer.eyeLocation.toVector()
        val direction = bukkitPlayer.eyeLocation.direction.normalize()
        val maxDistance = 6.0

        var bestBit = -1
        var bestFace: Direction? = null
        var bestDistance = Double.MAX_VALUE

        for (bit in 0..7) {
            if (!MiniCubeMask.has(mask, bit)) continue

            val box = boxForBit(pos, bit)
            val hit = box.rayTrace(eye, direction, maxDistance) ?: continue
            val distance = eye.distanceSquared(hit.hitPosition)

            if (distance < bestDistance) {
                bestDistance = distance
                bestBit = bit
                bestFace = blockFaceToDirection(hit.hitBlockFace)
            }
        }

        if (bestBit != -1 && bestFace != null) {
            return Hit(bestBit, bestFace)
        }

        return null
    }

    fun findHitOnClickedFace(
        mask: Int,
        face: Direction
    ): Hit? {
        for (bit in 0..7) {
            if (!MiniCubeMask.has(mask, bit)) continue

            val matchesFace = when (face) {
                Direction.UP -> MiniCubeMask.y(bit) == 1
                Direction.DOWN -> MiniCubeMask.y(bit) == 0
                Direction.EAST -> MiniCubeMask.x(bit) == 1
                Direction.WEST -> MiniCubeMask.x(bit) == 0
                Direction.SOUTH -> MiniCubeMask.z(bit) == 1
                Direction.NORTH -> MiniCubeMask.z(bit) == 0
                else -> false
            }

            if (matchesFace) {
                return Hit(bit, face)
            }
        }

        return null
    }

    fun findNearestActiveHit(
        player: org.bukkit.entity.Player,
        pos: BlockPos,
        mask: Int,
        fallbackFace: Direction
    ): Hit? {
        val hit = player.rayTraceBlocks(6.0)
        val hitPos = hit?.hitPosition ?: return fallback(mask)

        val face = blockFaceToDirection(hit.hitBlockFace) ?: fallbackFace

        val localX = (hitPos.x - pos.x()).coerceIn(0.0, 0.999999)
        val localY = (hitPos.y - pos.y()).coerceIn(0.0, 0.999999)
        val localZ = (hitPos.z - pos.z()).coerceIn(0.0, 0.999999)

        var bestBit = -1
        var bestDistance = Double.MAX_VALUE

        for (bit in 0..7) {
            if (!MiniCubeMask.has(mask, bit)) continue

            val centerX = MiniCubeMask.x(bit) * 0.5 + 0.25
            val centerY = MiniCubeMask.y(bit) * 0.5 + 0.25
            val centerZ = MiniCubeMask.z(bit) * 0.5 + 0.25

            val dx = localX - centerX
            val dy = localY - centerY
            val dz = localZ - centerZ
            val distance = dx * dx + dy * dy + dz * dz

            if (distance < bestDistance) {
                bestDistance = distance
                bestBit = bit
            }
        }

        if (!MiniCubeMask.validBit(bestBit)) return fallback(mask)

        return Hit(bestBit, face)
    }

    fun findTargetedBit(
        player: Any,
        world: World,
        pos: BlockPos,
        mask: Int
    ): Int {
        return findTargetedHit(player, world, pos, mask)?.bit ?: -1
    }

    private fun fallback(mask: Int): Hit? {
        val bit = MiniCubeMask.firstActiveBit(mask)
        if (!MiniCubeMask.validBit(bit)) return null
        return Hit(bit, Direction.UP)
    }

    private fun boxForBit(pos: BlockPos, bit: Int): BoundingBox {
        val x = MiniCubeMask.x(bit)
        val y = MiniCubeMask.y(bit)
        val z = MiniCubeMask.z(bit)

        val minX = pos.x() + if (x == 0) 0.0 else 0.5
        val minY = pos.y() + if (y == 0) 0.0 else 0.5
        val minZ = pos.z() + if (z == 0) 0.0 else 0.5

        val maxX = minX + 0.5
        val maxY = minY + 0.5
        val maxZ = minZ + 0.5

        return BoundingBox(minX, minY, minZ, maxX, maxY, maxZ)
    }

    private fun blockFaceToDirection(face: BlockFace?): Direction? {
        return when (face) {
            BlockFace.UP -> Direction.UP
            BlockFace.DOWN -> Direction.DOWN
            BlockFace.EAST -> Direction.EAST
            BlockFace.WEST -> Direction.WEST
            BlockFace.SOUTH -> Direction.SOUTH
            BlockFace.NORTH -> Direction.NORTH
            else -> null
        }
    }

    private fun resolveBukkitPlayer(player: Any): org.bukkit.entity.Player? {
        val uuid = runCatching {
            player.javaClass.getMethod("uuid").invoke(player) as? UUID
        }.getOrNull()

        if (uuid != null) {
            return Bukkit.getPlayer(uuid)
        }

        val uniqueId = runCatching {
            player.javaClass.getMethod("uniqueId").invoke(player) as? UUID
        }.getOrNull()

        if (uniqueId != null) {
            return Bukkit.getPlayer(uniqueId)
        }

        return null
    }
}