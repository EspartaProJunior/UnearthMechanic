package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.meerkat_cache

import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.plugin.config.ConfigSection

class MeerkatCacheSandBehavior(
    customBlock: BlockDefinition
) : BukkitBlockBehavior(customBlock) {
    companion object {
        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<MeerkatCacheSandBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): MeerkatCacheSandBehavior {
                MeerkatCacheGameplay.configureFromBehavior(section)
                return MeerkatCacheSandBehavior(block)
            }
        }
    }
}
