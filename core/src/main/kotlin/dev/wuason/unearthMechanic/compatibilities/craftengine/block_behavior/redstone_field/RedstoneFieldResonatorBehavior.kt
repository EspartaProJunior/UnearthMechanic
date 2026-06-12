package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field

import dev.wuason.unearthMechanic.compatibilities.craftengine.RedstoneFieldManager
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.plugin.config.ConfigSection

class RedstoneFieldResonatorBehavior(
    customBlock: BlockDefinition,
    private val triggerTypes: Set<FieldType> = setOf(FieldType.ICE),
    private val outputMode: ResonatorOutputMode = ResonatorOutputMode.INHERIT,
    private val resonanceRadius: Int = 3,
    private val particleCount: Int = 24,
    private val safeRadius: Double = 0.5,
    private val resonanceTicks: Long = 45L,
    private val iceSettings: IceSettings = IceSettings(
        slownessAmplifier = 7,
        slowFallingAmplifier = 1,
        leatherArmorEffectReduction = 1,
        fullLeatherPreventsFreeze = true
    ),
    private val fireSettings: FireSettings = FireSettings(
        fireTicks = 140,
        igniteBlocks = true,
        blockIgniteAttempts = 20
    )
) : BukkitBlockBehavior(customBlock) {

    init {
        RedstoneFieldManager.registerResonatorDefinition(
            block = customBlock,
            triggerTypes = triggerTypes,
            outputMode = outputMode,
            resonanceRadius = resonanceRadius,
            particleCount = particleCount,
            safeRadius = safeRadius,
            resonanceTicks = resonanceTicks,
            iceSettings = iceSettings,
            fireSettings = fireSettings
        )

        RedstoneFieldManager.ensureStarted(
            scanIntervalTicks = 10L,
            maxRadius = resonanceRadius
        )
    }

    override fun onPlace(thisBlock: Any, args: Array<Any>) {
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: return
        RedstoneFieldManager.rememberPlacedResonator(world.name(), pos)

        RedstoneFieldManager.registerResonator(
            ResonatorSource(
                key = SourceKey(world.name(), pos.x(), pos.y(), pos.z(), SourceKind.RESONATOR),
                triggerTypes = triggerTypes,
                outputMode = outputMode,
                radius = resonanceRadius.coerceAtLeast(1),
                particleCount = particleCount.coerceAtLeast(0),
                safeRadius = safeRadius.coerceAtLeast(0.0),
                resonanceTicks = resonanceTicks.coerceAtLeast(1L),
                ice = iceSettings,
                fire = fireSettings
            )
        )
    }

    override fun affectNeighborsAfterRemoval(thisBlock: Any, args: Array<Any>) {
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: return
        val key = SourceKey(world.name(), pos.x(), pos.y(), pos.z(), SourceKind.RESONATOR)

        RedstoneFieldManager.forgetPlacedResonator(world.name(), pos)
        RedstoneFieldManager.unregisterResonator(key)
        RedstoneFieldManager.deactivate(key)
    }

    companion object {
        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<RedstoneFieldResonatorBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): RedstoneFieldResonatorBehavior {
                return RedstoneFieldResonatorBehavior(
                    customBlock = block,
                    triggerTypes = FieldType.setFromConfig(section.getString("trigger-field-types", "ice")),
                    outputMode = ResonatorOutputMode.fromConfig(section.getString("output-field-type", "inherit")),
                    resonanceRadius = section.getInt("resonance-radius", 3),
                    particleCount = section.getInt("particle-count", 24),
                    safeRadius = section.getDouble("safe-radius", 0.5),
                    resonanceTicks = section.getInt("resonance-ticks", 45).toLong(),
                    iceSettings = IceSettings(
                        slownessAmplifier = section.getInt("ice.slowness-amplifier", 7),
                        slowFallingAmplifier = section.getInt("ice.slow-falling-amplifier", 1),
                        leatherArmorEffectReduction = section.getInt("ice.leather-armor-effect-reduction", 1),
                        fullLeatherPreventsFreeze = section.getBoolean("ice.full-leather-prevents-freeze", true)
                    ),
                    fireSettings = FireSettings(
                        fireTicks = section.getInt("fire.fire-ticks", 140),
                        igniteBlocks = section.getBoolean("fire.ignite-blocks", true),
                        blockIgniteAttempts = section.getInt("fire.block-ignite-attempts", 20)
                    )
                )
            }
        }
    }
}