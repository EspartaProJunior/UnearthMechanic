package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.meerkat_cache

import dev.wuason.unearthMechanic.utils.FoliaUtils
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable

class MeerkatCacheListener : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onBrush(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        val brush = event.item ?: return
        if (brush.type != Material.BRUSH) return
        if (!MeerkatCacheGameplay.isCacheBlock(block)) return

        event.isCancelled = true

        playBrushUseAnimation(event.player, block)

        damageBrush(event.player, brush)

        val center = block.location.clone().add(0.5, 0.5, 0.5)
        val revealed = MeerkatCacheGameplay.revealWithBrush(block) ?: return
        revealed.item?.let { block.world.dropItemNaturally(center, it) }
        block.world.playSound(center, Sound.BLOCK_SUSPICIOUS_SAND_BREAK, 0.9f, 1.1f)
        block.world.spawnParticle(
            Particle.BLOCK,
            center,
            32,
            0.4,
            0.3,
            0.4,
            0.03,
            Material.SAND.createBlockData()
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val block = event.block
        if (!MeerkatCacheGameplay.isCacheBlock(block) && !MeerkatCacheGameplay.isBurrowBlock(block)) return

        event.isDropItems = false
        val reveal = MeerkatCacheGameplay.breakCacheBlock(block) ?: return
        reveal.item?.let { block.world.dropItemNaturally(block.location.add(0.5, 0.5, 0.5), it) }
    }

    private fun playBrushUseAnimation(player: org.bukkit.entity.Player, block: org.bukkit.block.Block) {
        repeat(6) { step ->
            FoliaUtils.runLater(step * 2L) {
                if (!block.isValidBrushTarget()) return@runLater
                val center = block.location.clone().add(0.5, 0.5, 0.5)
                player.swingMainHand()
                block.world.playSound(center, Sound.ITEM_BRUSH_BRUSHING_SAND, 0.55f, 0.9f + step * 0.04f)
                block.world.spawnParticle(
                    Particle.BLOCK,
                    center,
                    8,
                    0.35,
                    0.2,
                    0.35,
                    0.015,
                    Material.SAND.createBlockData()
                )
            }
        }
    }

    private fun org.bukkit.block.Block.isValidBrushTarget(): Boolean =
        type != Material.AIR && MeerkatCacheGameplay.isCacheBlock(this)

    private fun damageBrush(player: org.bukkit.entity.Player, stack: ItemStack) {
        if (player.gameMode == GameMode.CREATIVE) return

        val meta = stack.itemMeta as? Damageable ?: return
        meta.damage += 1
        stack.itemMeta = meta

        if (meta.damage >= stack.type.maxDurability.toInt()) {
            stack.amount -= 1
            player.world.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 0.8f, 1.0f)
        }
    }
}
