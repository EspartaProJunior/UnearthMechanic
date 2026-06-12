package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite

import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory

class TermiteHollowLogBehavior(
    customBlock: BlockDefinition
) : BukkitBlockBehavior(customBlock) {
    companion object {
        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<TermiteHollowLogBehavior> {
            override fun create(
                block: BlockDefinition,
                section: net.momirealms.craftengine.core.plugin.config.ConfigSection
            ): TermiteHollowLogBehavior {
                return TermiteHollowLogBehavior(block)
            }
        }
    }
}
