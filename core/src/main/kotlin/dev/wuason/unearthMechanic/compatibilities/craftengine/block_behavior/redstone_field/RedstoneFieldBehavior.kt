package dev.wuason.unearthMechanic.compatibilities.craftengine

import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.FieldSource
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.FieldType
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.FireSettings
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.IceSettings
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.SourceKey
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.SourceKind
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.toBukkitLocation
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.worldAndPosFromNmsArgs
import dev.wuason.unearthMechanic.utils.FoliaUtils
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
import kotlin.math.ceil

class RedstoneFieldBehavior(
    customBlock: BlockDefinition,
    private val fieldType: FieldType = FieldType.ICE,
    private val propertyName: String = "redstone_power",
    private val maxRadius: Int = 8,
    private val scanIntervalTicks: Long = 20L,
    private val particleCount: Int = 8,
    private val safeRadius: Double = 2.25,
    private val iceSettings: IceSettings = IceSettings(),
    private val fireSettings: FireSettings = FireSettings()
) : BukkitBlockBehavior(customBlock) {

    init {
        RedstoneFieldManager.registerFieldDefinition(
            block = customBlock,
            fieldType = fieldType,
            propertyName = propertyName,
            maxRadius = maxRadius,
            particleCount = particleCount,
            safeRadius = safeRadius,
            iceSettings = iceSettings,
            fireSettings = fireSettings
        )

        RedstoneFieldManager.ensureStarted(
            scanIntervalTicks = scanIntervalTicks,
            maxRadius = maxRadius
        )
    }

    override fun onPlace(thisBlock: Any, args: Array<Any>) {
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: return
        val location = toBukkitLocation(world, pos) ?: return

        RedstoneFieldManager.rememberPlacedField(world.name(), pos)

        FoliaUtils.runLater(1L) {
            FoliaUtils.runAtLocation(location) {
                updateFromCurrentRedstone(world, pos)
            }
        }
    }

    override fun neighborChanged(thisBlock: Any, args: Array<Any>) {
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: return
        updateFromCurrentRedstone(world, pos)
    }

    override fun affectNeighborsAfterRemoval(thisBlock: Any, args: Array<Any>) {
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: return
        RedstoneFieldManager.forgetPlacedField(world.name(), pos)
        RedstoneFieldManager.deactivate(SourceKey(world.name(), pos.x(), pos.y(), pos.z(), SourceKind.REDSTONE))
    }

    private fun updateFromCurrentRedstone(world: World, pos: BlockPos) {
        val location = toBukkitLocation(world, pos) ?: return
        val power = location.block.blockPower.coerceIn(0, 15)
        val key = SourceKey(world.name(), pos.x(), pos.y(), pos.z(), SourceKind.REDSTONE)

        setPowerState(world, pos, power)

        if (power > 0) {
            RedstoneFieldManager.activate(
                FieldSource(
                    key = key,
                    type = fieldType,
                    radius = scaledRadius(power, maxRadius),
                    particleCount = particleCount.coerceAtLeast(0),
                    safeRadius = safeRadius.coerceAtLeast(0.0),
                    ice = iceSettings,
                    fire = fireSettings
                )
            )
        } else {
            RedstoneFieldManager.deactivate(key)
        }
    }

    private fun scaledRadius(power: Int, maxRadius: Int): Int {
        if (power <= 0) return 0
        return ceil(power.coerceIn(1, 15) * maxRadius.coerceAtLeast(1) / 15.0).toInt()
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

    companion object {
        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<RedstoneFieldBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): RedstoneFieldBehavior {
                return RedstoneFieldBehavior(
                    customBlock = block,
                    fieldType = FieldType.fromConfig(section.getString("field-type", "ice")),
                    propertyName = section.getString("property", "redstone_power"),
                    maxRadius = section.getInt("max-radius", 8),
                    scanIntervalTicks = section.getInt("scan-interval-ticks", 20).toLong(),
                    particleCount = section.getInt("particle-count", 8),
                    safeRadius = section.getDouble("safe-radius", 2.25),
                    iceSettings = IceSettings(
                        slownessAmplifier = section.getInt("ice.slowness-amplifier", 4),
                        slowFallingAmplifier = section.getInt("ice.slow-falling-amplifier", 0),
                        leatherArmorEffectReduction = section.getInt("ice.leather-armor-effect-reduction", 1),
                        fullLeatherPreventsFreeze = section.getBoolean("ice.full-leather-prevents-freeze", true)
                    ),
                    fireSettings = FireSettings(
                        fireTicks = section.getInt("fire.fire-ticks", 80),
                        igniteBlocks = section.getBoolean("fire.ignite-blocks", true),
                        blockIgniteAttempts = section.getInt("fire.block-ignite-attempts", 12)
                    )
                )
            }
        }
    }
}
