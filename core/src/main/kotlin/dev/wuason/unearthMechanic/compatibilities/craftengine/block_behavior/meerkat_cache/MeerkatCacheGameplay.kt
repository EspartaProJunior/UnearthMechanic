package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.meerkat_cache

import dev.wuason.adapter.Adapter
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.bukkit.api.CraftEngineItems
import net.momirealms.craftengine.bukkit.block.BukkitBlockManager
import net.momirealms.craftengine.core.block.UpdateFlags
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.util.Key
import io.lumine.mythic.bukkit.MythicBukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.lang.reflect.Modifier
import kotlin.math.max

object MeerkatCacheGameplay {
    const val NO_PACK_SPAWN_TAG: String = "meerkat_no_pack_spawn"

    var cacheBlockId: String = "elitefantasy:meerkat_cache_sand"
    var burrowBlockId: String = "elitefantasy:meerkat_burrow"
    var searchRadius: Double = 12.0
    var takeRadius: Double = 1.6
    var buryRadius: Int = 5
    var buryDistance: Double = 1.8
    var burrowSearchRadius: Int = 10
    var avoidEntityRadius: Double = 7.0
    var calmChance: Double = 0.18
    var calmTicks: Long = 60L
    var brushUses: Int = 4
    var maxCachesPerChunk: Int = 8
    var dropBuriedItemWhenBroken: Boolean = true
    var shelteredMeerkatMobId: String = "meerkat"
    var allowAnySandBlock: Boolean = false
    var allowedItems: Set<String> = defaultAllowedItems()
    var blockedItems: Set<String> = defaultBlockedItems()
    var validBuryBlocks: Set<Material> = setOf(Material.SAND)

    private data class HeldItem(val item: ItemStack)
    private val heldItems = ConcurrentHashMap<UUID, HeldItem>()
    private val claimedItems = ConcurrentHashMap<UUID, UUID>()
    private val claimedBlocks = ConcurrentHashMap<String, UUID>()
    private val calmUntil = ConcurrentHashMap<UUID, Long>()

    fun configureFromBehavior(section: ConfigSection) {
        cacheBlockId = section.getString("cache-block", cacheBlockId)
        burrowBlockId = section.getString("burrow-block", burrowBlockId)
        searchRadius = section.getDouble("search-radius", searchRadius).coerceAtLeast(1.0)
        takeRadius = section.getDouble("take-radius", takeRadius).coerceAtLeast(0.5)
        buryRadius = section.getInt("bury-radius", buryRadius).coerceAtLeast(1)
        buryDistance = section.getDouble("bury-distance", buryDistance).coerceAtLeast(0.5)
        burrowSearchRadius = section.getInt("burrow-search-radius", burrowSearchRadius).coerceAtLeast(1)
        avoidEntityRadius = section.getDouble("avoid-entity-radius", avoidEntityRadius).coerceAtLeast(1.0)
        calmChance = section.getDouble("calm-chance", calmChance).coerceIn(0.0, 1.0)
        calmTicks = section.getInt("calm-ticks", calmTicks.toInt()).coerceAtLeast(1).toLong()
        brushUses = section.getInt("brush-uses", brushUses).coerceAtLeast(1)
        maxCachesPerChunk = section.getInt("max-caches-per-chunk", maxCachesPerChunk).coerceAtLeast(1)
        dropBuriedItemWhenBroken = section.getBoolean("drop-buried-item-when-broken", dropBuriedItemWhenBroken)
        shelteredMeerkatMobId = section.getString("sheltered-meerkat-mob", shelteredMeerkatMobId)
        allowAnySandBlock = section.getBoolean("allow-any-sand-block", allowAnySandBlock)
        allowedItems = itemIdsFromList(section.getStringList("allowed-items"), defaultAllowedItems())
        blockedItems = itemIdsFromList(section.getStringList("blocked-items"), defaultBlockedItems())
        validBuryBlocks = materialsFromList(section.getStringList("valid-bury-blocks"), setOf(Material.SAND))
    }

    fun hasHeldItem(meerkat: LivingEntity): Boolean =
        heldItems.containsKey(meerkat.uniqueId)

    fun takeNearbyItem(meerkat: LivingEntity): Boolean {
        if (shouldStayCalm(meerkat)) return true
        if (hasHeldItem(meerkat)) return false
        val item = findNearestAllowedItem(meerkat) ?: return false
        val distanceSquared = item.location.distanceSquared(meerkat.location)

        if (distanceSquared > takeRadius * takeRadius) {
            moveToward(meerkat, item.location)
            return true
        }

        val stack = item.itemStack
        val one = stack.clone().also { it.amount = 1 }
        heldItems[meerkat.uniqueId] = HeldItem(one)
        claimedItems.remove(item.uniqueId, meerkat.uniqueId)
        removeOne(item)

        meerkat.world.playSound(meerkat.location, Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.6f)
        return true
    }

    fun buryHeldItem(meerkat: LivingEntity): Boolean {
        if (shouldStayCalm(meerkat)) return true
        val held = heldItems[meerkat.uniqueId] ?: return false
        if (moveAwayFromNearbyEntities(meerkat)) return true
        val target = findClaimedOrNearestBuryBlock(meerkat) ?: return false
        val center = target.location.add(0.5, 0.5, 0.5)

        if (meerkat.location.distanceSquared(center) > buryDistance * buryDistance) {
            return moveToward(meerkat, center)
        }

        val worldName = target.world.name
        if (MeerkatCacheDataStore.countInChunk(worldName, target.x shr 4, target.z shr 4) >= maxCachesPerChunk) {
            releaseBlockClaim(meerkat.uniqueId, target)
            return false
        }

        val key = MeerkatCacheKeys.key(target)
        val original = target.type
        if (!setCustomBlock(target, cacheBlockId)) {
            releaseBlockClaim(meerkat.uniqueId, target)
            return false
        }

        MeerkatCacheDataStore.put(
            key,
            MeerkatCacheDataStore.CacheData(
                world = worldName,
                x = target.x,
                y = target.y,
                z = target.z,
                kind = "cache",
                originalBlock = original,
                item = held.item.clone(),
                shelteredMeerkats = 0,
                updatedAt = System.currentTimeMillis()
            )
        )

        heldItems.remove(meerkat.uniqueId)
        releaseBlockClaim(meerkat.uniqueId, target)
        target.world.playSound(target.location.add(0.5, 0.5, 0.5), Sound.BLOCK_SAND_BREAK, 0.7f, 1.25f)
        return true
    }

    fun useNightBurrow(meerkat: LivingEntity): Boolean {
        if (!isNight(meerkat.location.world?.time ?: return false)) return false
        if (shouldStayCalm(meerkat)) return true

        val nearbyBurrow = findNearestExistingBurrow(meerkat)
        if (nearbyBurrow != null) {
            return enterExistingBurrow(meerkat, nearbyBurrow)
        }

        val target = findClaimedOrNearestBurrowBlock(meerkat) ?: return false
        val center = target.location.add(0.5, 0.1, 0.5)

        if (meerkat.location.distanceSquared(center) > buryDistance * buryDistance) {
            return moveToward(meerkat, center)
        }

        val key = MeerkatCacheKeys.key(target)
        val original = target.type
        if (!setCustomBlock(target, burrowBlockId)) {
            releaseBlockClaim(meerkat.uniqueId, target)
            return false
        }

        MeerkatCacheDataStore.put(
            key,
            MeerkatCacheDataStore.CacheData(
                world = target.world.name,
                x = target.x,
                y = target.y,
                z = target.z,
                kind = "burrow",
                originalBlock = original,
                item = null,
                shelteredMeerkats = 1,
                updatedAt = System.currentTimeMillis()
            )
        )

        releaseBlockClaim(meerkat.uniqueId, target)
        target.world.playSound(target.location.add(0.5, 0.1, 0.5), Sound.BLOCK_SAND_BREAK, 0.7f, 0.9f)
        meerkat.remove()
        return true
    }

    private fun enterExistingBurrow(meerkat: LivingEntity, burrow: Block): Boolean {
        val center = burrow.location.add(0.5, 0.1, 0.5)
        if (meerkat.location.distanceSquared(center) > buryDistance * buryDistance) {
            return moveToward(meerkat, center)
        }

        val key = MeerkatCacheKeys.key(burrow)
        val data = MeerkatCacheDataStore.peek(key) ?: return false
        if (data.kind != "burrow") return false

        data.shelteredMeerkats += 1
        MeerkatCacheDataStore.put(key, data)
        burrow.world.playSound(center, Sound.BLOCK_SAND_STEP, 0.6f, 0.8f)
        meerkat.remove()
        return true
    }

    fun targetNearestArachnid(meerkat: LivingEntity): Boolean {
        val mob = meerkat as? Mob ?: return false
        val target = meerkat.getNearbyEntities(10.0, 5.0, 10.0)
            .filterIsInstance<LivingEntity>()
            .filter { it.type == org.bukkit.entity.EntityType.SPIDER || it.type == org.bukkit.entity.EntityType.CAVE_SPIDER }
            .minByOrNull { it.location.distanceSquared(meerkat.location) }
            ?: return false

        mob.target = target
        mob.pathfinder.moveTo(target.location, 1.25)
        return true
    }

    fun isCacheBlock(block: Block): Boolean =
        customBlockId(block) == cacheBlockId.lowercase()

    fun isBurrowBlock(block: Block): Boolean =
        customBlockId(block) == burrowBlockId.lowercase()

    data class CacheReveal(val item: ItemStack?, val releasedMeerkats: Int)

    fun revealWithBrush(block: Block): CacheReveal? {
        val key = MeerkatCacheKeys.key(block)
        val data = MeerkatCacheDataStore.incrementBrushProgress(key) ?: return null
        if (data.kind != "cache" || data.brushProgress < brushUses) return null

        MeerkatCacheDataStore.remove(key)
        restoreOriginalBlock(block, data.originalBlock)
        releaseShelteredMeerkats(block.location.add(0.5, 1.0, 0.5), data.shelteredMeerkats)
        return CacheReveal(data.item?.clone(), data.shelteredMeerkats)
    }

    fun breakCacheBlock(block: Block): CacheReveal? {
        val data = MeerkatCacheDataStore.remove(MeerkatCacheKeys.key(block)) ?: return null
        releaseShelteredMeerkats(block.location.add(0.5, 1.0, 0.5), data.shelteredMeerkats)
        return CacheReveal(
            item = if (dropBuriedItemWhenBroken) data.item?.clone() else null,
            releasedMeerkats = data.shelteredMeerkats
        )
    }

    fun releaseDayBurrows(): Int {
        var released = 0
        for ((key, data) in MeerkatCacheDataStore.all()) {
            if (data.kind != "burrow") continue
            val world = Bukkit.getWorld(data.world) ?: continue
            if (isNight(world.time)) continue
            val block = world.getBlockAt(data.x, data.y, data.z)
            MeerkatCacheDataStore.remove(key)
            restoreOriginalBlock(block, data.originalBlock)
            releaseShelteredMeerkats(block.location.add(0.5, 1.0, 0.5), data.shelteredMeerkats)
            released += data.shelteredMeerkats
        }
        return released
    }

    private fun findNearestAllowedItem(meerkat: LivingEntity): Item? {
        var best: Item? = null
        var bestDistance = Double.MAX_VALUE

        for (entity in meerkat.getNearbyEntities(searchRadius, searchRadius / 2.0, searchRadius)) {
            val item = entity as? Item ?: continue
            if (!canTake(item.itemStack)) continue
            val claimedBy = claimedItems[item.uniqueId]
            if (claimedBy != null && claimedBy != meerkat.uniqueId) continue

            val distance = item.location.distanceSquared(meerkat.location)
            if (distance < bestDistance) {
                best = item
                bestDistance = distance
            }
        }

        best?.let { claimedItems[it.uniqueId] = meerkat.uniqueId }
        return best
    }

    private fun findClaimedOrNearestBuryBlock(meerkat: LivingEntity): Block? {
        claimedBlocks.entries.firstOrNull { it.value == meerkat.uniqueId }?.key?.let { key ->
            blockFromKey(key)?.takeIf { isBuryCandidate(it) }?.let { return it }
        }

        val origin = meerkat.location.block
        var best: Block? = null
        var bestDistance = Double.MAX_VALUE

        for (dx in -buryRadius..buryRadius) for (dy in -1..1) for (dz in -buryRadius..buryRadius) {
            val block = origin.world.getBlockAt(origin.x + dx, origin.y + dy, origin.z + dz)
            if (!isBuryCandidate(block)) continue

            val key = MeerkatCacheKeys.key(block)
            val claimedBy = claimedBlocks[key]
            if (claimedBy != null && claimedBy != meerkat.uniqueId) continue

            val distance = block.location.distanceSquared(meerkat.location)
            if (distance < bestDistance) {
                best = block
                bestDistance = distance
            }
        }

        best?.let { claimedBlocks[MeerkatCacheKeys.key(it)] = meerkat.uniqueId }
        return best
    }

    private fun findClaimedOrNearestBurrowBlock(meerkat: LivingEntity): Block? {
        claimedBlocks.entries.firstOrNull { it.value == meerkat.uniqueId }?.key?.let { key ->
            blockFromKey(key)?.takeIf { isBurrowPlacementCandidate(it) }?.let { return it }
        }

        val origin = meerkat.location.block
        var best: Block? = null
        var bestDistance = Double.MAX_VALUE

        for (dx in -buryRadius..buryRadius) for (dy in -1..1) for (dz in -buryRadius..buryRadius) {
            val block = origin.world.getBlockAt(origin.x + dx, origin.y + dy, origin.z + dz)
            if (!isBurrowPlacementCandidate(block)) continue

            val key = MeerkatCacheKeys.key(block)
            val claimedBy = claimedBlocks[key]
            if (claimedBy != null && claimedBy != meerkat.uniqueId) continue

            val distance = block.location.distanceSquared(meerkat.location)
            if (distance < bestDistance) {
                best = block
                bestDistance = distance
            }
        }

        best?.let { claimedBlocks[MeerkatCacheKeys.key(it)] = meerkat.uniqueId }
        return best
    }

    private fun findNearestExistingBurrow(meerkat: LivingEntity): Block? {
        val origin = meerkat.location
        var best: Block? = null
        var bestDistance = Double.MAX_VALUE

        for ((_, data) in MeerkatCacheDataStore.all()) {
            if (data.kind != "burrow") continue
            if (data.world != origin.world?.name) continue

            val world = origin.world ?: continue
            val block = world.getBlockAt(data.x, data.y, data.z)
            if (!isBurrowBlock(block)) continue

            val distance = block.location.distanceSquared(origin)
            if (distance > burrowSearchRadius * burrowSearchRadius) continue
            if (distance < bestDistance) {
                best = block
                bestDistance = distance
            }
        }

        return best
    }

    private fun isBuryCandidate(block: Block): Boolean {
        if (!isValidSandBlock(block.type)) return false
        if (!block.getRelative(0, 1, 0).type.isAir) return false
        if (MeerkatCacheDataStore.peek(MeerkatCacheKeys.key(block)) != null) return false
        return true
    }

    private fun isBurrowPlacementCandidate(block: Block): Boolean {
        if (!block.type.isAir) return false
        if (!isValidSandBlock(block.getRelative(0, -1, 0).type)) return false
        if (MeerkatCacheDataStore.peek(MeerkatCacheKeys.key(block)) != null) return false
        return true
    }

    private fun isValidSandBlock(material: Material): Boolean {
        if (material in validBuryBlocks) return true
        return allowAnySandBlock && material == Material.SAND
    }

    private fun moveToward(entity: LivingEntity, target: Location): Boolean {
        val mob = entity as? Mob ?: return false
        mob.pathfinder.moveTo(target, 1.0)
        return true
    }

    private fun removeOne(item: Item) {
        val stack = item.itemStack
        if (stack.amount <= 1) {
            item.remove()
            return
        }
        stack.amount = max(1, stack.amount - 1)
        item.itemStack = stack
    }

    private fun canTake(stack: ItemStack): Boolean {
        if (stack.type.isAir) return false
        val ids = itemIds(stack)
        if (ids.any { it in blockedItems }) return false
        if (stack.type.name.endsWith("SHULKER_BOX")) return false
        return ids.any { it in allowedItems }
    }

    private fun setCustomBlock(block: Block, blockId: String): Boolean {
        val definition = BukkitBlockManager.instance()
            .blockById(Key.of(blockId))
            .orElse(null)
            ?: return false

        val state = definition.variantProvider().states().firstOrNull() ?: return false
        BukkitAdaptor.adapt(block.world).setBlockState(
            block.x,
            block.y,
            block.z,
            state,
            UpdateFlags.UPDATE_ALL
        )
        return true
    }

    private fun releaseShelteredMeerkats(location: Location, amount: Int) {
        repeat(amount.coerceAtLeast(0)) {
            val spawned = MythicBukkit.inst().apiHelper.spawnMythicMob(shelteredMeerkatMobId, location)
            tagSpawnedNoPack(spawned)
        }
    }

    private fun tagSpawnedNoPack(spawned: Any?) {
        val activeEntity = spawned?.javaClass?.methods
            ?.firstOrNull { it.name == "getEntity" && it.parameterCount == 0 }
            ?.invoke(spawned)
            ?: return
        val bukkitEntity = activeEntity.javaClass.methods
            .firstOrNull { it.name == "getBukkitEntity" && it.parameterCount == 0 }
            ?.invoke(activeEntity) as? org.bukkit.entity.Entity
            ?: return

        bukkitEntity.addScoreboardTag(NO_PACK_SPAWN_TAG)
    }

    private fun shouldStayCalm(meerkat: LivingEntity): Boolean {
        val now = System.currentTimeMillis()
        val until = calmUntil[meerkat.uniqueId]
        if (until != null && until > now) {
            (meerkat as? Mob)?.pathfinder?.stopPathfinding()
            return true
        }

        if (ThreadLocalRandom.current().nextDouble() > calmChance) return false
        calmUntil[meerkat.uniqueId] = now + calmTicks * 50L
        (meerkat as? Mob)?.pathfinder?.stopPathfinding()
        return true
    }

    private fun moveAwayFromNearbyEntities(meerkat: LivingEntity): Boolean {
        val nearby = meerkat.getNearbyEntities(avoidEntityRadius, avoidEntityRadius / 2.0, avoidEntityRadius)
            .filter { it !is Item && it.uniqueId != meerkat.uniqueId }
        if (nearby.isEmpty()) return false

        var away = meerkat.location.toVector()
        for (entity in nearby) {
            away = away.add(meerkat.location.toVector().subtract(entity.location.toVector()))
        }

        if (away.lengthSquared() <= 0.001) return false
        val target = meerkat.location.clone().add(away.normalize().multiply(6.0))
        return moveToward(meerkat, target)
    }

    private fun isNight(time: Long): Boolean = time in 13000L..23000L

    private fun restoreOriginalBlock(block: Block, material: Material) {
        block.type = material
    }

    private fun customBlockId(block: Block): String? {
        val state = CraftEngineBlocks.getCustomBlockState(block.blockData) ?: return null
        return state.owner().value().id().toString().lowercase()
    }

    private fun releaseBlockClaim(meerkatId: UUID, block: Block) {
        claimedBlocks.remove(MeerkatCacheKeys.key(block), meerkatId)
    }

    private fun blockFromKey(key: String): Block? {
        val worldName = key.substringBefore(':')
        val coords = key.substringAfter(':').split(',')
        if (coords.size != 3) return null
        val world = org.bukkit.Bukkit.getWorld(worldName) ?: return null
        return world.getBlockAt(
            coords[0].toIntOrNull() ?: return null,
            coords[1].toIntOrNull() ?: return null,
            coords[2].toIntOrNull() ?: return null
        )
    }

    private fun materialsFromList(raw: List<String>, fallback: Set<Material>): Set<Material> {
        val parsed = raw.mapNotNull { materialFromConfig(it) }.toSet()
        return parsed.ifEmpty { fallback }
    }

    private fun materialFromConfig(raw: String): Material? {
        val normalized = raw.substringAfter(':').uppercase().replace('-', '_')
        return Material.matchMaterial(normalized)
    }

    private fun itemIdsFromList(raw: List<String>, fallback: Set<String>): Set<String> {
        val parsed = raw.map { normalizeItemId(it) }.filter { it.isNotBlank() }.toSet()
        return parsed.ifEmpty { fallback }
    }

    private fun itemIds(stack: ItemStack): Set<String> {
        val ids = linkedSetOf(
            stack.type.name.uppercase(),
            "minecraft:${stack.type.name.lowercase()}"
        )
        adapterItemId(stack)?.let { ids.add(normalizeItemId(it)) }
        CraftEngineItems.getCustomItemId(stack)?.let { ids.add(normalizeItemId(it.toString())) }
        return ids
    }

    private fun adapterItemId(stack: ItemStack): String? = runCatching {
        val methods = Adapter::class.java.methods
        val candidates = setOf("getItemId", "getItemID", "getCustomItemId", "getCustomItemID", "getNamespacedId")
        methods.firstOrNull { method ->
            method.name in candidates &&
                    Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(ItemStack::class.java)
        }?.invoke(null, stack) as? String
    }.getOrNull()

    private fun normalizeItemId(raw: String): String {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return ""
        return if (cleaned.contains(':')) cleaned.lowercase() else cleaned.uppercase()
    }

    private fun defaultAllowedItems(): Set<String> = setOf(
        "BONE",
        "STRING",
        "SPIDER_EYE",
        "FERMENTED_SPIDER_EYE",
        "GOLD_NUGGET",
        "EMERALD",
        "WHEAT_SEEDS",
        "MELON_SEEDS",
        "PUMPKIN_SEEDS",
        "BREAD",
        "APPLE"
    )

    private fun defaultBlockedItems(): Set<String> = setOf(
        "BUNDLE",
        "DEBUG_STICK",
        "COMMAND_BLOCK",
        "CHAIN_COMMAND_BLOCK",
        "REPEATING_COMMAND_BLOCK",
        "STRUCTURE_BLOCK",
        "STRUCTURE_VOID",
        "BARRIER"
    )
}