package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.mini_cubes

import net.momirealms.craftengine.core.util.Direction
import net.momirealms.craftengine.core.world.BlockPos

object MiniCubePlacement {

    data class Target(
        val pos: BlockPos,
        val bit: Int
    )

    fun computeTarget(
        pos: BlockPos,
        hitBit: Int,
        face: Direction
    ): Target? {
        if (!MiniCubeMask.validBit(hitBit)) return null

        var subX = MiniCubeMask.x(hitBit)
        var subY = MiniCubeMask.y(hitBit)
        var subZ = MiniCubeMask.z(hitBit)

        var blockX = pos.x()
        var blockY = pos.y()
        var blockZ = pos.z()

        when (face.name.lowercase()) {
            "up" -> subY += 1
            "down" -> subY -= 1
            "east" -> subX += 1
            "west" -> subX -= 1
            "south" -> subZ += 1
            "north" -> subZ -= 1
        }

        if (subX < 0) {
            blockX -= 1
            subX = 1
        } else if (subX > 1) {
            blockX += 1
            subX = 0
        }

        if (subY < 0) {
            blockY -= 1
            subY = 1
        } else if (subY > 1) {
            blockY += 1
            subY = 0
        }

        if (subZ < 0) {
            blockZ -= 1
            subZ = 1
        } else if (subZ > 1) {
            blockZ += 1
            subZ = 0
        }

        val targetBit = MiniCubeMask.bit(subX, subY, subZ)
        return Target(BlockPos(blockX, blockY, blockZ), targetBit)
    }
}