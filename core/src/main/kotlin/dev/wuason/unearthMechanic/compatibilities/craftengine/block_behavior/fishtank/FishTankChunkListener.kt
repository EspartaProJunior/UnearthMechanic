package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.fishtank

import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.compatibilities.craftengine.types.FishType
import net.momirealms.craftengine.bukkit.api.BukkitAdaptors
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.core.block.UpdateOption
import net.momirealms.craftengine.core.block.properties.Property
import net.momirealms.craftengine.core.world.BlockPos
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

class FishTankChunkListener : Listener {
    @EventHandler
    fun onChunkLoad(e: ChunkLoadEvent) {
        Bukkit.getScheduler().runTaskLater(
            UnearthMechanic.getInstance(),
            Runnable {
                FishTankBehavior.ensureTaskRunning()
                FishTankBehavior.resyncChunkAquariums(e.world, e.chunk)
            },
            20L
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBucketEmpty(e: PlayerBucketEmptyEvent) {
        if (!isEntityBucket(e.bucket)) return

        val clicked = e.blockClicked
        val placed = clicked.getRelative(e.blockFace)

        val hitTank =
            isFishTankBlock(clicked) || isFishTankBlock(placed) ||
                    isFishTankNear(clicked, 1) || isFishTankNear(placed, 1)

        //Bukkit.getLogger().info("[DBG] BucketEmpty bucket=${e.bucket} clicked=${clicked.type} hitTank=$hitTank cancelledBefore=${e.isCancelled}")

        if (hitTank) {
            e.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteractFallback(e: PlayerInteractEvent) {
        if (e.action != Action.RIGHT_CLICK_BLOCK) return
        if (e.hand != EquipmentSlot.HAND) return

        val item = e.item ?: return
        val fishType = FishTankBehavior.fishFromBucket(item.type) ?: return

        val clicked = e.clickedBlock ?: return
        val placed = clicked.getRelative(e.blockFace)

        // encuentra el bloque FishTank real cerca (clicked puede ser BRICKS/GLASS/etc)
        val tankBlock = findTankBlockNear(clicked) ?: findTankBlockNear(placed) ?: return

        // ✅ Cancela vanilla (esto evita agua/spawn)
        e.isCancelled = true
        e.setUseItemInHand(Event.Result.DENY)
        e.setUseInteractedBlock(Event.Result.DENY)

        // ✅ Ejecuta tu lógica de insert/swap manualmente
        runTankInsertLikeCE(e.player, tankBlock, item, fishType)
    }

    private fun findTankBlockNear(b: Block, r: Int = 1): Block? {
        val w = b.world
        for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) {
            val nb = w.getBlockAt(b.x + dx, b.y + dy, b.z + dz)
            val st = CraftEngineBlocks.getCustomBlockState(nb.blockData) ?: continue
            val owner = st.owner().value() ?: continue
            if (owner.getProperty("fish") != null) return nb
        }
        return null
    }

    private fun runTankInsertLikeCE(player: Player, tankBlock: Block, handItem: ItemStack, fishFromBucket: FishType) {
        val ceWorld = BukkitAdaptors.adapt(tankBlock.world)
        val pos = BlockPos(tankBlock.x, tankBlock.y, tankBlock.z)

        val st = CraftEngineBlocks.getCustomBlockState(tankBlock.blockData) ?: return
        val owner = st.owner().value() ?: return
        val fishPropAny = owner.getProperty("fish") ?: return
        @Suppress("UNCHECKED_CAST")
        val fishProp = fishPropAny as Property<FishType>

        val currentFish = try { st.get(fishProp, FishType.none) } catch (_: Throwable) { return }
        if (fishFromBucket == currentFish) return

        val inSnapshot = handItem.clone().also { it.amount = 1 }

        // bucket que devuelves si había pez
        val bw = tankBlock.world
        val center = Location(bw, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5)
        val rootState = CraftEngineBlocks.getCustomBlockState(bw.getBlockAt(pos.x(), pos.y(), pos.z()).blockData)
        val expectedId = if (rootState != null) FishTankBehavior.ownerIdString(rootState) else "elitefantasy:aquarium_block"
        val tankKey = FishTankBehavior.resolveTankKey(bw, ceWorld, pos, expectedId)

        val out = if (currentFish == FishType.none) ItemStack(Material.BUCKET)
        else FishTankBehavior.bucketToGive(bw, tankKey, pos, currentFish)

        // set state
        val newState = st.with(fishProp, fishFromBucket)
        ceWorld.setBlockState(pos, newState, UpdateOption.UPDATE_ALL.flags())

        FishTankBehavior.ensureTaskRunning()
        FishTankBehavior.syncFishDisplay(ceWorld, pos, fishFromBucket, inSnapshot)

        // aplica item en mano (igual que tu CE handler)
        if (player.gameMode != GameMode.CREATIVE) {
            handItem.type = out.type
            handItem.itemMeta = out.itemMeta
            handItem.amount = out.amount
        }
    }

    private fun isEntityBucket(mat: Material): Boolean {
        return mat == Material.AXOLOTL_BUCKET ||
                mat == Material.TROPICAL_FISH_BUCKET ||
                mat == Material.COD_BUCKET ||
                mat == Material.SALMON_BUCKET ||
                mat == Material.PUFFERFISH_BUCKET
    }

    private fun isFishTankBlock(b: Block): Boolean {
        val st = CraftEngineBlocks.getCustomBlockState(b.blockData) ?: return false
        val owner = st.owner().value() ?: return false
        return owner.getProperty("fish") != null
    }

    private fun isFishTankNear(b: Block, r: Int = 1): Boolean {
        val w = b.world
        val bx = b.x
        val by = b.y
        val bz = b.z

        for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) {
            val nb = w.getBlockAt(bx + dx, by + dy, bz + dz)
            if (isFishTankBlock(nb)) return true
        }
        return false
    }

    @EventHandler(ignoreCancelled = true)
    fun onBucketEntity(e: PlayerBucketEntityEvent) {
        val le = e.entity as? LivingEntity ?: return

        // ONLY your display fish
        if (!le.scoreboardTags.contains("um_fishtank")) return

        // Cancel vanilla capture (prevents rare dupe/respawn)
        e.isCancelled = true

        val bw = le.world
        val tankKey = le.persistentDataContainer
            .get(FishTankBehavior.PDC_TANK_KEY_PUBLIC, org.bukkit.persistence.PersistentDataType.STRING)
            ?: return

        val fishType = FishTankBehavior.fishTypeOfEntityPublic(le) // expón helper público

        // Build the correct bucket (with target if snapshot exists)
        val pos = BlockPos(le.location.blockX, le.location.blockY, le.location.blockZ)
        val bucket = FishTankBehavior.bucketToGive(bw,tankKey,pos,fishType)

        // Consumir 1 cubeta vacía del jugador (si está en mano)
        val p = e.player
        val hand = e.hand
        val item = p.inventory.getItem(hand)
        if (item.type == Material.BUCKET) {
            // reemplaza por el bucket lleno
            p.inventory.setItem(hand, bucket)
        } else {
            // fallback
            p.inventory.addItem(bucket)
        }

        // Remover el entity display
        le.remove()

        // IMPORTANTÍSIMO: limpiar 1 “slot” en el tanque (fish -> none)
        // Aquí debes usar TU lógica de cambiar la propiedad del bloque.
        FishTankBehavior.removeOneFishFromTankAndResync(bw, tankKey, fishType)
    }
}