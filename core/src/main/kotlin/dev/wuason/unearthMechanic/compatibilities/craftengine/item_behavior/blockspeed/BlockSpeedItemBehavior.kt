package dev.wuason.unearthMechanic.compatibilities.craftengine.item_behavior.blockspeed

import net.momirealms.craftengine.core.item.behavior.ItemBehavior
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory
import net.momirealms.craftengine.core.pack.Pack
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.util.Key
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class BlockSpeedItemBehavior(
    val speedAmplifier: Int,
    val durationTicks: Int,
    val blocks: Set<String>
) : ItemBehavior() {

    companion object {
        private val configuredItems: MutableMap<Key, BlockSpeedItemBehavior> = ConcurrentHashMap()

        fun behaviorFor(itemKey: Key): BlockSpeedItemBehavior? = configuredItems[itemKey]

        val FACTORY: ItemBehaviorFactory<BlockSpeedItemBehavior> =
            ItemBehaviorFactory { _: Pack, _: Path, key: Key, section: ConfigSection ->
                val speedAmplifier = section.getInt("speed-amplifier", 0)
                    .coerceAtLeast(0)

                val durationTicks = section.getInt("duration-ticks", 40)
                    .coerceAtLeast(2)

                val blocks = section.getStringList("blocks", emptyList())
                    .mapTo(mutableSetOf()) { it.uppercase() }

                BlockSpeedItemBehavior(speedAmplifier, durationTicks, blocks).also {
                    configuredItems[key] = it
                }
            }
    }
}