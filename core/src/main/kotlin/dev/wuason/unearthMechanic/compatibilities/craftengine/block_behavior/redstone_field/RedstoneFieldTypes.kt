package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field

enum class FieldType {
    ICE,
    FIRE;

    companion object {
        fun fromConfig(value: String): FieldType {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ICE
        }

        fun setFromConfig(value: String, default: Set<FieldType> = setOf(ICE)): Set<FieldType> {
            val parsed = value
                .split(',', ';', '|')
                .mapNotNull { raw ->
                    entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                }
                .toSet()

            return parsed.ifEmpty { default }
        }
    }
}

enum class ResonatorOutputMode {
    INHERIT,
    ICE,
    FIRE;

    companion object {
        fun fromConfig(value: String): ResonatorOutputMode {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: INHERIT
        }
    }
}

internal enum class SourceKind {
    REDSTONE,
    RESONATOR
}

internal data class SourceKey(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val kind: SourceKind
)

internal data class FieldSource(
    val key: SourceKey,
    val type: FieldType,
    val radius: Int,
    val particleCount: Int,
    val safeRadius: Double,
    val ice: IceSettings,
    val fire: FireSettings
)

internal data class ResonatorSource(
    val key: SourceKey,
    val triggerTypes: Set<FieldType>,
    val outputMode: ResonatorOutputMode,
    val radius: Int,
    val particleCount: Int,
    val safeRadius: Double,
    val resonanceTicks: Long,
    val ice: IceSettings,
    val fire: FireSettings
)

data class IceSettings(
    val slownessAmplifier: Int = 4,
    val slowFallingAmplifier: Int = 0,
    val leatherArmorEffectReduction: Int = 1,
    val fullLeatherPreventsFreeze: Boolean = true
)

data class FireSettings(
    val fireTicks: Int = 80,
    val igniteBlocks: Boolean = true,
    val blockIgniteAttempts: Int = 12
)

