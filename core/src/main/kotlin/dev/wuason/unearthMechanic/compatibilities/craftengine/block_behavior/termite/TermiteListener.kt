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

class TermiteListener : Listener {

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
            TermiteGameplay.composterFoodToBefriend = foodToBefriend.coerceAtLeast(1)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val clicked = event.clickedBlock ?: return
        val item = event.item ?: return

        when {
            isWoodFood(item) && isTermiteComposter(clicked) -> feedComposter(event.player, clicked, item, event)
            isSawdust(item) && isCrop(clicked) -> applySawdust(event.player, item, event)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBucketEntity(event: PlayerInteractEntityEvent) {
        val termite = event.rightClicked as? LivingEntity ?: return
        if (!MythicTermites.isTermite(termite)) return
        if (!MythicTermites.isFriendlyTermite(termite)) return

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
        if (isTermiteComposter(block)) {
            TermiteGameplay.clearComposter(block)
            return
        }

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
        val composterKey = TermiteGameplay.composterKey(composter)
        val accepted = TermiteDataStore.addFood(
            composterKey,
            composterConfig.foodPerWood,
            composterConfig.foodToBefriend
        )
        if (accepted <= 0) return

        event.isCancelled = true
        if (player.gameMode != GameMode.CREATIVE) item.amount -= 1

        val data = TermiteDataStore.get(composterKey)
        if (data.food >= composterConfig.foodToBefriend) {
            if (data.ownerUuid == null) {
                TermiteDataStore.markFriendly(composterKey, player.uniqueId.toString())
            }
            TermiteGameplay.befriendColonyFromComposter(
                composter = composter,
                ownerUuid = player.uniqueId.toString(),
                radius = composterConfig.nestRadius
            )
        }

        TermiteGameplay.updateComposterStage(composter, data.food, composterConfig.foodToBefriend)
    }

    private fun applySawdust(player: Player, item: ItemStack, event: PlayerInteractEvent) {
        event.isCancelled = true
        if (player.gameMode != GameMode.CREATIVE) item.amount -= 1
        event.clickedBlock?.let { sawdustBlocks.add(TermiteKeys.key(it)) }
    }

    private fun tryDepositItemEntity(itemEntity: Item): Boolean {
        if (!itemEntity.isValid) return false

        val stack = itemEntity.itemStack
        if (!isWoodFood(stack)) return false

        val composter = itemEntity.location.clone().subtract(0.0, 0.25, 0.0).block
        if (!isTermiteComposter(composter)) return false

        val composterKey = TermiteGameplay.composterKey(composter)
        val maxFoodFromStack = stack.amount * composterConfig.foodPerWood
        val acceptedFood = TermiteDataStore.addFood(composterKey, maxFoodFromStack, composterConfig.foodToBefriend)
        if (acceptedFood <= 0) return false

        val consumedItems = ceil(acceptedFood.toDouble() / composterConfig.foodPerWood.toDouble()).toInt()
            .coerceIn(1, stack.amount)

        if (consumedItems >= stack.amount) {
            itemEntity.remove()
        } else {
            stack.amount -= consumedItems
            itemEntity.itemStack = stack
        }

        val data = TermiteDataStore.get(composterKey)
        TermiteGameplay.updateComposterStage(composter, data.food, composterConfig.foodToBefriend)
        return true
    }

    private fun isTermiteNest(block: Block): Boolean =
        customBlockId(block).contains("termite_nest")

    private fun isTermiteComposter(block: Block): Boolean =
        customBlockId(block).contains("termite_composter")

    private fun customBlockId(block: Block): String {
        val state = CraftEngineBlocks.getCustomBlockState(block.blockData) ?: return ""
        return state.owner().value().id().toString()
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

    private fun isWoodFood(item: ItemStack): Boolean {
        if (isVanillaWoodFood(item.type)) return true

        val customId = CraftEngineItems.getCustomItemId(item)?.toString()?.lowercase() ?: return false
        val path = customId.substringAfter(':')
        return path.endsWith("_log") ||
                path.endsWith("_wood") ||
                path.endsWith("_stem") ||
                path.endsWith("_hyphae") ||
                path.endsWith("_block") && path.contains("bamboo")
    }

    private fun isVanillaWoodFood(material: Material): Boolean =
        material.name.endsWith("_LOG") ||
                material.name.endsWith("_WOOD") ||
                material.name.endsWith("_STEM") ||
                material.name.endsWith("_HYPHAE") ||
                material == Material.BAMBOO_BLOCK ||
                material == Material.STRIPPED_BAMBOO_BLOCK ||
                material == Material.STICK

    private fun isConsumedWoodCandidate(block: Block): Boolean =
        TermiteGameplay.isConsumedWoodCandidate(block)

    private fun isCrop(block: Block): Boolean =
        block.type.name in setOf("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART")
}