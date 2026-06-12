package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite

import dev.wuason.unearthMechanic.compatibilities.craftengine.types.HollowLogStage
import dev.wuason.unearthMechanic.compatibilities.craftengine.types.TermiteNestStage
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.bukkit.block.BukkitBlockManager
import net.momirealms.craftengine.core.block.UpdateFlags
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.util.Direction
import net.momirealms.craftengine.core.util.Key
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.Axis as BukkitAxis
import org.bukkit.block.Block
import org.bukkit.block.data.Orientable
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

object TermiteGameplay {
    var consumedBlocks: Map<String, String> = defaultConsumedBlocks()
    var sawdustMaterial: Material = Material.SUGAR
    var sawdustDropChance: Int = 6
    var maxTermitesPerNest: Int = 1
    var termiteBucketItemId: String = "elitefantasy:termite_bucket"
    var chewStepsRequired: Int = 5
    var hiveBreakReleaseRadius: Int = 3

    private data class ChewProgress(val blockKey: String, val block: Block, var steps: Int)
    private val chewing = ConcurrentHashMap<UUID, ChewProgress>()
    private val blockClaims = ConcurrentHashMap<String, UUID>()
    private val alertedUntil = ConcurrentHashMap<UUID, Long>()
    private val blockPosClass by lazy { Class.forName("net.minecraft.core.BlockPos") }
    private val blockPosConstructor by lazy {
        blockPosClass.getConstructor(
            java.lang.Integer.TYPE,
            java.lang.Integer.TYPE,
            java.lang.Integer.TYPE
        )
    }
    private val blockDestructionPacketConstructor by lazy {
        Class.forName("net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket")
            .getConstructor(
                java.lang.Integer.TYPE,
                blockPosClass,
                java.lang.Integer.TYPE
            )
    }

    fun configureFromBehavior(section: ConfigSection) {
        consumedBlocks = consumedBlocksFromConfig(section, defaultConsumedBlocks())
        sawdustMaterial = materialFromConfig(section.getString("sawdust-material", "SUGAR")) ?: Material.SUGAR
        sawdustDropChance = section.getInt("sawdust-drop-chance", 6).coerceAtLeast(1)
        maxTermitesPerNest = section.getInt("max-termites", 1).coerceAtLeast(1)
        termiteBucketItemId = section.getString("termite-bucket-item", "elitefantasy:termite_bucket")
        chewStepsRequired = section.getInt("chew-steps", 5).coerceAtLeast(1)
        hiveBreakReleaseRadius = section.getInt("hive-break-release-radius", 3).coerceAtLeast(0)
    }

    private fun consumedBlocksFromConfig(
        section: ConfigSection,
        fallback: Map<String, String>
    ): Map<String, String> {
        val root = section.getSection("consumed-blocks") ?: return fallback
        val parsed = linkedMapOf<String, String>()

        for (route in root.keySet()) {
            val sourceBlockId = normalizeBlockId(route)
            val hollowBlockId = root.getString(route, "").trim()
            if (hollowBlockId.isEmpty()) continue
            parsed[sourceBlockId] = hollowBlockId
        }

        return parsed.ifEmpty { fallback }
    }

    private fun materialFromConfig(raw: String): Material? {
        val normalized = raw
            .substringAfter(':')
            .uppercase()
            .replace('-', '_')

        return Material.matchMaterial(normalized)
    }

    fun consumeNearbyWood(termite: LivingEntity, radius: Int = 6, chance: Double = 1.0, eatDistance: Double = 1.65): Boolean {
        cleanupInvalidChew(termite)
        if (shouldPrioritizeCombat(termite)) {
            cancelChewing(termite)
            return false
        }

        val claimed = claimedBlock(termite)
        if (claimed == null && ThreadLocalRandom.current().nextDouble() > chance) return false

        val target = claimed ?: findNearestConsumableWood(termite.location, radius, termite.uniqueId) ?: return false
        val targetCenter = target.location.add(0.5, 0.5, 0.5)

        if (termite.location.distanceSquared(targetCenter) > eatDistance * eatDistance) {
            return moveToward(termite, targetCenter)
        }

        holdStillWhileChewing(termite, targetCenter)
        if (!advanceChewing(termite, target)) return true
        val consumed = consumeWoodBlock(target)
        clearClaim(termite.uniqueId, TermiteKeys.key(target))
        return consumed
    }

    fun alertNearbyTermites(location: Location, radius: Double = 10.0, durationMillis: Long = 6000L) {
        val world = location.world ?: return
        val until = System.currentTimeMillis() + durationMillis

        world.getNearbyEntities(location, radius, radius * 0.5, radius)
            .filterIsInstance<LivingEntity>()
            .filter { MythicTermites.isTermite(it) }
            .forEach { termite ->
                alertedUntil[termite.uniqueId] = until
                cancelChewing(termite)
            }
    }

    fun consumeWoodBlock(block: Block): Boolean {
        val hollowBlockId = hollowBlockFor(block) ?: return false
        convertToHollowLog(block, hollowBlockId)

        if (ThreadLocalRandom.current().nextInt(sawdustDropChance.coerceAtLeast(1)) == 0) {
            block.world.dropItemNaturally(block.location.add(0.5, 0.5, 0.5), sawdustItem())
        }
        return true
    }

    fun updateNestStage(block: Block) {
        val data = TermiteDataStore.get(TermiteKeys.key(block))
        val stage = when {
            data.ownerUuid != null -> TermiteNestStage.friendly
            data.food > 0 -> TermiteNestStage.fed
            data.termites > 0 -> TermiteNestStage.occupied
            else -> TermiteNestStage.empty
        }

        setNestStage(block, stage)
    }

    fun releaseStoredTermites(block: Block): Int {
        val key = TermiteKeys.key(block)
        val stored = TermiteDataStore.peek(key)?.termites?.coerceAtLeast(0) ?: 0
        val amount = stored.takeIf { it > 0 } ?: fallbackTermitesFromNestStage(block)

        repeat(amount) {
            MythicTermites.spawn(block.location.add(0.5, 1.0, 0.5), key)
        }

        TermiteDataStore.remove(key)
        return amount
    }

    fun releaseStoredTermitesAround(block: Block, radius: Int = hiveBreakReleaseRadius): Int {
        var released = 0

        for (dx in -radius..radius) for (dy in -radius..radius) for (dz in -radius..radius) {
            val nearby = block.world.getBlockAt(block.x + dx, block.y + dy, block.z + dz)
            if (!isTermiteNest(nearby)) continue
            released += releaseStoredTermites(nearby)
            if (nearby.location != block.location) updateNestStage(nearby)
        }

        return released
    }

    fun enterNearestNest(termite: LivingEntity, radius: Int = 8): Boolean {
        val nest = findNearestNest(termite.location, radius) ?: return false
        val accepted = TermiteDataStore.addTermites(TermiteKeys.key(nest), 1, maxTermitesPerNest)
        if (accepted <= 0) return false

        termite.remove()
        updateNestStage(nest)
        return true
    }

    fun returnToNestIfClose(termite: LivingEntity, radius: Int = 2): Boolean {
        return enterNearestNest(termite, radius)
    }

    fun releaseFromNest(nest: Block, amount: Int = 1): Int {
        var released = 0
        val key = TermiteKeys.key(nest)

        repeat(amount.coerceAtLeast(1)) {
            if (!TermiteDataStore.takeTermite(key)) return@repeat
            MythicTermites.spawn(nest.location.add(0.5, 1.0, 0.5), key)
            released++
        }

        updateNestStage(nest)
        return released
    }

    fun findNearestNest(location: Location, radius: Int): Block? {
        val world = location.world ?: return null
        var best: Block? = null
        var bestDistance = Double.MAX_VALUE

        for (dx in -radius..radius) for (dy in -radius..radius) for (dz in -radius..radius) {
            val block = world.getBlockAt(location.blockX + dx, location.blockY + dy, location.blockZ + dz)
            if (!isTermiteNest(block)) continue

            val distance = block.location.distanceSquared(location)
            if (distance < bestDistance) {
                best = block
                bestDistance = distance
            }
        }

        return best
    }

    private fun findNearestConsumableWood(location: Location, radius: Int, termiteId: UUID): Block? {
        val world = location.world ?: return null
        var best: Block? = null
        var bestDistance = Double.MAX_VALUE

        for (dx in -radius..radius) for (dy in -2..2) for (dz in -radius..radius) {
            val block = world.getBlockAt(location.blockX + dx, location.blockY + dy, location.blockZ + dz)
            if (!isConsumedWoodCandidate(block)) continue
            val blockKey = TermiteKeys.key(block)
            val claimedBy = blockClaims[blockKey]
            if (claimedBy != null && claimedBy != termiteId) continue

            val distance = block.location.add(0.5, 0.5, 0.5).distanceSquared(location)
            if (distance < bestDistance) {
                best = block
                bestDistance = distance
            }
        }

        best?.let { claimBlock(termiteId, it) }
        return best
    }

    private fun moveToward(termite: LivingEntity, target: Location): Boolean {
        val mob = termite as? Mob ?: return false
        mob.pathfinder.moveTo(target, 1.0)
        return true
    }

    private fun advanceChewing(termite: LivingEntity, block: Block): Boolean {
        val blockKey = TermiteKeys.key(block)
        val progress = chewing.compute(termite.uniqueId) { _, old ->
            if (old == null || old.blockKey != blockKey) ChewProgress(blockKey, block, 1)
            else old.also { it.steps++ }
        } ?: return false

        block.world.playSound(block.location.add(0.5, 0.5, 0.5), Sound.BLOCK_WOOD_HIT, 0.35f, 1.55f)
        MythicTermites.playWoodCuttingAnimation(termite)
        sendBreakAnimation(block, progress.steps.toFloat() / chewStepsRequired.toFloat())

        if (progress.steps < chewStepsRequired) return false
        clearBreakAnimation(block)
        chewing.remove(termite.uniqueId)
        return true
    }

    private fun claimedBlock(termite: LivingEntity): Block? {
        val progress = chewing[termite.uniqueId] ?: return null
        if (!isConsumedWoodCandidate(progress.block)) {
            clearClaim(termite.uniqueId, progress.blockKey)
            return null
        }

        return progress.block
    }

    private fun claimBlock(termiteId: UUID, block: Block) {
        val blockKey = TermiteKeys.key(block)
        blockClaims.compute(blockKey) { _, current ->
            if (current == null || current == termiteId) termiteId else current
        }
    }

    private fun clearClaim(termiteId: UUID, blockKey: String) {
        chewing.remove(termiteId)
        blockClaims.remove(blockKey, termiteId)
    }

    private fun cleanupInvalidChew(termite: LivingEntity) {
        val progress = chewing[termite.uniqueId] ?: return
        if (termite.isDead || !termite.isValid || !isConsumedWoodCandidate(progress.block)) {
            cancelChewing(termite)
        }
    }

    private fun holdStillWhileChewing(termite: LivingEntity, target: Location) {
        (termite as? Mob)?.pathfinder?.stopPathfinding()
        termite.velocity = Vector(0.0, 0.0, 0.0)
        faceLocation(termite, target)
    }

    private fun cancelChewing(termite: LivingEntity) {
        val progress = chewing.remove(termite.uniqueId) ?: return
        clearBreakAnimation(progress.block)
        blockClaims.remove(progress.blockKey, termite.uniqueId)
    }

    private fun faceLocation(entity: LivingEntity, target: Location) {
        val location = entity.location
        val direction = target.toVector().subtract(location.toVector())
        if (direction.lengthSquared() <= 0.0001) return

        location.direction = direction
        entity.teleport(location)
    }

    private fun shouldPrioritizeCombat(termite: LivingEntity): Boolean {
        if (isAlerted(termite)) return true

        val mob = termite as? Mob
        if (mob?.target != null) return true

        return hasEnemyNearby(termite)
    }

    private fun isAlerted(termite: LivingEntity): Boolean {
        val until = alertedUntil[termite.uniqueId] ?: return false
        if (until > System.currentTimeMillis()) return true

        alertedUntil.remove(termite.uniqueId)
        return false
    }

    private fun hasEnemyNearby(termite: LivingEntity, radius: Double = 8.0): Boolean {
        val location = termite.location
        return termite.world.getNearbyEntities(location, radius, 4.0, radius).any { entity ->
            if (entity.uniqueId == termite.uniqueId || entity !is LivingEntity || entity.isDead) return@any false

            val typeName = entity.type.name
            typeName == "SPIDER" || typeName == "CAVE_SPIDER"
        }
    }

    private fun sendBreakAnimation(block: Block, progress: Float) {
        val stage = (progress.coerceIn(0.0f, 0.99f) * 9.0f).toInt().coerceIn(0, 8)
        for (player in nearbyPlayers(block, 32.0)) {
            sendBlockDamage(player, block, stage)
        }
    }

    private fun clearBreakAnimation(block: Block) {
        for (player in nearbyPlayers(block, 32.0)) {
            sendBlockDamage(player, block, -1)
        }
    }

    private fun sendBlockDamage(player: Player, block: Block, stage: Int) {
        if (sendBlockDestructionPacket(player, block, stage)) return

        val progress = if (stage < 0) 0.0f else ((stage + 1).toFloat() / 10.0f).coerceIn(0.0f, 0.99f)
        player.sendBlockDamage(block.location, progress)
    }

    private fun sendBlockDestructionPacket(player: Player, block: Block, stage: Int): Boolean = runCatching {
        val sourceId = TermiteKeys.key(block).hashCode()
        val blockPos = blockPosConstructor.newInstance(block.x, block.y, block.z)
        val packet = blockDestructionPacketConstructor.newInstance(sourceId, blockPos, stage)
        val handle = player.javaClass.getMethod("getHandle").invoke(player)
        val connection = findFieldValue(handle, "connection") ?: return@runCatching false
        val sendMethod = connection.javaClass.methods.firstOrNull { method ->
            method.name == "send" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(packet.javaClass)
        } ?: return@runCatching false

        sendMethod.invoke(connection, packet)
        true
    }.getOrDefault(false)

    private fun findFieldValue(instance: Any, fieldName: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            runCatching {
                val field = type.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(instance)
            }
            type = type.superclass
        }

        return null
    }

    private fun nearbyPlayers(block: Block, radius: Double): List<Player> {
        val center = block.location.add(0.5, 0.5, 0.5)
        val radiusSquared = radius * radius
        return block.world.players.filter { it.location.distanceSquared(center) <= radiusSquared }
    }

    fun isTermiteNest(block: Block): Boolean =
        customBlockHasProperty(block, "stage") && customBlockId(block).contains("termite")

    fun isTermiteComposter(block: Block): Boolean =
        customBlockId(block).contains("termite_composter")

    fun isConsumedWoodCandidate(block: Block): Boolean =
        !isHollowLog(block) && hollowBlockFor(block) != null

    fun isConsumedWoodCandidate(material: Material): Boolean =
        consumedBlocks.containsKey(vanillaBlockId(material))

    fun sawdustItem(): ItemStack {
        val item = ItemStack(sawdustMaterial)
        val meta = item.itemMeta
        meta.setDisplayName("Sawdust")
        item.itemMeta = meta
        return item
    }

    private fun convertToHollowLog(block: Block, hollowBlockId: String) {
        val originalAxis = originalAxis(block)
        val definition = BukkitBlockManager.instance()
            .blockById(Key.of(hollowBlockId))
            .orElse(null)
            ?: return

        var state = definition.variantProvider().states().first()

        val stageProperty = definition.getProperty("stage")
        if (stageProperty != null && ThreadLocalRandom.current().nextInt(5) == 0) {
            @Suppress("UNCHECKED_CAST")
            state = state.with(stageProperty as Property<HollowLogStage>, HollowLogStage.nest)
        }

        val axisProperty = definition.getProperty("axis")
        if (axisProperty != null && originalAxis != null) {
            @Suppress("UNCHECKED_CAST")
            state = state.with(axisProperty as Property<Direction.Axis>, originalAxis)
        }

        BukkitAdaptor.adapt(block.world).setBlockState(
            block.x,
            block.y,
            block.z,
            state,
            UpdateFlags.UPDATE_ALL
        )
    }

    private fun setNestStage(block: Block, stage: TermiteNestStage) {
        val current = CraftEngineBlocks.getCustomBlockState(block.blockData) ?: return
        val stageProperty = current.owner().value().getProperty("stage") ?: return

        @Suppress("UNCHECKED_CAST")
        val next = current.with(stageProperty as Property<TermiteNestStage>, stage)

        BukkitAdaptor.adapt(block.world).setBlockState(
            block.x,
            block.y,
            block.z,
            next,
            UpdateFlags.UPDATE_ALL
        )
    }

    private fun fallbackTermitesFromNestStage(block: Block): Int {
        val current = CraftEngineBlocks.getCustomBlockState(block.blockData) ?: return 0
        val stageProperty = current.owner().value().getProperty("stage") ?: return 0
        val stage = current.get(stageProperty).toString().substringAfterLast('.').lowercase()

        return if (stage in setOf("occupied", "fed", "friendly")) 1 else 0
    }

    private fun BukkitAxis.toCraftEngineAxis(): Direction.Axis = when (this) {
        BukkitAxis.X -> Direction.Axis.X
        BukkitAxis.Y -> Direction.Axis.Y
        BukkitAxis.Z -> Direction.Axis.Z
    }

    private fun originalAxis(block: Block): Direction.Axis? {
        val vanillaAxis = (block.blockData as? Orientable)?.axis
        if (vanillaAxis != null) return vanillaAxis.toCraftEngineAxis()

        val state = CraftEngineBlocks.getCustomBlockState(block.blockData) ?: return null
        val axisProperty = state.owner().value().getProperty("axis") ?: return null
        val value = state.get(axisProperty)

        if (value is Direction.Axis) return value

        return when (value.toString().substringAfterLast('.').lowercase()) {
            "x" -> Direction.Axis.X
            "y" -> Direction.Axis.Y
            "z" -> Direction.Axis.Z
            else -> null
        }
    }

    private fun customBlockId(block: Block): String {
        val state = CraftEngineBlocks.getCustomBlockState(block.blockData) ?: return ""
        return state.owner().value().id().toString()
    }

    private fun customBlockHasProperty(block: Block, property: String): Boolean {
        val state = CraftEngineBlocks.getCustomBlockState(block.blockData) ?: return false
        return state.owner().value().getProperty(property) != null
    }

    private fun hollowBlockFor(block: Block): String? {
        customBlockIdOrNull(block)?.let { customId ->
            if (isHollowBlockId(customId)) return null
            consumedBlocks[customId]?.let { return it }
        }

        return consumedBlocks[vanillaBlockId(block.type)]
    }

    private fun isHollowLog(block: Block): Boolean {
        val customId = customBlockIdOrNull(block) ?: return false
        return isHollowBlockId(customId)
    }

    private fun isHollowBlockId(customId: String): Boolean =
        customId.substringAfter(':').startsWith("hollow_") || customId.contains(":hollow_")

    private fun normalizeBlockId(raw: String): String {
        val cleaned = raw.trim().lowercase()
        return if (cleaned.contains(':')) {
            cleaned
        } else {
            "minecraft:${cleaned.replace('-', '_')}"
        }
    }

    private fun vanillaBlockId(material: Material): String =
        "minecraft:${material.name.lowercase()}"

    private fun customBlockIdOrNull(block: Block): String? {
        val state = CraftEngineBlocks.getCustomBlockState(block.blockData) ?: return null
        return state.owner().value().id().toString().lowercase()
    }

    fun defaultConsumedBlocks(): Map<String, String> = linkedMapOf(
        "minecraft:oak_log" to "elitefantasy:hollow_oak_log",
        "minecraft:oak_wood" to "elitefantasy:hollow_oak_wood",
        "minecraft:stripped_oak_log" to "elitefantasy:hollow_stripped_oak_log",
        "minecraft:stripped_oak_wood" to "elitefantasy:hollow_stripped_oak_wood",

        "minecraft:spruce_log" to "elitefantasy:hollow_spruce_log",
        "minecraft:spruce_wood" to "elitefantasy:hollow_spruce_wood",
        "minecraft:stripped_spruce_log" to "elitefantasy:hollow_stripped_spruce_log",
        "minecraft:stripped_spruce_wood" to "elitefantasy:hollow_stripped_spruce_wood",

        "minecraft:birch_log" to "elitefantasy:hollow_birch_log",
        "minecraft:birch_wood" to "elitefantasy:hollow_birch_wood",
        "minecraft:stripped_birch_log" to "elitefantasy:hollow_stripped_birch_log",
        "minecraft:stripped_birch_wood" to "elitefantasy:hollow_stripped_birch_wood",

        "minecraft:jungle_log" to "elitefantasy:hollow_jungle_log",
        "minecraft:jungle_wood" to "elitefantasy:hollow_jungle_wood",
        "minecraft:stripped_jungle_log" to "elitefantasy:hollow_stripped_jungle_log",
        "minecraft:stripped_jungle_wood" to "elitefantasy:hollow_stripped_jungle_wood",

        "minecraft:acacia_log" to "elitefantasy:hollow_acacia_log",
        "minecraft:acacia_wood" to "elitefantasy:hollow_acacia_wood",
        "minecraft:stripped_acacia_log" to "elitefantasy:hollow_stripped_acacia_log",
        "minecraft:stripped_acacia_wood" to "elitefantasy:hollow_stripped_acacia_wood",

        "minecraft:dark_oak_log" to "elitefantasy:hollow_dark_oak_log",
        "minecraft:dark_oak_wood" to "elitefantasy:hollow_dark_oak_wood",
        "minecraft:stripped_dark_oak_log" to "elitefantasy:hollow_stripped_dark_oak_log",
        "minecraft:stripped_dark_oak_wood" to "elitefantasy:hollow_stripped_dark_oak_wood",

        "minecraft:mangrove_log" to "elitefantasy:hollow_mangrove_log",
        "minecraft:mangrove_wood" to "elitefantasy:hollow_mangrove_wood",
        "minecraft:stripped_mangrove_log" to "elitefantasy:hollow_stripped_mangrove_log",
        "minecraft:stripped_mangrove_wood" to "elitefantasy:hollow_stripped_mangrove_wood",

        "minecraft:cherry_log" to "elitefantasy:hollow_cherry_log",
        "minecraft:cherry_wood" to "elitefantasy:hollow_cherry_wood",
        "minecraft:stripped_cherry_log" to "elitefantasy:hollow_stripped_cherry_log",
        "minecraft:stripped_cherry_wood" to "elitefantasy:hollow_stripped_cherry_wood",

        "minecraft:crimson_stem" to "elitefantasy:hollow_crimson_stem",
        "minecraft:crimson_hyphae" to "elitefantasy:hollow_crimson_hyphae",
        "minecraft:stripped_crimson_stem" to "elitefantasy:hollow_stripped_crimson_stem",
        "minecraft:stripped_crimson_hyphae" to "elitefantasy:hollow_stripped_crimson_hyphae",

        "minecraft:warped_stem" to "elitefantasy:hollow_warped_stem",
        "minecraft:warped_hyphae" to "elitefantasy:hollow_warped_hyphae",
        "minecraft:stripped_warped_stem" to "elitefantasy:hollow_stripped_warped_stem",
        "minecraft:stripped_warped_hyphae" to "elitefantasy:hollow_stripped_warped_hyphae",

        "minecraft:bamboo_block" to "elitefantasy:hollow_bamboo_block",
        "minecraft:stripped_bamboo_block" to "elitefantasy:hollow_stripped_bamboo_block"
    )
}