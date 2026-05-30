package dev.wuason.unearthMechanic.compatibilities.craftengine.item_behavior

import net.momirealms.craftengine.bukkit.block.BukkitBlockManager
import net.momirealms.craftengine.bukkit.item.behavior.BlockItemBehavior
import net.momirealms.craftengine.core.entity.player.InteractionResult
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory
import net.momirealms.craftengine.core.pack.Pack
import net.momirealms.craftengine.core.pack.PendingConfigSection
import net.momirealms.craftengine.core.plugin.config.ConfigConstants
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.plugin.config.ConfigValue
import net.momirealms.craftengine.core.util.Key
import net.momirealms.craftengine.core.world.context.BlockPlaceContext
import net.momirealms.craftengine.core.world.context.UseOnContext
import java.nio.file.Path

class ShiftPlaceBlockItemBehavior(
    blockId: Key
) : BlockItemBehavior(blockId) {

    override fun useOnBlock(context: UseOnContext): InteractionResult {
        if (!context.isSecondaryUseActive) {
            return InteractionResult.PASS
        }

        return place(BlockPlaceContext(context))
    }

    companion object {
        val FACTORY: ItemBehaviorFactory<ShiftPlaceBlockItemBehavior> =
            ItemBehaviorFactory { pack: Pack, path: Path, key: Key, section: ConfigSection ->
                val blockValue: ConfigValue = section.getNonNullValue(
                    "block",
                    ConfigConstants.ARGUMENT_SECTION
                )

                if (blockValue.`is`(Map::class.java)) {
                    BukkitBlockManager.instance().blockParser().addPendingConfigSection(
                        PendingConfigSection(pack, path, key, blockValue.asSection)
                    )

                    ShiftPlaceBlockItemBehavior(key)
                } else {
                    ShiftPlaceBlockItemBehavior(blockValue.asIdentifier)
                }
            }
    }
}