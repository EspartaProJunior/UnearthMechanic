package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite

import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.utils.FoliaUtils
import net.kyori.adventure.text.Component
import net.momirealms.craftengine.bukkit.api.CraftEngineItems
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.core.util.Key
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import kotlin.math.ceil
import java.util.concurrent.ConcurrentHashMap

class TermiteListener(
    private val specialFriendItem: Material = Material.HONEYCOMB
) : Listener {

    companion object {
        private data class ComposterConfig(
            val foodPerWood: Int = 1,
            val foodToBefriend: Int = 64,
            val nestRadius: Int = 12
        )

        @Volatile private var composterConfig = ComposterConfig()
        private val TERMITE_BUCKET_KEY by lazy { NamespacedKey(UnearthMechanic.getInstance(), "termite_bucket") }
        private val sawdustBlocks = ConcurrentHashMap.newKeySet<String>()

        fun configureComposter(foodPerWood: Int, foodToBefriend: Int, nestRadius: Int) {
            composterConfig = ComposterConfig(foodPerWood, foodToBefriend, nestRadius)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val clicked = event.clickedBlock ?: return
        val item = event.item ?: return

        when {
            isWoodFood(item.type) && isTermiteComposter(clicked) -> feedComposter(event.player, clicked, item, event)
            item.type == specialFriendItem && isTermiteNest(clicked) -> claimFriendlyNest(event.player, clicked, item, event)
            isSawdust(item) && isCrop(clicked) -> applySawdust(event.player, item, event)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBucketEntity(event: PlayerInteractEntityEvent) {
        val termite = event.rightClicked as? LivingEntity ?: return
        if (!MythicTermites.isTermite(termite)) return

        val handItem = event.player.inventory.getItem(event.hand)
        if (handItem.type != Material.BUCKET) return

        event.isCancelled = true
        val player = event.player

        FoliaUtils.runAtEntity(termite) {
            termite.remove()

            FoliaUtils.runAtEntity(player) {
                val bucket = termiteBucket()
                val currentHandItem = player.inventory.getItem(event.hand)
                if (player.gameMode != GameMode.CREATIVE && currentHandItem.type != Material.BUCKET) {
                    player.inventory.addItem(bucket)
                    player.swingHand(event.hand)
                    return@runAtEntity
                }

                if (currentHandItem.type == Material.BUCKET && currentHandItem.amount == 1) {
                    if (player.gameMode != GameMode.CREATIVE) {
                        player.inventory.setItem(event.hand, bucket)
                    } else {
                        player.inventory.addItem(bucket)
                    }
                } else {
                    if (player.gameMode != GameMode.CREATIVE) currentHandItem.amount -= 1
                    player.inventory.addItem(bucket)
                }
                player.swingHand(event.hand)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTermiteCombatAlert(event: EntityDamageByEntityEvent) {
        val damaged = event.entity as? LivingEntity
        val damager = event.damager as? LivingEntity

        if (damaged != null && MythicTermites.isTermite(damaged)) {
            TermiteGameplay.alertNearbyTermites(damaged.location)
        }

        if (damager != null && MythicTermites.isTermite(damager)) {
            TermiteGameplay.alertNearbyTermites(damager.location)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWoodBreak(event: BlockBreakEvent) {
        val block = event.block
        if (isTermiteNest(block)) {
            TermiteGameplay.releaseStoredTermitesAround(block)
            return
        }

        if (!isConsumedWoodCandidate(block)) return
        if (!hasTermiteNearby(block)) return

        event.isDropItems = false
        TermiteGameplay.consumeWoodBlock(block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemSpawn(event: ItemSpawnEvent) {
        val itemEntity = event.entity

        FoliaUtils.runLater(20L) {
            FoliaUtils.runAtEntity(itemEntity) {
                tryDepositItemEntity(itemEntity)
            }
        }
    }

    fun consumeWoodBlock(block: Block) {
        TermiteGameplay.consumeWoodBlock(block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCropDrops(event: BlockDropItemEvent) {
        val key = TermiteKeys.key(event.block)
        if (!sawdustBlocks.remove(key)) return
        if (!isCrop(event.blockState.block)) return

        for (item in event.items) {
            val stack = item.itemStack.clone()
            event.block.world.dropItemNaturally(event.block.location.add(0.5, 0.5, 0.5), stack)
        }
    }

    private fun feedComposter(player: Player, composter: Block, item: ItemStack, event: PlayerInteractEvent) {
        val nest = findNearestNestBlock(composter, composterConfig.nestRadius) ?: return
        val nestKey = TermiteKeys.key(nest)
        val accepted = TermiteDataStore.addFood(nestKey, composterConfig.foodPerWood, composterConfig.foodToBefriend)
        if (accepted <= 0) return

        event.isCancelled = true
        if (player.gameMode != GameMode.CREATIVE) item.amount -= 1

        val data = TermiteDataStore.get(nestKey)
        if (data.food >= composterConfig.foodToBefriend && data.ownerUuid == null) {
            TermiteDataStore.markFriendly(nestKey, player.uniqueId.toString())
        }

        TermiteGameplay.updateNestStage(nest)
    }

    private fun claimFriendlyNest(player: Player, nest: Block, item: ItemStack, event: PlayerInteractEvent) {
        val key = TermiteKeys.key(nest)
        val data = TermiteDataStore.get(key)
        if (data.food < composterConfig.foodToBefriend) return

        TermiteDataStore.markFriendly(key, player.uniqueId.toString())
        event.isCancelled = true
        if (player.gameMode != GameMode.CREATIVE) item.amount -= 1
        TermiteGameplay.updateNestStage(nest)
    }

    private fun applySawdust(player: Player, item: ItemStack, event: PlayerInteractEvent) {
        event.isCancelled = true
        if (player.gameMode != GameMode.CREATIVE) item.amount -= 1
        event.clickedBlock?.let { sawdustBlocks.add(TermiteKeys.key(it)) }
    }

    private fun tryDepositItemEntity(itemEntity: Item): Boolean {
        if (!itemEntity.isValid) return false

        val stack = itemEntity.itemStack
        if (!isWoodFood(stack.type)) return false

        val composter = itemEntity.location.clone().subtract(0.0, 0.25, 0.0).block
        if (!isTermiteComposter(composter)) return false

        val nest = findNearestNestBlock(composter, composterConfig.nestRadius) ?: return false
        val nestKey = TermiteKeys.key(nest)
        val maxFoodFromStack = stack.amount * composterConfig.foodPerWood
        val acceptedFood = TermiteDataStore.addFood(nestKey, maxFoodFromStack, composterConfig.foodToBefriend)
        if (acceptedFood <= 0) return false

        val consumedItems = ceil(acceptedFood.toDouble() / composterConfig.foodPerWood.toDouble()).toInt()
            .coerceIn(1, stack.amount)

        if (consumedItems >= stack.amount) {
            itemEntity.remove()
        } else {
            stack.amount -= consumedItems
            itemEntity.itemStack = stack
        }

        TermiteGameplay.updateNestStage(nest)
        return true
    }

    private fun findNearestNestBlock(origin: Block, radius: Int): Block? {
        var best: Block? = null
        var bestDistance = Double.MAX_VALUE

        for (dx in -radius..radius) for (dy in -radius..radius) for (dz in -radius..radius) {
            val block = origin.world.getBlockAt(origin.x + dx, origin.y + dy, origin.z + dz)
            if (!isTermiteNest(block)) continue
            val data = TermiteDataStore.peek(TermiteKeys.key(block)) ?: continue
            if (data.termites <= 0) continue

            val distance = block.location.distanceSquared(origin.location)
            if (distance < bestDistance) {
                bestDistance = distance
                best = block
            }
        }

        return best
    }

    private fun isTermiteNest(block: Block): Boolean =
        customBlockHasProperty(block, "stage") && customBlockId(block).contains("termite")

    private fun isTermiteComposter(block: Block): Boolean =
        customBlockId(block).contains("termite_composter")

    private fun customBlockId(block: Block): String {
        val state = CraftEngineBlocks.getCustomBlockState(block.blockData) ?: return ""
        return state.owner().value().id().toString()
    }

    private fun customBlockHasProperty(block: Block, property: String): Boolean {
        val state = CraftEngineBlocks.getCustomBlockState(block.blockData) ?: return false
        return state.owner().value().getProperty(property) != null
    }

    private fun hasTermiteNearby(block: Block): Boolean {
        return block.world.getNearbyEntities(block.location.add(0.5, 0.5, 0.5), 5.0, 3.0, 5.0)
            .any { MythicTermites.isTermite(it) }
    }

    private fun termiteBucket(): ItemStack {
        val customBucket = createCraftEngineItem(TermiteGameplay.termiteBucketItemId)
        if (customBucket != null) return customBucket

        val item = ItemStack(Material.BUCKET)
        val meta = item.itemMeta
        meta.displayName(Component.text("Termite Bucket"))
        meta.persistentDataContainer.set(TERMITE_BUCKET_KEY, PersistentDataType.INTEGER, 1)
        item.itemMeta = meta
        return item
    }

    private fun createCraftEngineItem(id: String): ItemStack? {
        if (id.isBlank()) return null

        val definition = CraftEngineItems.byId(Key.of(id)) ?: return null
        return definition.buildBukkitItem()
    }

    private fun isSawdust(item: ItemStack): Boolean =
        item.itemMeta?.persistentDataContainer?.has(
            NamespacedKey(UnearthMechanic.getInstance(), "sawdust"),
            PersistentDataType.INTEGER
        ) == true || item.type == TermiteGameplay.sawdustMaterial

    private fun isWoodFood(material: Material): Boolean =
        material.name.endsWith("_LOG") || material.name.endsWith("_WOOD") || material == Material.STICK

    private fun isConsumedWoodCandidate(block: Block): Boolean =
        TermiteGameplay.isConsumedWoodCandidate(block)

    private fun isCrop(block: Block): Boolean =
        block.type.name in setOf("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART")
}