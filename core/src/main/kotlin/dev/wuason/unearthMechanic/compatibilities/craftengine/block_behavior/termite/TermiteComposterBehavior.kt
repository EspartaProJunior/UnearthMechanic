package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite

import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.plugin.config.ConfigSection

class TermiteComposterBehavior(
    customBlock: BlockDefinition,
    val foodPerWood: Int,
    val foodToBefriend: Int,
    val nestRadius: Int
) : BukkitBlockBehavior(customBlock) {

    companion object {
        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<TermiteComposterBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): TermiteComposterBehavior {
                TermiteListener.configureComposter(
                    foodPerWood = section.getInt("food-per-wood", 1),
                    foodToBefriend = section.getInt("food-to-befriend", 64),
                    nestRadius = section.getInt("nest-radius", 12)
                )

                return TermiteComposterBehavior(
                    customBlock = block,
                    foodPerWood = section.getInt("food-per-wood", 1),
                    foodToBefriend = section.getInt("food-to-befriend", 64),
                    nestRadius = section.getInt("nest-radius", 12)
                )
            }
        }
    }
}
