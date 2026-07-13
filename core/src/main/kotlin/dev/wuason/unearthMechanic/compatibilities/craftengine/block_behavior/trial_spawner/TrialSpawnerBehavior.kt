package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.trial_spawner

import dev.wuason.unearthMechanic.compatibilities.craftengine.types.TrialSpawnerStage
import io.lumine.mythic.bukkit.MythicBukkit
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.UpdateFlags
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.GameMode
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.floor
import kotlin.math.max
import kotlin.random.Random

class TrialSpawnerBehavior(
    customBlock: BlockDefinition,
    private val stageProperty: Property<TrialSpawnerStage>,
    private val settings: Settings
) : BukkitBlockBehavior(customBlock) {

    override fun onPlace(thisBlock: Any, args: Array<out Any>) {
        locationFromArgs(args)?.let(::startController)
    }

    override fun randomTick(thisBlock: Any, args: Array<out Any>) {
        locationFromArgs(args)?.let(::startController)
    }

    override fun affectNeighborsAfterRemoval(thisBlock: Any, args: Array<out Any>) {
        val location = locationFromArgs(args) ?: return
        val key = location.key()
        controllers.remove(key)?.clear()
        TrialBlockDataStore.remove(plugin, key)
        super.affectNeighborsAfterRemoval(thisBlock, args)
    }

    private fun startController(location: Location) {
        val centered = location.centered()
        val key = centered.key()
        if (controllers.containsKey(key)) return
        controllers[key] = Controller(this, key, centered).also(Controller::start)
    }

    private fun setStage(location: Location, stage: TrialSpawnerStage) {
        val block = location.block
        val current = CraftEngineBlocks.getCustomBlockState(block.blockData) ?: return
        val next = current.with(stageProperty, stage)
        BukkitAdaptor.adapt(block.world).setBlockState(block.x, block.y, block.z, next, UpdateFlags.UPDATE_ALL)
    }

    private inner class Controller(
        private val behavior: TrialSpawnerBehavior,
        private val key: BlockPosKey,
        private val origin: Location
    ) {
        private var stage = TrialSpawnerStage.inactive
        private var task: BukkitTask? = null
        private var totalMobTarget = 0
        private var simultaneousMobTarget = 0
        private var spawnedCount = 0
        private var maxPlayersSeen = 0
        private var nextSpawnAt = 0L
        private var nextRewardAt = 0L
        private var cooldownEndsAt = 0L
        private val participants = linkedSetOf<UUID>()
        private val spawnedMobs = mutableSetOf<UUID>()
        private val rewardQueue = ArrayDeque<ItemStack>()

        fun start() {
            if (task != null) return
            cooldownEndsAt = TrialBlockDataStore.cooldownEnd(plugin, key)
            if (cooldownEndsAt > System.currentTimeMillis()) transition(TrialSpawnerStage.cooldown)
            task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::tick), 1L, settings.tickPeriodTicks)
        }

        fun clear() {
            task?.cancel()
            task = null
            rewardQueue.clear()
        }

        private fun tick() {
            val world = origin.world
            if (!world.isChunkLoaded(origin.blockX shr 4, origin.blockZ shr 4)) return
            removeDefeatedMobs()
            when (stage) {
                TrialSpawnerStage.inactive -> tryActivate()
                TrialSpawnerStage.active -> tickCombat()
                TrialSpawnerStage.ejecting -> tickRewards()
                TrialSpawnerStage.cooldown -> if (System.currentTimeMillis() >= cooldownEndsAt) reset()
            }
        }

        private fun tryActivate() {
            val world = origin.world
            if (world.difficulty == Difficulty.PEACEFUL) return
            if (world.getGameRuleValue(GameRule.DO_MOB_SPAWNING) == false) return
            val players = detectablePlayers()
            if (players.size < settings.minPlayers) return

            participants += players.map(Player::getUniqueId)
            maxPlayersSeen = players.size.coerceAtMost(settings.maxPlayers)
            recalculateTargets()
            spawnedCount = 0
            nextSpawnAt = 0L
            transition(TrialSpawnerStage.active)
            origin.world.spawnParticle(Particle.ENCHANT, origin.clone().add(0.0, 0.5, 0.0), 30, 0.45, 0.45, 0.45, 0.02)
        }

        private fun tickCombat() {
            val now = origin.world.fullTime
            val players = detectablePlayers()
            participants += players.map(Player::getUniqueId)
            val currentCount = players.size.coerceAtMost(settings.maxPlayers)
            if (currentCount > maxPlayersSeen) {
                maxPlayersSeen = currentCount
                recalculateTargets()
            }

            if (spawnedCount < totalMobTarget && spawnedMobs.size < simultaneousMobTarget && now >= nextSpawnAt) {
                val location = findSpawnLocation()
                val mobId = settings.mobs.randomOrNull()
                if (location != null && mobId != null) {
                    spawnMob(mobId, location)?.let { mob ->
                        mob.addScoreboardTag("um_trial_spawner")
                        mob.addScoreboardTag("um_trial_spawner:${key.worldId}:${key.x}:${key.y}:${key.z}")
                        mob.removeWhenFarAway = false
                        spawnedMobs += mob.uniqueId
                        spawnedCount++
                        origin.world.spawnParticle(Particle.FLAME, mob.location, 12, 0.25, 0.5, 0.25, 0.02)
                    }
                }
                nextSpawnAt = now + settings.spawnIntervalTicks
            }

            origin.world.spawnParticle(Particle.SMOKE, origin.clone().add(0.0, 0.5, 0.0), 2, 0.35, 0.35, 0.35, 0.01)
            if (spawnedCount >= totalMobTarget && spawnedMobs.isEmpty()) prepareRewards(now)
        }

        private fun recalculateTargets() {
            val additional = (maxPlayersSeen - 1).coerceAtLeast(0)
            totalMobTarget = max(1, settings.baseTotalMobs + additional * settings.totalMobsAddedPerPlayer)
            simultaneousMobTarget = max(1, floor(settings.baseSimultaneousMobs +
                    additional * settings.simultaneousMobsAddedPerPlayer).toInt())
        }

        private fun prepareRewards(now: Long) {
            rewardQueue.clear()
            repeat(participants.size.coerceAtLeast(1)) {
                settings.rewards.weightedRandom()?.createItemStack()?.let(rewardQueue::addLast)
            }
            transition(TrialSpawnerStage.ejecting)
            nextRewardAt = now + settings.rewardDelayTicks
        }

        private fun tickRewards() {
            val now = origin.world.fullTime
            if (now < nextRewardAt) return
            val reward = rewardQueue.removeFirstOrNull()
            if (reward == null) {
                beginCooldown()
                return
            }
            val drop = origin.clone().add(0.0, 0.45, 0.0)
            origin.world.dropItem(drop, reward).velocity = Vector(
                Random.nextDouble(-0.08, 0.08), 0.35, Random.nextDouble(-0.08, 0.08)
            )
            origin.world.spawnParticle(Particle.HAPPY_VILLAGER, drop, 18, 0.3, 0.3, 0.3, 0.02)
            nextRewardAt = now + settings.rewardIntervalTicks
            if (rewardQueue.isEmpty()) beginCooldown()
        }

        private fun beginCooldown() {
            cooldownEndsAt = System.currentTimeMillis() + settings.cooldownSeconds * 1000L
            TrialBlockDataStore.saveCooldownEnd(plugin, key, cooldownEndsAt)
            transition(TrialSpawnerStage.cooldown)
        }

        private fun reset() {
            cooldownEndsAt = 0L
            TrialBlockDataStore.saveCooldownEnd(plugin, key, 0L)
            totalMobTarget = 0
            simultaneousMobTarget = 0
            spawnedCount = 0
            maxPlayersSeen = 0
            participants.clear()
            spawnedMobs.clear()
            rewardQueue.clear()
            transition(TrialSpawnerStage.inactive)
        }

        private fun removeDefeatedMobs() {
            spawnedMobs.removeIf { id ->
                val entity = Bukkit.getEntity(id)
                entity !is LivingEntity || entity.isDead || !entity.isValid
            }
        }

        private fun detectablePlayers(): List<Player> {
            val r2 = settings.activationRadius * settings.activationRadius
            return origin.world.players.filter { player ->
                !player.isDead && player.gameMode in setOf(GameMode.SURVIVAL, GameMode.ADVENTURE) &&
                        player.location.distanceSquared(origin) < r2 &&
                        (!settings.requireLineOfSight || clearTrialSpawnerLine(player.eyeLocation, origin))
            }
        }

        private fun findSpawnLocation(): Location? {
            repeat(settings.spawnAttempts) {
                val random = ThreadLocalRandom.current()
                val direction = Vector(random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0))
                if (direction.lengthSquared() == 0.0) return@repeat
                direction.normalize().multiply(random.nextDouble(0.5, settings.spawnRadius))
                val candidate = origin.clone().add(direction)
                if (candidate.block.isPassable && candidate.clone().add(0.0, 1.0, 0.0).block.isPassable &&
                    clearTrialSpawnerLine(candidate, origin)) return candidate
            }
            return null
        }

        private fun spawnMob(id: String, location: Location): LivingEntity? {
            if (id.startsWith("mythic:", true)) {
                if (!Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) return null
                return MythicBukkit.inst().apiHelper.spawnMythicMob(id.substringAfter(':'), location) as? LivingEntity
            }
            val type = runCatching { EntityType.valueOf(id.uppercase()) }.getOrNull() ?: return null
            return location.world.spawnEntity(location, type) as? LivingEntity
        }

        private fun transition(next: TrialSpawnerStage) {
            if (stage == next) return
            stage = next
            behavior.setStage(origin, next)
        }
    }

    data class Settings(
        val mobs: List<String>, val activationRadius: Double, val spawnRadius: Double,
        val requireLineOfSight: Boolean, val minPlayers: Int, val maxPlayers: Int,
        val baseTotalMobs: Int, val totalMobsAddedPerPlayer: Int,
        val baseSimultaneousMobs: Double, val simultaneousMobsAddedPerPlayer: Double,
        val spawnIntervalTicks: Long, val spawnAttempts: Int, val cooldownSeconds: Long,
        val rewardDelayTicks: Long, val rewardIntervalTicks: Long, val tickPeriodTicks: Long,
        val rewards: List<SpawnerReward>
    )

    data class SpawnerReward(
        val item: VaultBehavior.ItemKey, val minAmount: Int, val maxAmount: Int, val weight: Int
    ) {
        fun createItemStack(): ItemStack? = item.createItemStack(Random.nextInt(minAmount, maxAmount + 1))
    }

    companion object {
        private val plugin: JavaPlugin by lazy { JavaPlugin.getProvidingPlugin(TrialSpawnerBehavior::class.java) }
        private val controllers = mutableMapOf<BlockPosKey, TrialSpawnerBehavior.Controller>()
        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<TrialSpawnerBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): TrialSpawnerBehavior {
                val property = block.getProperty("stage")
                    ?: throw IllegalArgumentException("painter:trial_spawner requires the blockstate property 'stage'")
                @Suppress("UNCHECKED_CAST")
                return TrialSpawnerBehavior(block, property as Property<TrialSpawnerStage>, Settings(
                    mobs = section.getStringList("mobs", listOf("ZOMBIE")),
                    activationRadius = section.getDouble("activation-radius", 14.0),
                    spawnRadius = section.getDouble("spawn-radius", 4.0).coerceAtLeast(0.5),
                    requireLineOfSight = section.getBoolean("require-line-of-sight", true),
                    minPlayers = section.getInt("min-players", 1).coerceAtLeast(1),
                    maxPlayers = section.getInt("max-players", 4).coerceAtLeast(1),
                    baseTotalMobs = section.getInt("base-total-mobs", 6).coerceAtLeast(1),
                    totalMobsAddedPerPlayer = section.getInt("total-mobs-added-per-player", 2).coerceAtLeast(0),
                    baseSimultaneousMobs = section.getDouble("base-simultaneous-mobs", 2.0).coerceAtLeast(1.0),
                    simultaneousMobsAddedPerPlayer = section.getDouble("simultaneous-mobs-added-per-player", 1.0).coerceAtLeast(0.0),
                    spawnIntervalTicks = section.getLong("spawn-interval-ticks", 40L).coerceAtLeast(1L),
                    spawnAttempts = section.getInt("spawn-attempts", 24).coerceAtLeast(1),
                    cooldownSeconds = section.getLong("cooldown-seconds", 1800L).coerceAtLeast(0L),
                    rewardDelayTicks = section.getLong("reward-delay-ticks", 40L).coerceAtLeast(0L),
                    rewardIntervalTicks = section.getLong("reward-interval-ticks", 30L).coerceAtLeast(1L),
                    tickPeriodTicks = section.getLong("tick-period-ticks", 5L).coerceAtLeast(1L),
                    rewards = section.getSectionList("rewards") { s ->
                        val min = s.getInt("min-amount", s.getInt("amount", 1)).coerceAtLeast(1)
                        SpawnerReward(VaultBehavior.ItemKey.parse(s.getString("item", "minecraft:trial_key")), min,
                            s.getInt("max-amount", min).coerceAtLeast(min), s.getInt("weight", 1).coerceAtLeast(1))
                    }.ifEmpty { listOf(SpawnerReward(VaultBehavior.ItemKey.parse("minecraft:trial_key"), 1, 1, 1)) }
                ))
            }
        }
    }
}

private fun List<TrialSpawnerBehavior.SpawnerReward>.weightedRandom(): TrialSpawnerBehavior.SpawnerReward? {
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

private fun locationFromArgs(args: Array<out Any>): Location? {
    val nmsWorld = args.getOrNull(1) ?: return null
    val pos = args.getOrNull(2) ?: return null
    val world = Bukkit.getWorlds().firstOrNull { runCatching { it.javaClass.getMethod("getHandle").invoke(it) == nmsWorld }.getOrDefault(false) } ?: return null
    fun int(name: String) = runCatching { pos.javaClass.getMethod(name).invoke(pos) as Int }.getOrNull()
    return Location(world, int("getX")?.toDouble() ?: return null, int("getY")?.toDouble() ?: return null,
        int("getZ")?.toDouble() ?: return null)
}

/** A sampled LOS check which intentionally lets glass and copper grates through, like vanilla. */
private fun clearTrialSpawnerLine(from: Location, to: Location): Boolean {
    val delta = to.toVector().subtract(from.toVector())
    val steps = max(1, kotlin.math.ceil(delta.length() * 4.0).toInt())
    val step = delta.multiply(1.0 / steps)
    var cursor = from.toVector()
    repeat(steps - 1) {
        cursor = cursor.add(step)
        val block = cursor.toLocation(from.world).block
        val material = block.type
        val transparentSolid = material.name.contains("GLASS") ||
                material.name.contains("COPPER_GRATE") || material == Material.IRON_BARS
        if (!block.isPassable && !transparentSolid) return false
    }
    return true
}