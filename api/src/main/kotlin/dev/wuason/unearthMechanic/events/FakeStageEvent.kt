package dev.wuason.unearthMechanic.events

import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

/**
 * Fake implementation of a PlayerInteractEvent used to simulate a right-click block interaction.
 *
 * This event is useful when the system needs to reuse logic that depends on
 * PlayerInteractEvent without requiring a real Bukkit interaction from the player.
 *
 * The generated interaction always uses:
 * - Action.RIGHT_CLICK_BLOCK
 * - BlockFace.UP
 */
class FakePlayerInteractEvent(
    player: Player,
    clickedBlock: Block?,
    item: ItemStack?,
    hand: EquipmentSlot?
) : PlayerInteractEvent(
    player,
    Action.RIGHT_CLICK_BLOCK,
    item,
    clickedBlock,
    BlockFace.UP,
    hand
)



