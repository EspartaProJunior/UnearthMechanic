package dev.wuason.unearthMechanic.compatibilities.craftengine.item_behavior.crop_accelerator

import dev.wuason.unearthMechanic.utils.FoliaUtils
import net.momirealms.craftengine.bukkit.api.CraftEngineItems
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
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class CropAcceleratorSettings(
    val validCrops: Set<Material>,
    val multiplier: Int,
    val consumeItem: Boolean
)

object CropAcceleratorRegistry {
    private val settingsByItemId = ConcurrentHashMap<String, CropAcceleratorSettings>()

    fun register(itemId: Key, settings: CropAcceleratorSettings) {
        settingsByItemId[itemId.toString().lowercase()] = settings
    }

    fun settingsFor(stack: ItemStack): CropAcceleratorSettings? {
        val customId = CraftEngineItems.getCustomItemId(stack)?.toString()?.lowercase()
            ?: return null
        return settingsByItemId[customId]
    }
}

object CropAccelerator {
    fun canApply(block: Block, settings: CropAcceleratorSettings): Boolean {
        if (block.type !in settings.validCrops) return false
        val ageable = block.blockData as? Ageable ?: return false
        return ageable.age >= ageable.maximumAge
    }

    fun harvestAndReset(block: Block, tool: ItemStack?, settings: CropAcceleratorSettings) {
        val drops = if (tool != null) block.getDrops(tool).map { it.clone() } else block.getDrops().map { it.clone() }
        val dropLocation = block.location.add(0.5, 0.5, 0.5)

        repeat(settings.multiplier.coerceAtLeast(1)) {
            for (drop in drops) {
                block.world.dropItemNaturally(dropLocation, drop.clone())
            }
        }

        val ageable = block.blockData as? Ageable ?: return
        ageable.age = 0
        block.blockData = ageable
    }

    fun playApplyFx(block: Block) {
        val location = block.location.add(0.5, 0.55, 0.5)
        block.world.playSound(location, Sound.ITEM_BONE_MEAL_USE, 0.75f, 1.2f)
        block.world.spawnParticle(
            Particle.COMPOSTER,
            location,
            18,
            0.32,
            0.22,
            0.32,
            0.02
        )
    }
}

class CropAcceleratorItemBehavior(
    private val settings: CropAcceleratorSettings
) : ItemBehavior() {

    override fun useOnBlock(context: UseOnContext): InteractionResult {
        val player = resolveBukkitPlayer(context.player) ?: return InteractionResult.PASS
        val world = Bukkit.getWorld(context.level.name()) ?: return InteractionResult.PASS
        val pos = context.clickedPos
        val block = world.getBlockAt(pos.x(), pos.y(), pos.z())

        if (block.type in settings.validCrops && !CropAccelerator.canApply(block, settings)) {
            return InteractionResult.SUCCESS_AND_CANCEL
        }
        if (!CropAccelerator.canApply(block, settings)) return InteractionResult.PASS

        FoliaUtils.runAtLocation(block.location) {
            CropAccelerator.harvestAndReset(block, player.inventory.itemInMainHand, settings)
            CropAccelerator.playApplyFx(block)

            FoliaUtils.runAtEntity(player) {
                if (settings.consumeItem && player.gameMode != GameMode.CREATIVE) {
                    val item = player.inventory.itemInMainHand
                    if (item.amount <= 1) {
                        player.inventory.setItemInMainHand(ItemStack(Material.AIR))
                    } else {
                        item.amount -= 1
                        player.inventory.setItemInMainHand(item)
                    }
                }
                player.swingMainHand()
            }
        }

        return InteractionResult.SUCCESS_AND_CANCEL
    }

    private fun resolveBukkitPlayer(player: Any?): Player? {
        if (player == null) return null
        if (player is Player) return player

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
        val FACTORY: ItemBehaviorFactory<CropAcceleratorItemBehavior> =
            ItemBehaviorFactory { _: Pack, _: Path, key: Key, section: ConfigSection ->
                val settings = CropAcceleratorSettings(
                    validCrops = materialsFromList(
                        section.getStringList("valid-crops"),
                        defaultCrops()
                    ),
                    multiplier = section.getInt("multiplier", 2).coerceAtLeast(2),
                    consumeItem = section.getBoolean("consume-item", true)
                )
                CropAcceleratorRegistry.register(key, settings)
                CropAcceleratorItemBehavior(settings)
            }

        private fun defaultCrops(): Set<Material> = setOf(
            Material.WHEAT,
            Material.CARROTS,
            Material.POTATOES,
            Material.BEETROOTS,
            Material.NETHER_WART
        )

        private fun materialsFromList(raw: List<String>, fallback: Set<Material>): Set<Material> {
            val parsed = raw.mapNotNull { materialFromConfig(it) }.toSet()
            return parsed.ifEmpty { fallback }
        }

        private fun materialFromConfig(raw: String): Material? {
            val normalized = raw.substringAfter(':').uppercase().replace('-', '_')
            return Material.matchMaterial(normalized)
        }
    }
}