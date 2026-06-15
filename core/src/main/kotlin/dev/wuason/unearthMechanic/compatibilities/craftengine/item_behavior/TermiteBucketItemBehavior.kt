package dev.wuason.unearthMechanic.compatibilities.craftengine.item_behavior

import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite.MythicTermites
import dev.wuason.unearthMechanic.utils.FoliaUtils
import net.momirealms.craftengine.core.entity.player.InteractionResult
import net.momirealms.craftengine.core.item.behavior.ItemBehavior
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory
import net.momirealms.craftengine.core.pack.Pack
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.util.Key
import net.momirealms.craftengine.core.world.context.UseOnContext
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import java.nio.file.Path
import java.util.UUID

class TermiteBucketItemBehavior(
    private val emptyBucketMaterial: Material
) : ItemBehavior() {

    override fun useOnBlock(context: UseOnContext): InteractionResult {
        val player = resolveBukkitPlayer(context.player) ?: return InteractionResult.PASS
        val world = Bukkit.getWorld(context.level.name()) ?: return InteractionResult.PASS
        val pos = context.clickedPos
        val location = org.bukkit.Location(
            world,
            pos.x().toDouble() + 0.5,
            pos.y().toDouble() + 1.0,
            pos.z().toDouble() + 0.5
        )

        FoliaUtils.runAtLocation(location) {
            MythicTermites.spawnFriendly(location)

            FoliaUtils.runAtEntity(player) {
                if (player.gameMode != GameMode.CREATIVE) {
                    replaceOneTermiteBucketWithEmptyBucket(player)
                }

                player.swingMainHand()
            }
        }

        return InteractionResult.SUCCESS_AND_CANCEL
    }

    private fun replaceOneTermiteBucketWithEmptyBucket(player: org.bukkit.entity.Player) {
        val item = player.inventory.itemInMainHand
        val emptyBucket = ItemStack(emptyBucketMaterial)

        if (item.amount <= 1) {
            player.inventory.setItemInMainHand(emptyBucket)
            return
        }

        item.amount -= 1
        player.inventory.setItemInMainHand(item)
        player.inventory.addItem(emptyBucket).values.forEach { leftover ->
            val dropLocation = player.location.clone()
            FoliaUtils.runAtLocation(dropLocation) {
                dropLocation.world?.dropItemNaturally(dropLocation, leftover)
            }
        }
    }

    private fun resolveBukkitPlayer(player: Any?): org.bukkit.entity.Player? {
        if (player == null) return null
        if (player is org.bukkit.entity.Player) return player

        val uuid = runCatching {
            player.javaClass.getMethod("uuid").invoke(player) as? UUID
        }.getOrNull()

        if (uuid != null) return Bukkit.getPlayer(uuid)

        val uniqueId = runCatching {
            player.javaClass.getMethod("uniqueId").invoke(player) as? UUID
        }.getOrNull()

        if (uniqueId != null) return Bukkit.getPlayer(uniqueId)

        return null
    }

    companion object {
        val FACTORY: ItemBehaviorFactory<TermiteBucketItemBehavior> =
            ItemBehaviorFactory { _: Pack, _: Path, _: Key, section: ConfigSection ->
                TermiteBucketItemBehavior(
                    emptyBucketMaterial = parseMaterial(section.getString("empty-bucket-material", "BUCKET"))
                )
            }

        private fun parseMaterial(raw: String): Material {
            val normalized = raw
                .substringAfter(':')
                .uppercase()
                .replace('-', '_')

            return Material.matchMaterial(normalized) ?: Material.BUCKET
        }
    }
}