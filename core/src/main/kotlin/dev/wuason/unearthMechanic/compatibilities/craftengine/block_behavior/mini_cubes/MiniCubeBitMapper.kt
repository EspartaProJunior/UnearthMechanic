package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.mini_cubes

import net.momirealms.craftengine.core.util.Direction

object MiniCubeBitMapper {

    fun map(
        bit: Int,
        flipX: Boolean,
        flipY: Boolean,
        flipZ: Boolean
    ): Int {
        if (!MiniCubeMask.validBit(bit)) return bit

        var x = MiniCubeMask.x(bit)
        var y = MiniCubeMask.y(bit)
        var z = MiniCubeMask.z(bit)

        if (flipX) x = 1 - x
        if (flipY) y = 1 - y
        if (flipZ) z = 1 - z

        return MiniCubeMask.bit(x, y, z)
    }

    fun unmap(
        bit: Int,
        flipX: Boolean,
        flipY: Boolean,
        flipZ: Boolean
    ): Int {
        return map(bit, flipX, flipY, flipZ)
    }

    fun unmapDirection(
        direction: Direction,
        flipX: Boolean,
        flipY: Boolean,
        flipZ: Boolean
    ): Direction {
        return when (direction) {
            Direction.EAST -> if (flipX) Direction.WEST else Direction.EAST
            Direction.WEST -> if (flipX) Direction.EAST else Direction.WEST
            Direction.UP -> if (flipY) Direction.DOWN else Direction.UP
            Direction.DOWN -> if (flipY) Direction.UP else Direction.DOWN
            Direction.SOUTH -> if (flipZ) Direction.NORTH else Direction.SOUTH
            Direction.NORTH -> if (flipZ) Direction.SOUTH else Direction.NORTH
            else -> direction
        }
    }

    fun mapMask(
        mask: Int,
        flipX: Boolean,
        flipY: Boolean,
        flipZ: Boolean
    ): Int {
        var result = 0

        for (bit in 0..7) {
            if (!MiniCubeMask.has(mask, bit)) continue

            val mappedBit = map(bit, flipX, flipY, flipZ)
            result = MiniCubeMask.add(result, mappedBit)
        }

        return result
    }
}