package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.trial_spawner

import dev.wuason.unearthMechanic.compatibilities.craftengine.types.VaultStage
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.bukkit.api.CraftEngineItems
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.UpdateFlags
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.entity.player.InteractionHand
import net.momirealms.craftengine.core.entity.player.InteractionResult
import net.momirealms.craftengine.core.item.Item
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.util.Key
import net.momirealms.craftengine.core.world.context.UseOnContext
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.max
import kotlin.random.Random

class VaultBehavior(
    blockDefinition: BlockDefinition,
    private val stageProperty: Property<VaultStage>,
    private val settings: Settings
) : BukkitBlockBehavior(blockDefinition) {

    override fun useOnBlock(context: UseOnContext, state: ImmutableBlockState): InteractionResult {
        val craftPlayer = context.player ?: return InteractionResult.PASS
        val player = craftPlayer.platformPlayer() as? Player ?: return InteractionResult.PASS
        val item = context.item ?: return InteractionResult.SUCCESS_AND_CANCEL
        val pos = context.clickedPos
        val location = Location(player.world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5)
        val key = BlockPosKey(player.world.uid, pos.x(), pos.y(), pos.z())
        val controller = controllers.getOrPut(key) { Controller(this, key, location) }

        controller.start()
        controller.tryUnlock(player, craftPlayer, context.hand, item)
        return InteractionResult.SUCCESS_AND_CANCEL
    }

    override fun onPlace(thisBlock: Any, args: Array<out Any>) {
        locationFromNmsArgs(args)?.let(::startController)
    }

    override fun randomTick(thisBlock: Any, args: Array<out Any>) {
        locationFromNmsArgs(args)?.let(::startController)
    }

    override fun affectNeighborsAfterRemoval(thisBlock: Any, args: Array<out Any>) {
        val location = locationFromNmsArgs(args) ?: return
        val key = location.key()
        controllers.remove(key)?.clear()
        openedCache.remove(key)
        TrialBlockDataStore.remove(plugin, key)
    }

    private fun startController(location: Location) {
        val key = location.key()
        controllers.getOrPut(key) { Controller(this, key, location.centered()) }.start()
    }

    private fun isValidKey(item: Item): Boolean = !item.isEmpty && settings.keys.any { it.matches(item) }

    private fun openedPlayers(key: BlockPosKey): MutableList<UUID> =
        openedCache.getOrPut(key) { TrialBlockDataStore.openedPlayers(plugin, key) }

    private fun hasOpened(key: BlockPosKey, playerId: UUID): Boolean =
        settings.perPlayer && playerId in openedPlayers(key)

    private fun markOpened(key: BlockPosKey, playerId: UUID) {
        if (!settings.perPlayer) return
        val players = openedPlayers(key)
        players.remove(playerId)
        while (players.size >= settings.maxPlayersStored) players.removeAt(0)
        players += playerId
        TrialBlockDataStore.saveOpenedPlayers(plugin, key, players)
    }

    private fun setStage(location: Location, stage: VaultStage) {
        val block = location.block
        val current = CraftEngineBlocks.getCustomBlockState(block.blockData) ?: return
        val next = current.with(stageProperty, stage)
        BukkitAdaptor.adapt(block.world).setBlockState(block.x, block.y, block.z, next, UpdateFlags.UPDATE_ALL)
    }

    private inner class Controller(
        private val behavior: VaultBehavior,
        private val key: BlockPosKey,
        private val origin: Location
    ) {
        private var stage = VaultStage.inactive
        private var taskId: Int? = null
        private var nextIdleCycleAt = 0L
        private var transitionAt = 0L
        private var nextEjectAt = 0L
        private var openingPlayer: UUID? = null
        private val ejectQueue = ArrayDeque<ItemStack>()
        private var display: ItemDisplay? = null

        fun start() {
            if (taskId != null) return
            taskId = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::tick), 1L, 2L).taskId
        }

        fun clear() {
            taskId?.let(Bukkit.getScheduler()::cancelTask)
            taskId = null
            removeDisplay()
            ejectQueue.clear()
        }

        fun tryUnlock(
            player: Player,
            craftPlayer: net.momirealms.craftengine.core.entity.player.Player,
            hand: InteractionHand,
            item: Item
        ) {
            if (stage == VaultStage.unlocking || stage == VaultStage.ejecting) return
            if (behavior.hasOpened(key, player.uniqueId)) {
                player.playSound(player.location, Sound.BLOCK_CHEST_LOCKED, 0.8f, 1.2f)
                return
            }
            if (!behavior.isValidKey(item)) {
                player.playSound(player.location, Sound.BLOCK_CHEST_LOCKED, 0.8f, 0.8f)
                return
            }

            if (settings.consumeKey && !craftPlayer.isCreativeMode) {
                item.shrink(1)
                craftPlayer.setItemInHand(hand, item)
            }

            openingPlayer = player.uniqueId
            transition(VaultStage.unlocking)
            transitionAt = origin.world.fullTime + settings.unlockingTicks
            playSound(settings.openingSound, 1f, 1f)
            origin.world.spawnParticle(Particle.ENCHANT, origin, 50, 0.45, 0.45, 0.45, 0.05)
        }

        private fun tick() {
            if (!origin.world.isChunkLoaded(origin.blockX shr 4, origin.blockZ shr 4)) return
            val now = origin.world.fullTime
            when (stage) {
                VaultStage.inactive -> if (eligiblePlayers(settings.activationRadius).isNotEmpty()) {
                    transition(VaultStage.active)
                    nextIdleCycleAt = 0L
                }
                VaultStage.active -> {
                    val players = eligiblePlayers(settings.deactivationRadius)
                    if (players.isEmpty()) {
                        transition(VaultStage.inactive)
                        removeDisplay()
                        return
                    }
                    awaitingParticles(players)
                    if (now >= nextIdleCycleAt) {
                        showDisplay(settings.rewards.weightedRandom()?.createItemStack())
                        nextIdleCycleAt = now + settings.idleCycleTicks
                    }
                }
                VaultStage.unlocking -> {
                    origin.world.spawnParticle(Particle.FLAME, origin, 4, 0.25, 0.25, 0.25, 0.01)
                    if (now >= transitionAt) {
                        prepareRewards()
                        if (ejectQueue.isEmpty()) finish() else {
                            transition(VaultStage.ejecting)
                            showDisplay(ejectQueue.first())
                            nextEjectAt = now + settings.showNextItemTicks
                        }
                    }
                }
                VaultStage.ejecting -> if (now >= nextEjectAt) {
                    val item = ejectQueue.removeFirstOrNull()
                    if (item == null) finish() else {
                        eject(item)
                        if (ejectQueue.isEmpty()) finish() else {
                            showDisplay(ejectQueue.first())
                            nextEjectAt = now + max(settings.ejectIntervalTicks, settings.showNextItemTicks)
                        }
                    }
                }
            }
        }

        private fun prepareRewards() {
            ejectQueue.clear()
            repeat(Random.nextInt(settings.minRewardRolls, settings.maxRewardRolls + 1)) {
                settings.rewards.weightedRandom()?.createItemStack()?.let(ejectQueue::addLast)
            }
        }

        private fun eject(stack: ItemStack) {
            val location = origin.clone().add(0.0, 0.45, 0.0)
            origin.world.dropItem(location, stack).velocity = Vector(
                Random.nextDouble(-0.08, 0.08), 0.35, Random.nextDouble(-0.08, 0.08)
            )
            playSound(settings.rewardSound, 1f, 1f)
            origin.world.spawnParticle(Particle.FLAME, location, 18, 0.25, 0.25, 0.25, 0.03)
        }

        private fun finish() {
            openingPlayer?.let { behavior.markOpened(key, it) }
            openingPlayer = null
            ejectQueue.clear()
            removeDisplay()
            transition(VaultStage.inactive)
        }

        private fun eligiblePlayers(radius: Double): List<Player> {
            val r2 = radius * radius
            return origin.world.players.filter {
                !it.isDead && it.gameMode in setOf(GameMode.SURVIVAL, GameMode.ADVENTURE) &&
                        it.location.distanceSquared(origin) <= r2 && !behavior.hasOpened(key, it.uniqueId)
            }
        }

        private fun awaitingParticles(players: List<Player>) {
            val keyhole = origin.clone().add(0.0, 0.1, 0.0)
            origin.world.spawnParticle(Particle.FLAME, keyhole, 2, 0.15, 0.15, 0.15, 0.005)
            for (player in players) {
                val direction = keyhole.toVector().subtract(player.eyeLocation.toVector())
                val steps = max(3, direction.length().toInt() * 2)
                val step = direction.multiply(1.0 / steps)
                var cursor = player.eyeLocation.toVector()
                repeat(steps) {
                    cursor = cursor.add(step)
                    origin.world.spawnParticle(Particle.FLAME, cursor.toLocation(origin.world), 1, 0.0, 0.0, 0.0, 0.0)
                }
            }
        }

        private fun showDisplay(stack: ItemStack?) {
            if (stack == null) return
            val location = origin.clone().add(0.0, 0.12, 0.0)
            val entity = display?.takeIf { it.isValid } ?: origin.world.spawn(location, ItemDisplay::class.java).also {
                it.isPersistent = false
                it.isInvulnerable = true
                it.setGravity(false)
                display = it
            }
            entity.teleport(location)
            entity.itemStack = stack
        }

        private fun removeDisplay() {
            display?.remove()
            display = null
        }

        private fun transition(next: VaultStage) {
            if (stage == next) return
            stage = next
            behavior.setStage(origin, next)
        }

        private fun playSound(id: String, volume: Float, pitch: Float) {
            sound(id)?.let { origin.world.playSound(origin, it, volume, pitch) }
        }
    }

    data class Settings(
        val keys: List<ItemKey>, val consumeKey: Boolean, val perPlayer: Boolean,
        val activationRadius: Double, val deactivationRadius: Double, val maxPlayersStored: Int,
        val idleCycleTicks: Long, val unlockingTicks: Long, val showNextItemTicks: Long,
        val ejectIntervalTicks: Long, val minRewardRolls: Int, val maxRewardRolls: Int,
        val openingSound: String, val rewardSound: String, val rewards: List<RewardEntry>
    )

    data class RewardEntry(val item: ItemKey, val minAmount: Int, val maxAmount: Int, val weight: Int) {
        fun createItemStack(): ItemStack? = item.createItemStack(Random.nextInt(minAmount, maxAmount + 1))
    }

    data class ItemKey(val key: Key) {
        private val normalized get() = if (key.namespace() == "vanilla") Key.minecraft(key.value()) else key
        fun matches(item: Item): Boolean = if (normalized.namespace() == "minecraft") {
            item.vanillaId() == normalized || item.id() == normalized
        } else item.id() == normalized || item.customId().map { it == normalized }.orElse(false)

        fun createItemStack(amount: Int): ItemStack? {
            if (normalized.namespace() == "minecraft") {
                return Material.matchMaterial(normalized.value().uppercase())?.let { ItemStack(it, amount) }
            }
            return CraftEngineItems.byId(normalized)?.buildBukkitItem()?.also { it.amount = amount }
        }

        companion object {
            fun parse(raw: String): ItemKey = ItemKey(if (':' in raw) Key.of(raw.trim()) else Key.minecraft(raw.trim().lowercase()))
        }
    }

    companion object {
        private val plugin: JavaPlugin by lazy { JavaPlugin.getProvidingPlugin(VaultBehavior::class.java) }
        private val controllers = mutableMapOf<BlockPosKey, VaultBehavior.Controller>()
        private val openedCache = mutableMapOf<BlockPosKey, MutableList<UUID>>()

        val FACTORY = object : BlockBehaviorFactory<VaultBehavior> {
            override fun create(blockDefinition: BlockDefinition, arguments: ConfigSection): VaultBehavior {
                val property = blockDefinition.getProperty("stage")
                    ?: throw IllegalArgumentException("painter:vault requires the blockstate property 'stage'")
                @Suppress("UNCHECKED_CAST")
                return VaultBehavior(blockDefinition, property as Property<VaultStage>, Settings(
                    keys = arguments.getStringList("keys", listOf("minecraft:tripwire_hook")).map(ItemKey::parse),
                    consumeKey = arguments.getBoolean("consume-key", true),
                    perPlayer = arguments.getBoolean("per-player", true),
                    activationRadius = arguments.getDouble("activation-radius", 3.0),
                    deactivationRadius = arguments.getDouble("deactivation-radius", 4.0),
                    maxPlayersStored = arguments.getInt("max-players-stored", 128).coerceAtLeast(1),
                    idleCycleTicks = arguments.getLong("idle-cycle-ticks", 20L).coerceAtLeast(1L),
                    unlockingTicks = arguments.getLong("unlocking-ticks", 30L).coerceAtLeast(1L),
                    showNextItemTicks = arguments.getLong("show-next-item-ticks", 8L).coerceAtLeast(1L),
                    ejectIntervalTicks = arguments.getLong("eject-interval-ticks", 30L).coerceAtLeast(1L),
                    minRewardRolls = arguments.getInt("min-reward-rolls", 2).coerceAtLeast(1),
                    maxRewardRolls = arguments.getInt("max-reward-rolls", 5).coerceAtLeast(1),
                    openingSound = arguments.getString("opening-sound", "minecraft:block.vault.activate"),
                    rewardSound = arguments.getString("reward-sound", "minecraft:block.vault.eject_item"),
                    rewards = arguments.getSectionList("rewards") { s ->
                        val min = s.getInt("min-amount", s.getInt("amount", 1)).coerceAtLeast(1)
                        RewardEntry(ItemKey.parse(s.getString("item", "minecraft:diamond")), min,
                            s.getInt("max-amount", min).coerceAtLeast(min), s.getInt("weight", 1).coerceAtLeast(1))
                    }.ifEmpty { listOf(RewardEntry(ItemKey.parse("minecraft:diamond"), 1, 1, 1)) }
                ).normalized())
            }
        }

        private fun Settings.normalized() = copy(maxRewardRolls = maxOf(minRewardRolls, maxRewardRolls))
    }
}

private fun List<VaultBehavior.RewardEntry>.weightedRandom(): VaultBehavior.RewardEntry? {
    if (isEmpty()) return null
    var selected = Random.nextInt(sumOf { it.weight })
    for (entry in this) {
        selected -= entry.weight
        if (selected < 0) return entry
    }
    return last()
}

private fun Location.centered() = Location(world, blockX + 0.5, blockY + 0.5, blockZ + 0.5)
private fun Location.key() = BlockPosKey(world.uid, blockX, blockY, blockZ)

private fun locationFromNmsArgs(args: Array<out Any>): Location? {
    val nmsWorld = args.getOrNull(1) ?: return null
    val pos = args.getOrNull(2) ?: return null
    val world = Bukkit.getWorlds().firstOrNull { runCatching { it.javaClass.getMethod("getHandle").invoke(it) == nmsWorld }.getOrDefault(false) } ?: return null
    fun coordinate(name: String) = runCatching { pos.javaClass.getMethod(name).invoke(pos) as Int }.getOrNull()
    return Location(world, coordinate("getX")?.toDouble() ?: return null,
        coordinate("getY")?.toDouble() ?: return null, coordinate("getZ")?.toDouble() ?: return null)
}

private fun sound(raw: String): Sound? {
    val clean = raw.substringAfter(':').replace('.', '_').uppercase()
    return runCatching { Sound.valueOf(clean) }.getOrNull()
        ?: NamespacedKey.fromString(raw)?.let { Registry.SOUNDS.get(it) }
}