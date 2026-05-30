package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.mini_cubes

import dev.wuason.unearthMechanic.compatibilities.craftengine.types.MiniCubeMaskState

object MiniCubeMask {

    const val MIN_MASK = 1
    const val MAX_MASK = 255

    fun has(mask: Int, bit: Int): Boolean {
        return (mask and (1 shl bit)) != 0
    }

    fun add(mask: Int, bit: Int): Int {
        return mask or (1 shl bit)
    }

    fun remove(mask: Int, bit: Int): Int {
        return mask and (1 shl bit).inv()
    }

    fun toggle(mask: Int, bit: Int): Int {
        return mask xor (1 shl bit)
    }

    fun count(mask: Int): Int {
        return Integer.bitCount(mask)
    }

    fun bit(x: Int, y: Int, z: Int): Int {
        return x + z * 2 + y * 4
    }

    fun x(bit: Int): Int {
        return bit and 1
    }

    fun z(bit: Int): Int {
        return (bit shr 1) and 1
    }

    fun y(bit: Int): Int {
        return (bit shr 2) and 1
    }

    fun validBit(bit: Int): Boolean {
        return bit in 0..7
    }

    fun normalize(mask: Int): Int {
        return mask.coerceIn(MIN_MASK, MAX_MASK)
    }

    fun state(mask: Int): MiniCubeMaskState {
        return MiniCubeMaskState.fromMask(normalize(mask))
    }

    fun mask(state: MiniCubeMaskState): Int {
        return state.mask
    }

    fun modelSuffix(mask: Int): String {
        return Integer.toBinaryString(normalize(mask))
    }

    fun firstActiveBit(mask: Int): Int {
        for (bit in 0..7) {
            if (has(mask, bit)) return bit
        }
        return -1
    }
}