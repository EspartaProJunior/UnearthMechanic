package dev.wuason.unearthMechanic.system.features

import dev.wuason.adapter.Adapter
import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.IStage
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import dev.wuason.unearthMechanic.utils.FoliaUtils
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.inventory.ItemStack
import kotlin.jvm.optionals.getOrNull

class BasicFeatures: AbstractFeature() {

    override fun onProcess(
        tick: Long,
        p: Player,
        comp: ICompatibility,
        event: Event,
        loc: Location,
        liveTool: ILiveTool,
        iStage: IStage,
        iGeneric: IGeneric
    ) {
    }

    private fun countToolInInventory(player: Player, toolAdapterData: AdapterData): Int {
        return (0..35).sumOf { slot ->
            val item = player.inventory.getItem(slot) ?: return@sumOf 0
            if (item.type.isAir) return@sumOf 0
            val data = Adapter.getAdapterData(Adapter.getAdapterId(item)).getOrNull() ?: return@sumOf 0
            if (data == toolAdapterData) item.amount else 0
        }
    }

    private fun removeToolFromInventory(player: Player, toolAdapterData: AdapterData, amount: Int) {
        var remaining = amount
        for (slot in 0..35) {
            if (remaining <= 0) break
            val item = player.inventory.getItem(slot) ?: continue
            if (item.type.isAir) continue
            val data = Adapter.getAdapterData(Adapter.getAdapterId(item)).getOrNull() ?: continue
            if (data != toolAdapterData) continue
            val toRemove = minOf(remaining, item.amount)
            val newAmount = item.amount - toRemove
            player.inventory.setItem(slot, if (newAmount <= 0) null else item.clone().apply { this.amount = newAmount })
            remaining -= toRemove
        }
    }

    override fun onApply(
        p: Player,
        comp: ICompatibility,
        event: Event,
        loc: Location,
        liveTool: ILiveTool,
        iStage: IStage,
        iGeneric: IGeneric
    ) {
        val plugin = UnearthMechanic.getInstance()
        val previousHeldSlot = p.inventory.heldItemSlot
        val shouldDelayItemsAdd = iStage.getItems().isNotEmpty()

        val reduceInv = iStage.getReduceItemInventory()
        val reduceHand = iStage.getReduceItemHand()
        val toolAdapterData = liveTool.getITool().getAdapterData()

        var batches = when {
            reduceInv > 0 && p.gameMode != GameMode.CREATIVE -> {
                maxOf(1, countToolInInventory(p, toolAdapterData) / reduceInv)
            }

            reduceHand > 0 && p.gameMode != GameMode.CREATIVE -> {
                if (liveTool.getItemMainHand() == p.inventory.itemInMainHand) 1 else 0
            }

            else -> 1
        }

        // First, we modify the hand.
        // This allows `items_add` to occupy the previous slot one tick later.
        if (p.gameMode != GameMode.CREATIVE) {
            if (iStage.isRemoveItemMainHand()) {
                liveTool.setItemMainHand(ItemStack(Material.AIR))

                plugin.getStageManager().getAnimator().getAnimation(p)?.let { anim ->
                    anim.updateItemMainHandData()
                }
            }

            if (reduceHand > 0) {
                val currentItem = liveTool.getItemMainHand()

                if (currentItem != null && !currentItem.type.isAir) {
                    val remaining = currentItem.amount - reduceHand

                    val updatedItem = if (remaining <= 0) {
                        ItemStack(Material.AIR)
                    } else {
                        currentItem.clone().apply {
                            amount = remaining
                        }
                    }

                    // Updates the actual item or the saved item behind the animation.
                    liveTool.setItemMainHand(updatedItem)
                }
            }
        }

        if (reduceInv > 0 && p.gameMode != GameMode.CREATIVE) {
            if (iStage.isBatchedProcess()) {
                removeToolFromInventory(p, toolAdapterData, batches * reduceInv)
            } else {
                removeToolFromInventory(p, toolAdapterData, reduceInv)
                batches = 1
            }
        }

        if (iStage.getDrops().isNotEmpty()) repeat(batches) { iStage.dropItems(loc, p) }

        // Then we call `items_add` one tick later
        // If the hand slot is empty, the first item is placed there
        if (shouldDelayItemsAdd) {
            FoliaUtils.runLater(1L) {
                val player = Bukkit.getPlayer(p.uniqueId) ?: return@runLater
                if (!player.isOnline) return@runLater

                FoliaUtils.runAtEntity(player) {
                    val before = player.inventory.contents.map { it?.clone() }.toTypedArray()

                    repeat(batches) { iStage.addItems(player) }

                    //iStage.addItems(player)

                    moveNewlyAddedItemToPreviousSlot(player, previousHeldSlot, before)

                    plugin.getStageManager().getAnimator().getAnimation(player)?.let { anim ->
                        anim.updateItemMainHandData()
                    }

                    player.updateInventory()
                }
            }
        }

        if (iStage.getSounds().isNotEmpty()) {
            iStage.getSounds().forEach { sound ->
                if (sound.delay > 0) {
                    FoliaUtils.runLater(sound.delay) {
                        FoliaUtils.runAtLocation(loc) {
                            loc.world?.playSound(
                                loc,
                                sound.soundId,
                                SoundCategory.BLOCKS,
                                sound.volume,
                                sound.pitch
                            )
                        }
                    }
                } else {
                    FoliaUtils.runAtLocation(loc) {
                        loc.world?.playSound(
                            loc,
                            sound.soundId,
                            SoundCategory.BLOCKS,
                            sound.volume,
                            sound.pitch
                        )
                    }
                }
            }
        }
    }

    private fun moveNewlyAddedItemToPreviousSlot(
        player: Player,
        previousHeldSlot: Int,
        before: Array<ItemStack?>
    ) {
        val plugin = UnearthMechanic.getInstance()
        val inventory = player.inventory

        val animation = plugin
            .getStageManager()
            .getAnimator()
            .getAnimation(player)
            ?.takeIf {
                it.isRunning() &&
                        previousHeldSlot == inventory.heldItemSlot
            }

        /*
         * During the animation, the slot contains the temporary visual element.
         * The actual item is stored within the AnimationRunner.
         */
        val currentSlotItem =
            animation?.getItemMainHand()
                ?: inventory.getItem(previousHeldSlot)

        if (currentSlotItem != null && !currentSlotItem.type.isAir) {
            return
        }

        fun setPreviousHeldItem(item: ItemStack) {
            if (animation != null) {
                animation.setItemMainHand(item)
            } else {
                inventory.setItem(previousHeldSlot, item)
            }
        }

        val after = inventory.contents

        // Search for a new item placed in a slot that was previously empty.
        for (slot in after.indices) {
            if (slot == previousHeldSlot) continue

            val beforeItem = before.getOrNull(slot)
            val afterItem = after[slot]

            if (
                (beforeItem == null || beforeItem.type.isAir) &&
                afterItem != null &&
                !afterItem.type.isAir
            ) {
                setPreviousHeldItem(afterItem.clone())
                inventory.setItem(slot, ItemStack(Material.AIR))
                return
            }
        }

        // Find a new item that has been added to the top of another stack.
        for (slot in after.indices) {
            if (slot == previousHeldSlot) continue

            val beforeItem = before.getOrNull(slot)
            val afterItem = after[slot]

            if (beforeItem == null || afterItem == null) continue
            if (beforeItem.type.isAir || afterItem.type.isAir) continue
            if (!beforeItem.isSimilar(afterItem)) continue
            if (afterItem.amount <= beforeItem.amount) continue

            val addedAmount = afterItem.amount - beforeItem.amount

            val movedItem = afterItem.clone().apply {
                amount = addedAmount
            }

            afterItem.amount -= addedAmount

            if (afterItem.amount <= 0) {
                inventory.setItem(slot, ItemStack(Material.AIR))
            } else {
                inventory.setItem(slot, afterItem)
            }

            setPreviousHeldItem(movedItem)
            return
        }
    }
}