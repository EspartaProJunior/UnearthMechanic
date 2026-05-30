package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.mini_cubes

enum class MiniCubeMode {
    FULL_8,
    LOWER_4,
    VERTICAL_SLAB_LIKE;

    companion object {
        fun from(raw: String?): MiniCubeMode {
            return when (raw?.lowercase()) {
                "full_8", "8", "8x8x8", "full" -> FULL_8
                "lower_4", "4", "2x1x2" -> LOWER_4
                "vertical_slab_like", "vertical_slab" -> VERTICAL_SLAB_LIKE
                else -> FULL_8
            }
        }
    }
}