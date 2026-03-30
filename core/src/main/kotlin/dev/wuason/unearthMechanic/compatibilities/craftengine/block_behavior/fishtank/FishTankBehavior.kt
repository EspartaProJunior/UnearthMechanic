package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.fishtank


import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.compatibilities.craftengine.types.FishType
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.bukkit.util.BlockStateUtils
import net.momirealms.craftengine.core.block.CustomBlock
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.properties.Property
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.registry.Holder
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Axolotl
import org.bukkit.entity.Cod
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.PufferFish
import org.bukkit.entity.Salmon
import org.bukkit.entity.TropicalFish
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Tadpole
import org.bukkit.inventory.meta.AxolotlBucketMeta
import org.bukkit.inventory.meta.TropicalFishBucketMeta
import org.bukkit.persistence.PersistentDataType
import java.util.Base64
import java.util.EnumMap
import java.util.concurrent.ThreadLocalRandom
import kotlin.collections.iterator
import kotlin.math.atan2
import kotlin.math.sqrt

class FishTankBehavior(
    customBlock: CustomBlock,
    private val fishProperty: Property<FishType>
) : BukkitBlockBehavior(customBlock) {

    companion object {
        val FACTORY = Factory()
        class Factory : BlockBehaviorFactory<FishTankBehavior> {
            override fun create(block: CustomBlock, section: ConfigSection): FishTankBehavior {
                val prop = block.getProperty("fish")
                    ?: throw IllegalArgumentException("Missing 'fish' property")
                @Suppress("UNCHECKED_CAST")
                return FishTankBehavior(block, prop as Property<FishType>)
            }
        }

        private fun key(world: World, pos: BlockPos): String =
            "${world.name()}:${pos.x()},${pos.y()},${pos.z()}"

        private fun key(worldName: String, pos: BlockPos): String =
            "$worldName:${pos.x()},${pos.y()},${pos.z()}"

        // entity per block
        private val fishEntities = ConcurrentHashMap<String, MutableSet<UUID>>()

        private data class Nav(var target: BlockPos? = null, var last: BlockPos? = null, var off: Triple<Double,Double,Double>? = null)
        private val navByEntity = ConcurrentHashMap<UUID, Nav>()

        // Cache: each cell of the tank -> tankKey (per world)
        private val cellToTankKey = ConcurrentHashMap<String, ConcurrentHashMap<Long, String>>()

        // Cache: tank -> root packed (for faster validation if desired)
        private fun worldCellMap(worldName: String): ConcurrentHashMap<Long, String> =
            cellToTankKey.computeIfAbsent(worldName) { ConcurrentHashMap() }

        // Cache all cells in the tank
        private fun cacheTankCells(worldName: String, tankKey: String, cells: List<BlockPos>) {
            val m = worldCellMap(worldName)
            for (c in cells) m[pack(c)] = tankKey
        }

        // Clear all cells from the cache (when you have the list)
        private fun uncacheTankCells(worldName: String, tankKey: String, cells: List<BlockPos>) {
            val m = cellToTankKey[worldName] ?: return
            for (c in cells) {
                val pk = pack(c)
                // Only delete if it still points to THIS tankKey (avoid stepping on another tank)
                if (m[pk] == tankKey) m.remove(pk)
            }
        }

        // Quick validation: tankKey exists and clicked belongs to its cellSet
        private fun isCachedKeyValid(worldName: String, clicked: BlockPos, tankKey: String, expectedId: String): Boolean {
            val ref = tanks[tankKey] ?: return false
            if (ref.worldName != worldName) return false
            if (ref.expectedId != expectedId) return false
            if (ref.cells.isEmpty() || ref.cellSet.isEmpty()) return false
            if (!ref.cellSet.contains(pack(clicked))) return false

            // Opcional extra: comprobar que la celda sigue siendo aquarium (barato)
            val bw = Bukkit.getWorld(worldName) ?: return false
            if (!isAquariumCell(bw, clicked, expectedId)) return false
            return true
        }

        // Fallback by nearby entity: reads PDC and returns tankKey if applicable
        private fun tankKeyFromNearbyEntity(bw: org.bukkit.World, center: Location, expectedId: String): String? {
            return bw.getNearbyEntities(center, 6.0, 6.0, 6.0)
                .asSequence()
                .mapNotNull { it as? LivingEntity }
                .firstOrNull { it.scoreboardTags.contains(TAG_FISHTANK) }
                ?.let { ent ->
                    val k = readTankKey(ent) ?: return@let null
                    val ref = tanks[k] ?: return@let k // si aún no hay ref, igual sirve para reusar key
                    if (ref.expectedId == expectedId) k else null
                }
        }

        // task global
        @Volatile private var taskStarted = false

        private val TAG_FISHTANK = "um_fishtank"
        private val PDC_TANK_KEY by lazy { NamespacedKey(UnearthMechanic.getInstance(), "fishtank_key") }

        private fun serializeItem(item: ItemStack): String {
            try {
                val m = ItemStack::class.java.getMethod("serializeAsBytes")
                val bytes = m.invoke(item) as ByteArray
                return Base64.getEncoder().encodeToString(bytes)
            } catch (_: Throwable) {
                // Fallback (menos ideal): Bukkit map serialize
                val map = item.serialize() // Map<String, Any>
                val json = YamlConfiguration().apply {
                    set("i", map)
                }.saveToString()
                return Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
            }
        }

        private fun deserializeItem(b64: String): ItemStack? {
            val clean = b64.replace("\\s+".toRegex(), "") // quita \n, espacios, etc.
            val bytes = try { Base64.getDecoder().decode(clean) } catch (_: Throwable) { return null }

            // Prefer: Paper deserializeBytes
            try {
                val m = ItemStack::class.java.getMethod("deserializeBytes", ByteArray::class.java)
                return m.invoke(null, bytes) as? ItemStack
            } catch (_: Throwable) {
                // Fallback from yaml-string
                return try {
                    val s = bytes.toString(Charsets.UTF_8)
                    val yml = YamlConfiguration()
                    yml.loadFromString(s)
                    @Suppress("UNCHECKED_CAST")
                    val map = yml.get("i") as? Map<String, Any> ?: return null
                    ItemStack.deserialize(map)
                } catch (_: Throwable) {
                    null
                }
            }
        }

        private fun markAsTankEntity(e: LivingEntity, tankKey: String) {
            e.addScoreboardTag(TAG_FISHTANK)
            e.persistentDataContainer.set(PDC_TANK_KEY, PersistentDataType.STRING, tankKey)
        }

        private fun readTankKey(e: LivingEntity): String? =
            e.persistentDataContainer.get(PDC_TANK_KEY, PersistentDataType.STRING)

        private fun writeBucketSnapshot(tankKey: String, cell: BlockPos, fish: FishType, bucket: ItemStack) {
            if (fish == FishType.none) return
            try {
                val snap = bucket.clone().also { it.amount = 1 }
                val b64 = serializeItem(snap)
                FishTankDataStore.setCellBucketSnapshotB64(tankKey, pack(cell), fish, b64)
            } catch (t: Throwable) {
                Bukkit.getLogger().warning("[FishTank] writeBucketSnapshot failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        private fun readBucketSnapshot(tankKey: String, packedCell: Long, fish: FishType): ItemStack? {
            val snap = FishTankDataStore.getCellSnapshot(tankKey, packedCell) ?: return null
            if (snap.fishType != fish.name) return null
            val b64 = snap.bucketB64 ?: return null
            return deserializeItem(b64)
        }

        fun fishFromBucket(mat: Material): FishType? = when (mat) {
            Material.COD_BUCKET -> FishType.cod
            Material.SALMON_BUCKET -> FishType.salmon
            Material.TROPICAL_FISH_BUCKET -> FishType.tropical_fish
            Material.PUFFERFISH_BUCKET -> FishType.pufferfish
            Material.AXOLOTL_BUCKET -> FishType.axolotl
            Material.TADPOLE_BUCKET -> FishType.tadpole
            else -> null
        }

        fun bucketForFish(f: FishType): Material? = when (f) {
            FishType.cod -> Material.COD_BUCKET
            FishType.salmon -> Material.SALMON_BUCKET
            FishType.tropical_fish -> Material.TROPICAL_FISH_BUCKET
            FishType.pufferfish -> Material.PUFFERFISH_BUCKET
            FishType.axolotl -> Material.AXOLOTL_BUCKET
            FishType.tadpole -> Material.TADPOLE_BUCKET
            FishType.none -> null
        }

        private fun adoptOrPurgeEntities(bw: org.bukkit.World, ref: TankRef, tankKey: String) {
            val center = tankCenter(ref, bw)
            val radius = tankRadius(ref)
            val ids = getIdSet(tankKey)

            for (near in bw.getNearbyEntities(center, radius, radius, radius)) {
                val le = near as? LivingEntity ?: continue

                if (!isDisplayFish(le)) continue

                val cell = BlockPos(le.location.blockX, le.location.blockY, le.location.blockZ)
                val inside = ref.cellSet.contains(pack(cell))

                if (inside) {
                    // ADOPT even if you have another tankKey or do not have PDC
                    markAsTankEntity(le, tankKey)
                    ids.add(le.uniqueId)
                } else {
                    // Outside the tank: delete ONLY if it is orphaned from THIS tank (same key) or legacy (no key).
                    val k = readTankKey(le)
                    if (k == null || k == tankKey) {
                        le.remove()
                        ids.remove(le.uniqueId)
                        navByEntity.remove(le.uniqueId)
                    }
                }
            }

            // Extra cleaning (optional but recommended)
            val it = ids.iterator()
            while (it.hasNext()) {
                val id = it.next()
                val e = bw.getEntity(id) as? LivingEntity
                if (e == null || e.isDead) {
                    it.remove()
                    navByEntity.remove(id)
                }
            }
        }

        fun ensureTaskRunning() {
            if (taskStarted) return
            val plugin = Bukkit.getPluginManager().getPlugin("UnearthMechanic") ?: return
            taskStarted = true

            Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                if (tanks.isEmpty()) return@Runnable

                for ((k, ref) in tanks) {
                    var tankKey = k

                    val bw = Bukkit.getWorld(ref.worldName) ?: continue
                    val ids = fishEntities[tankKey]
                    if (ids == null || ids.isEmpty()) {
                        continue
                    }

                    // cleans dead IDs and creates live list
                    val alive = ArrayList<LivingEntity>(ids.size)
                    val it = ids.iterator()
                    while (it.hasNext()) {
                        val id = it.next()
                        val e = bw.getEntity(id) as? LivingEntity
                        if (e == null || e.isDead) {
                            it.remove()
                            continue
                        }
                        alive.add(e)
                    }

                    if (alive.isEmpty()) {
                        fishEntities.remove(tankKey)
                        tanks.remove(tankKey)?.let { r ->
                            if (r.cells.isNotEmpty()) uncacheTankCells(r.worldName, tankKey, r.cells)
                        }
                        continue
                    }

                    ref.dbgTick++

                    val nowMs = System.currentTimeMillis()
                    val doLog = (ref.dbgTick % 40 == 0) || (nowMs - ref.dbgLastLog > 5000) // cada 40 ticks o 5s
                    if (doLog) ref.dbgLastLog = nowMs

                    /*if (doLog) {
                        Bukkit.getLogger().info(
                            "[FishTankTick] k=$k fish=${ref.fish} ent=${ent.type} " +
                                    "loc=${ent.location.blockX},${ent.location.blockY},${ent.location.blockZ} " +
                                    "cells=${ref.cells.size} set=${ref.cellSet.size} target=${ref.target?.let { "${it.x()},${it.y()},${it.z()}" }}"
                        )
                    }*/

                    val cells = ref.cells
                    if (cells.isEmpty()) continue

                    // current fish cell
                    val entRef = alive[0]
                    val locRef = entRef.location
                    var curCell = BlockPos(locRef.blockX, locRef.blockY, locRef.blockZ)

                    val expectedId = ref.expectedId

                    val t = ref.target
                    val now = System.currentTimeMillis()

                    if (t != null && !isAquariumCell(bw, t, expectedId)) {
                        if (now >= ref.brokenLogCooldownUntil) {
                            ref.brokenLogCooldownUntil = now + 500
                            /*Bukkit.getLogger().warning(
                                "[FishTankTick] k=$k target invalid (broken block) -> FORCE RESCAN target=${t.x()},${t.y()},${t.z()}"
                            )*/
                        }

                        ref.target = null
                        ref.lastCell = null
                        ref.targetOffset = null
                    }

                    // if the current cell is no longer an aquarium (it broke around and ended up “outside”), rescan it too
                    if (!isAquariumCell(bw, curCell, expectedId)) {
                        if (now >= ref.forceRescanCooldownUntil) {
                            ref.forceRescanCooldownUntil = now + 500

                            // quitar cache anterior
                            if (ref.cells.isNotEmpty()) {
                                uncacheTankCells(ref.worldName, tankKey, ref.cells)
                            }

                            val scanned = scanConnectedBukkit(bw, ref.root, expectedId, 16, 512)

                            // DEAD TANK (broken block / no longer an aquarium)
                            if (scanned.isEmpty()) {
                                val center = Location(bw, ref.root.x() + 0.5, ref.root.y() + 0.5, ref.root.z() + 0.5)

                                // delete registered entities
                                fishEntities.remove(tankKey)?.forEach { id ->
                                    bw.getEntity(id)?.remove()
                                    navByEntity.remove(id)
                                }

                                // Delete nearby legacy files (in case any remain unregistered).
                                cleanupTankEntities(bw, center, tankKey, null)

                                // clears caches
                                tanks.remove(tankKey)
                                cellToTankKey[ref.worldName]?.entries?.removeIf { it.value == tankKey }

                                // clean yml
                                FishTankDataStore.removeTank(tankKey)

                                continue
                            }

                            // rescan normal
                            ref.cells = scanned
                            ref.root = canonicalRoot(ref.cells)

                            val newKey = key(ref.worldName, ref.root)
                            if (newKey != tankKey) {
                                FishTankDataStore.migrateKey(tankKey, newKey)

                                fishEntities.remove(tankKey)?.let { set -> fishEntities[newKey] = set }
                                tanks.remove(tankKey)?.let { tr -> tanks[newKey] = tr }

                                tankKey = newKey
                            }

                            ref.cellSet = HashSet(ref.cells.size * 2)
                            for (c in ref.cells) ref.cellSet.add(pack(c))
                            cacheTankCells(ref.worldName, tankKey, ref.cells)

                            ref.target = null
                            ref.lastCell = null
                            ref.targetOffset = null
                        }

                        curCell = ref.root
                    }

                    val curPacked = pack(curCell)
                    val curInside = ref.cellSet.contains(curPacked)

                    // if it is not within the set, re-route to root
                    if (!curInside) {
                        /*if (doLog) Bukkit.getLogger().warning(
                            "[FishTankTick] k=$k curCell OUTSIDE set cur=${curCell.x()},${curCell.y()},${curCell.z()} pack=$curPacked -> reroute root=${ref.root.x()},${ref.root.y()},${ref.root.z()}"
                        )*/
                        curCell = ref.root
                    }

                    if (ref.target == null) {
                        val next = chooseNeighbor(ref, curCell)
                        /*if (doLog) Bukkit.getLogger().info(
                            "[FishTankTick] k=$k chooseNeighbor from=${curCell.x()},${curCell.y()},${curCell.z()} -> " +
                                    (next?.let { "${it.x()},${it.y()},${it.z()}" } ?: "NULL")
                        )*/
                        ref.target = next ?: curCell
                        ref.lastCell = curCell
                        ref.targetOffset = newOffset()
                    }

                    for (ent in alive) {
                        val nav = navByEntity.computeIfAbsent(ent.uniqueId) { Nav() }

                        val loc = ent.location
                        var curCell = BlockPos(loc.blockX, loc.blockY, loc.blockZ)

                        if (!ref.cellSet.contains(pack(curCell))) curCell = ref.root

                        if (nav.target == null) {
                            nav.target = chooseNeighbor(ref, curCell) ?: curCell
                            nav.last = curCell
                            nav.off = newOffset()
                        }

                        val target = nav.target!!
                        val off = nav.off!!

                        val tx = target.x() + 0.5 + off.first
                        val ty = target.y() + 0.5 + off.second
                        val tz = target.z() + 0.5 + off.third

                        val dx = tx - loc.x
                        val dy = ty - loc.y
                        val dz = tz - loc.z
                        val dist2 = dx*dx + dy*dy + dz*dz

                        if (dist2 < 0.0025) {
                            val next = chooseNeighbor(ref, target) ?: target
                            nav.last = target
                            nav.target = next
                            nav.off = newOffset()
                            continue
                        }

                        val dist = kotlin.math.sqrt(dist2)
                        val step = 0.08
                        val nx = dx / dist
                        val ny = dy / dist
                        val nz = dz / dist

                        val yaw = Math.toDegrees(kotlin.math.atan2(-nx, nz)).toFloat()
                        ent.teleport(Location(bw, loc.x + nx*step, loc.y + ny*step, loc.z + nz*step, yaw, 0f))
                    }
                }
            }, 1L, 1L)
        }

        data class TankRef(
            val worldName: String,
            var root: BlockPos,
            var fish: FishType,

            var expectedId: String = "elitefantasy:aquarium_block",

            var cells: List<BlockPos> = emptyList(),
            var cellSet: HashSet<Long> = hashSetOf(),
            var target: BlockPos? = null,
            var lastCell: BlockPos? = null,
            var targetOffset: Triple<Double, Double, Double>? = null,
            var rescanAt: Long = 0L,

            var dbgTick: Int = 0,
            var dbgLastLog: Long = 0L,

            var forceRescanCooldownUntil: Long = 0L,
            var bucketSnapshot: ItemStack? = null,
            var brokenLogCooldownUntil: Long = 0L
            )

        private val tanks = ConcurrentHashMap<String, TankRef>()

        private fun getIdSet(tankKey: String): MutableSet<UUID> =
            fishEntities.computeIfAbsent(tankKey) { ConcurrentHashMap.newKeySet() }

        private fun isDisplayFish(e: LivingEntity): Boolean {
            val isFishType =
                e is Cod || e is Salmon || e is TropicalFish || e is PufferFish || e is Axolotl || e is Tadpole
            if (!isFishType) return false
            // Preferable: ONLY those with tags (to avoid deleting real pets)
            if (e.scoreboardTags.contains(TAG_FISHTANK)) return true

            // fallback for compatibility with old entities (before tag): extremely “your entity”
            return e.isInvulnerable && e.isSilent && !e.hasGravity() && !e.isCollidable
        }

        private fun cleanupTankEntities(bw: org.bukkit.World, center: Location, tankKey: String, keep: UUID? = null) {
            val legacyR2 = 1.6 * 1.6 // only legacy very close to the center

            for (near in bw.getNearbyEntities(center, 6.0, 6.0, 6.0)) { // 20 es demasiado
                val le = near as? LivingEntity ?: continue
                if (!isDisplayFish(le)) continue

                val k = readTankKey(le)
                val remove =
                    (k == tankKey && (keep == null || le.uniqueId != keep)) ||
                            (k == null && le.location.distanceSquared(center) <= legacyR2)

                if (remove) le.remove()
            }
        }

        private fun fishTypeOfEntity(e: LivingEntity): FishType = when (e) {
            is Axolotl -> FishType.axolotl
            is Cod -> FishType.cod
            is Salmon -> FishType.salmon
            is TropicalFish -> FishType.tropical_fish
            is PufferFish -> FishType.pufferfish
            is Tadpole -> FishType.tadpole
            else -> FishType.none
        }

        private fun buildDesiredCount(
            bw: org.bukkit.World,
            cells: List<BlockPos>
        ): EnumMap<FishType, Int> {
            val desired = EnumMap<FishType, Int>(FishType::class.java)

            for (c in cells) {
                val b = bw.getBlockAt(c.x(), c.y(), c.z())
                val st = CraftEngineBlocks.getCustomBlockState(b.blockData) ?: continue
                val owner = st.owner().value() ?: continue
                val propAny = owner.getProperty("fish") ?: continue
                @Suppress("UNCHECKED_CAST")
                val prop = propAny as Property<FishType>

                val f = st.get(prop, FishType.none)
                if (f != FishType.none) desired[f] = (desired[f] ?: 0) + 1
            }

            return desired
        }

        private fun purgeTankOrphans(
            bw: org.bukkit.World,
            center: Location,
            radius: Double,
            tankKey: String,
            keep: Set<UUID>
        ) {
            for (near in bw.getNearbyEntities(center, radius, radius, radius)) {
                val le = near as? LivingEntity ?: continue
                if (!isDisplayFish(le)) continue
                if (readTankKey(le) != tankKey) continue
                if (!keep.contains(le.uniqueId)) {
                    le.remove()
                }
            }
        }

        fun syncFishDisplay(world: World, pos: BlockPos, fish: FishType, bucketSnapshot: ItemStack? = null, forceRescan: Boolean = false) {
            val bw = Bukkit.getWorld(world.name()) ?: return

            val baseLoc = Location(bw, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5)

            val rootBlock = bw.getBlockAt(pos.x(), pos.y(), pos.z())
            val rootState = CraftEngineBlocks.getCustomBlockState(rootBlock.blockData)
            val expectedId = if (rootState != null) ownerIdString(rootState) else "elitefantasy:aquarium_block"

            var tankKey = resolveTankKey(bw, world, pos, expectedId)
            val ref = tanks[tankKey] ?: TankRef(world.name(), pos, fish)

            if (fish == FishType.none) {
                FishTankDataStore.removeCellSnapshot(tankKey, pack(pos))
            } else if (bucketSnapshot != null) {
                writeBucketSnapshot(tankKey, pos, fish, bucketSnapshot)
            }

            if (forceRescan) ref.rescanAt = 0L

            //Bukkit.getLogger().info("[FishTank] syncFishDisplay ENTER k=$k fish=$fish bucketSnapshot=${bucketSnapshot?.type}")

            // TankRef base
            val now = System.currentTimeMillis()
            ref.fish = fish

            // if a bucketSnapshot arrives, save it to apply meta to new spawns
            if (bucketSnapshot != null) {
                ref.bucketSnapshot = bucketSnapshot.clone().also { it.amount = 1 }
            }

            // rescan each 3s or if empty
            if (ref.cells.isEmpty() || now >= ref.rescanAt) {
                val rootBlock = bw.getBlockAt(pos.x(), pos.y(), pos.z())
                val rootState = CraftEngineBlocks.getCustomBlockState(rootBlock.blockData)

                val expectedId = if (rootState != null) ownerIdString(rootState) else "elitefantasy:aquarium_block"
                ref.expectedId = expectedId

                debugNeighborsOnce(bw, pos, expectedId)

                if (ref.cells.isNotEmpty()) {
                    uncacheTankCells(ref.worldName, tankKey, ref.cells)
                }

                val scanned = scanConnectedBukkit(bw, pos, expectedId, 16, 512)
                ref.cells = if (scanned.isNotEmpty()) scanned else listOf(pos) // fallback 1 cell
                ref.root = canonicalRoot(ref.cells)

                val newKey = key(ref.worldName, ref.root)
                if (newKey != tankKey) {
                    FishTankDataStore.migrateKey(tankKey, newKey)

                    fishEntities.remove(tankKey)?.let { set -> fishEntities[newKey] = set }
                    tanks.remove(tankKey)?.let { tr -> tanks[newKey] = tr }

                    // actualizar PDC de las entidades existentes del tanque
                    fishEntities[newKey]?.let { set ->
                        for (id in set) {
                            val ent = bw.getEntity(id) as? LivingEntity ?: continue
                            markAsTankEntity(ent, newKey)
                        }
                    }

                    tankKey = newKey
                }

                ref.cellSet = HashSet(ref.cells.size * 2)
                for (c in ref.cells) ref.cellSet.add(pack(c))

                adoptOrPurgeEntities(bw, ref, tankKey)

                // Build desiredCount by type, based on ALL cells in the tank
                val desiredCount = buildDesiredCount(bw, ref.cells)

                // If there are no fish in any block, clear EVERYTHING from the tank and exit.
                if (desiredCount.isEmpty()) {
                    val center = Location(bw, ref.root.x() + 0.5, ref.root.y() + 0.5, ref.root.z() + 0.5)

                    val ids0 = fishEntities.remove(tankKey)
                    if (ids0 != null) {
                        for (id in ids0) bw.getEntity(id)?.remove()
                    }
                    cleanupTankEntities(bw, center, tankKey, null)

                    if (ref.cells.isNotEmpty()) uncacheTankCells(ref.worldName, tankKey, ref.cells)
                    tanks.remove(tankKey)
                    FishTankDataStore.removeTank(tankKey)
                    return
                }

                // Global limit (optional)
                val maxFish = 20
                var totalWant = 0
                for ((_, v) in desiredCount) totalWant += v
                if (totalWant > maxFish) {
                    // Cut it out simply: cut by iteration (if you want something more professional, we'll do that later).
                    var remaining = maxFish
                    val it = desiredCount.entries.iterator()
                    while (it.hasNext()) {
                        val e = it.next()
                        if (remaining <= 0) { it.remove(); continue }
                        val take = minOf(e.value, remaining)
                        if (take <= 0) it.remove() else e.setValue(take)
                        remaining -= take
                    }
                }

                // Current tank ID set
                val ids = getIdSet(tankKey)

                // 1 cleans dead IDs
                run {
                    val it = ids.iterator()
                    while (it.hasNext()) {
                        val id = it.next()
                        val e = bw.getEntity(id)
                        if (e == null || e.isDead) it.remove()
                    }
                }

                val keep = HashSet<UUID>()

                // 2 groups current ones by type
                val currentByType = EnumMap<FishType, MutableList<UUID>>(FishType::class.java)
                for (id in ids) {
                    val e = bw.getEntity(id) as? LivingEntity ?: continue
                    val t = fishTypeOfEntity(e)
                    if (t == FishType.none) continue
                    currentByType.computeIfAbsent(t) { mutableListOf() }.add(id)
                }

                // 3 DELETE EXTRAS by type
                for ((type, list) in currentByType) {
                    val want = desiredCount[type] ?: 0
                    while (list.size > want) {
                        val id = list.removeAt(list.size - 1)
                        ids.remove(id)
                        bw.getEntity(id)?.remove()
                    }
                }

                val desiredCellsByType = EnumMap<FishType, MutableList<BlockPos>>(FishType::class.java)

                for ((type, cellList) in desiredCellsByType) {
                    val want = desiredCount[type] ?: 0
                    if (cellList.size > want) {
                        // deja solo las primeras 'want' (o random si prefieres)
                        while (cellList.size > want) cellList.removeAt(cellList.size - 1)
                    }
                }

                for (c in ref.cells) {
                    val b = bw.getBlockAt(c.x(), c.y(), c.z())
                    val st = CraftEngineBlocks.getCustomBlockState(b.blockData) ?: continue
                    val owner = st.owner().value() ?: continue
                    val propAny = owner.getProperty("fish") ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val prop = propAny as Property<FishType>
                    val fish = st.get(prop, FishType.none)

                    val packedCell = pack(c)

                    if (fish == FishType.none) {
                        // if there is a linked entity in YML, kill it
                        val oldUuid = FishTankDataStore.getCellEntityUuid(tankKey, packedCell)
                        if (oldUuid != null) {
                            bw.getEntity(oldUuid)?.remove()
                            ids.remove(oldUuid)
                            navByEntity.remove(oldUuid)
                        }
                        // delete snapshot of that cell
                        FishTankDataStore.removeCellSnapshot(tankKey, packedCell)
                        continue
                    }

                    // fish != none: linked entity must exist
                    val snap = FishTankDataStore.getCellSnapshot(tankKey, packedCell)
                    // if the snapshot shows a different fish than the current state, invalidate old uuid
                    if (snap != null && snap.fishType != fish.name) {
                        val wrong = FishTankDataStore.getCellEntityUuid(tankKey, packedCell)
                        if (wrong != null) {
                            bw.getEntity(wrong)?.remove()
                            ids.remove(wrong)
                            navByEntity.remove(wrong)
                        }
                        snap.fishType = fish.name
                        snap.entityUuid = null
                    }

                    // 1) Attempts to reuse the snapshot UUID.
                    val linkedUuid = FishTankDataStore.getCellEntityUuid(tankKey, packedCell)
                    var ent = linkedUuid?.let { bw.getEntity(it) as? LivingEntity }
                        ?.takeIf { !it.isDead && isDisplayFish(it) }

                    // 2) if it does not exist, spawn it
                    if (ent == null) {
                        val loc = Location(bw, c.x() + 0.5, c.y() + 0.5, c.z() + 0.5)
                        ent = spawnFishEntity(bw, loc, fish) ?: continue
                        markAsTankEntity(ent, tankKey)
                        ids.add(ent.uniqueId)

                        // save link cell → entity
                        FishTankDataStore.setCellEntityUuid(tankKey, packedCell, ent.uniqueId)
                    } else {
                        // adopts and secures tag/pdc
                        markAsTankEntity(ent, tankKey)
                        ids.add(ent.uniqueId)
                    }

                    // apply target from bucketB64 if it exists64 if it exists
                    val snap2 = FishTankDataStore.getCellSnapshot(tankKey, packedCell)
                    val b64 = snap2?.bucketB64
                    val snapItem = b64?.let { deserializeItem(it) }?.takeIf { it.type == bucketForFish(fish) }
                    if (snapItem != null) {
                        runCatching { applyBucketMetaToEntity(ent, snapItem) }
                    }

                    keep.add(ent.uniqueId)
                }

                val it2 = ids.iterator()
                while (it2.hasNext()) {
                    val id = it2.next()
                    if (!keep.contains(id)) {
                        bw.getEntity(id)?.remove()
                        navByEntity.remove(id)
                        it2.remove()
                    }
                }

                // 4 MISSING SPAWNS per type
                for ((type, cellList) in desiredCellsByType) {
                    val want = cellList.size
                    val listIds = currentByType[type] ?: mutableListOf()

                    // 1) delete extras if there are any left over
                    while (listIds.size > want) {
                        val id = listIds.removeAt(listIds.size - 1)
                        ids.remove(id)
                        bw.getEntity(id)?.remove()
                    }

                    // 2) prepare desired snapshots (one per cell)
                    val snaps = cellList.map { cell ->
                        readBucketSnapshot(tankKey, pack(cell), type)?.takeIf { it.type == bucketForFish(type) }
                    }

                    // 3) apply goal to existing ones
                    for (i in 0 until minOf(listIds.size, want)) {
                        val ent = bw.getEntity(listIds[i]) as? LivingEntity ?: continue
                        val snapItem = snaps[i]
                        if (snapItem != null) {
                            try { applyBucketMetaToEntity(ent, snapItem) } catch (_: Throwable) {}
                        }
                    }

                    // 4) spawnear missing and apply its snapshot
                    var missing = want - listIds.size
                    var idx = listIds.size
                    while (missing > 0) {
                        val ent = spawnFishEntity(bw, baseLoc, type) ?: break
                        markAsTankEntity(ent, tankKey)

                        val snapItem = snaps.getOrNull(idx)
                        if (snapItem != null) {
                            try { applyBucketMetaToEntity(ent, snapItem) } catch (_: Throwable) {}
                        }

                        ids.add(ent.uniqueId)
                        listIds.add(ent.uniqueId)
                        missing--
                        idx++
                    }

                    currentByType[type] = listIds
                }

                cacheTankCells(ref.worldName, tankKey, ref.cells)
                val center = tankCenter(ref, bw)
                val radius = tankRadius(ref)
                purgeTankOrphans(bw, center, radius, tankKey, ids.toSet())

                ref.target = null
                ref.lastCell = null
                ref.targetOffset = null
                ref.rescanAt = now + 3000L

                //Bukkit.getLogger().info("[FishTank] scan cells=${ref.cells.size} expectedId=$expectedId")
            }

            /*Bukkit.getLogger().info(
                "[FishTank] sync k=$k fish=$fish root=${pos.x()},${pos.y()},${pos.z()} " +
                        "cells=${ref.cells.size} set=${ref.cellSet.size} ent=${fishEntities[k]}"
            )
            if (ref.cells.size <= 3) {
                Bukkit.getLogger().info("[FishTank] cells(sample)=${ref.cells.joinToString { "${it.x()},${it.y()},${it.z()}" }}")
            }*/

            tanks[tankKey] = ref
        }

        val PDC_TANK_KEY_PUBLIC by lazy { NamespacedKey(UnearthMechanic.getInstance(), "fishtank_key") }

        fun fishTypeOfEntityPublic(e: LivingEntity): FishType = fishTypeOfEntity(e)

        fun removeOneFishFromTankAndResync(bw: org.bukkit.World, tankKey: String, fish: FishType) {
            val ref = tanks[tankKey] ?: return

            // Search for a cell that actually has that fish in the block-state
            val targetCell = ref.cells.firstOrNull { c ->
                val b = bw.getBlockAt(c.x(), c.y(), c.z())
                val st = CraftEngineBlocks.getCustomBlockState(b.blockData) ?: return@firstOrNull false
                val owner = st.owner().value() ?: return@firstOrNull false
                val propAny = owner.getProperty("fish") ?: return@firstOrNull false
                @Suppress("UNCHECKED_CAST")
                val prop = propAny as Property<FishType>
                st.get(prop, FishType.none) == fish
            } ?: return

            // Set fish = none in that cell
            // ---- EXAMPLE/PLACEHOLDER ----
            // setFishProperty(bw, targetCell, FishType.none)
            // -----------------------------

            // Resync (this deletes entities/YML if it is empty)
            val ceWorld = BukkitAdaptor.adapt(bw)
            ensureTaskRunning()
            syncFishDisplay(ceWorld, targetCell, FishType.none)
        }

        private fun tankCenter(ref: TankRef, bw: org.bukkit.World): Location {
            return Location(bw, ref.root.x() + 0.5, ref.root.y() + 0.5, ref.root.z() + 0.5)
        }

        private fun tankRadius(ref: TankRef): Double {
            // maximum distance from root to any cell, + margin
            var maxD2 = 0.0
            val rx = ref.root.x()
            val ry = ref.root.y()
            val rz = ref.root.z()

            for (c in ref.cells) {
                val dx = (c.x() - rx).toDouble()
                val dy = (c.y() - ry).toDouble()
                val dz = (c.z() - rz).toDouble()
                val d2 = dx*dx + dy*dy + dz*dz
                if (d2 > maxD2) maxD2 = d2
            }

            val r = sqrt(maxD2) + 3.0
            return r.coerceAtMost(32.0) // cap to avoid killing performance
        }

        private fun debugNeighborsOnce(bw: org.bukkit.World, root: BlockPos, expectedId: String) {
            val sb = StringBuilder()
            sb.append("[FishTankDbg] root=${root.x()},${root.y()},${root.z()} expectedId=$expectedId\n")

            for (d in DIRS) {
                val np = BlockPos(root.x() + d[0], root.y() + d[1], root.z() + d[2])
                val b = bw.getBlockAt(np.x(), np.y(), np.z())

                val opt = BlockStateUtils.getOptionalCustomBlockState(b)
                if (opt == null) {
                    sb.append("  neighbor ${np.x()},${np.y()},${np.z()} vanilla=${b.type} customState=NULL\n")
                    continue
                }
                if (opt.isEmpty) {
                    sb.append("  neighbor ${np.x()},${np.y()},${np.z()} vanilla=${b.type} customState=EMPTY\n")
                    continue
                }

                val st = try { opt.get() } catch (t: Throwable) {
                    sb.append("  neighbor ${np.x()},${np.y()},${np.z()} vanilla=${b.type} customState=THROW ${t.javaClass.simpleName}\n")
                    continue
                }

                val id = ownerIdString(st)
                sb.append("  neighbor ${np.x()},${np.y()},${np.z()} vanilla=${b.type} id=$id match=${id == expectedId}\n")
            }

            //Bukkit.getLogger().info(sb.toString())
        }

        private fun spawnFishEntity(bukkitWorld: org.bukkit.World, loc: Location, fish: FishType): LivingEntity? {
            return when (fish) {
                FishType.axolotl -> bukkitWorld.spawn(loc, Axolotl::class.java) { e ->
                    setupEntity(e)
                }
                FishType.cod -> bukkitWorld.spawn(loc, Cod::class.java) { e ->
                    setupEntity(e)
                }
                FishType.salmon -> bukkitWorld.spawn(loc, Salmon::class.java) { e ->
                    setupEntity(e)
                }
                FishType.tropical_fish -> bukkitWorld.spawn(loc, TropicalFish::class.java) { e ->
                    setupEntity(e)
                }
                FishType.pufferfish -> bukkitWorld.spawn(loc, PufferFish::class.java) { e ->
                    setupEntity(e)
                }
                FishType.tadpole -> bukkitWorld.spawn(loc, Tadpole::class.java) { e ->
                    setupEntity(e)
                }
                FishType.none -> null
            }
        }

        private fun setupEntity(e: LivingEntity) {
            e.isSilent = true
            e.isInvulnerable = true
            e.isCollidable = false
            e.setGravity(false)

            if (e is Mob) {
                e.isAggressive = false
                e.removeWhenFarAway = false
                e.setPersistent(true)
                //e.setAI(false)
            }
        }

        private val DIRS = arrayOf(
            intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0),
            intArrayOf(0, 1, 0), intArrayOf(0, -1, 0),
            intArrayOf(0, 0, 1), intArrayOf(0, 0, -1)
        )
        fun ownerIdString(state: ImmutableBlockState): String {
            val ref = state.owner() as? Holder.Reference<CustomBlock>
            if (ref != null) return ref.key().location().asString()
            return state.toString().substringBefore('[')
        }

        private fun scanConnectedBukkit(
            bw: org.bukkit.World,
            root: BlockPos,
            expectedId: String,
            radius: Int = 16,
            limit: Int = 512
        ): List<BlockPos> {

            val r2 = radius * radius
            fun inRange(p: BlockPos): Boolean {
                val dx = p.x() - root.x()
                val dy = p.y() - root.y()
                val dz = p.z() - root.z()
                return dx*dx + dy*dy + dz*dz <= r2
            }

            fun visitPack(p: BlockPos): Long {
                val x = (p.x().toLong() and 0x3FFFFF)
                val y = (p.y().toLong() and 0xFFFFF)
                val z = (p.z().toLong() and 0x3FFFFF)
                return (x shl 42) or (z shl 20) or y
            }

            var checked = 0
            var match = 0
            var mismatch = 0
            var nullState = 0

            val out = ArrayList<BlockPos>(64)
            val q = ArrayDeque<BlockPos>()
            val seen = HashSet<Long>(512)

            q.add(root)
            seen.add(visitPack(root))

            while (q.isNotEmpty() && out.size < limit) {
                val p = q.removeFirst()
                if (!inRange(p)) continue
                checked++

                val b = bw.getBlockAt(p.x(), p.y(), p.z())

                val st = CraftEngineBlocks.getCustomBlockState(b.blockData)
                if (st == null) {
                    nullState++
                    continue
                }

                val idString = ownerIdString(st)
                if (idString != expectedId) {
                    mismatch++
                    continue
                }

                match++
                out.add(p)

                for (d in DIRS) {
                    val np = BlockPos(p.x() + d[0], p.y() + d[1], p.z() + d[2])
                    val pk = visitPack(np)
                    if (seen.add(pk)) q.add(np)
                }
            }

            /*Bukkit.getLogger().info(
                "[FishTankBFS] root=${root.x()},${root.y()},${root.z()} expectedId=$expectedId " +
                        "checked=$checked match=$match mismatch=$mismatch nullState=$nullState out=${out.size}"
            )*/

            return out
        }

        private fun pack(p: BlockPos): Long {
            val x = p.x().toLong() and 0x3FFFFFF  // 26 bits
            val y = p.y().toLong() and 0xFFF      // 12 bits
            val z = p.z().toLong() and 0x3FFFFFF
            return (x shl 38) or (z shl 12) or y
        }

        fun packPos(pos: BlockPos): Long = pack(pos)

        private fun chooseNeighbor(ref: TankRef, from: BlockPos): BlockPos? {
            val options = ArrayList<BlockPos>(6)
            for (d in DIRS) {
                val np = BlockPos(from.x() + d[0], from.y() + d[1], from.z() + d[2])
                if (ref.cellSet.contains(pack(np))) {
                    // valid in the real worldx
                    val bw = Bukkit.getWorld(ref.worldName)
                    if (bw != null && isAquariumCell(bw, np, ref.expectedId)) {
                        options.add(np)
                    }
                }
            }

            if (options.isEmpty()) {
                if (ref.dbgTick % 40 == 0) {
                    /*Bukkit.getLogger().warning(
                        "[FishTankNeighbor] from=${from.x()},${from.y()},${from.z()} -> NO OPTIONS (cellSet=${ref.cellSet.size})"
                    )*/
                }
                return null
            }

            val last = ref.lastCell
            if (last != null && options.size > 1) {
                options.removeIf { it.x() == last.x() && it.y() == last.y() && it.z() == last.z() }
                if (options.isEmpty()) return last
            }

            return options.random()
        }

        private fun newOffset(): Triple<Double, Double, Double> {
            val r = ThreadLocalRandom.current()
            val ox = r.nextDouble(-0.25, 0.25)
            val oy = r.nextDouble(-0.15, 0.15)
            val oz = r.nextDouble(-0.25, 0.25)
            return Triple(ox, oy, oz)
        }

        fun resyncAllLoadedAquariums() {
            for (bw in Bukkit.getWorlds()) {
                for (chunk in bw.loadedChunks) {
                    val bx = chunk.x shl 4
                    val bz = chunk.z shl 4
                    for (x in 0..15) for (z in 0..15) {
                        for (y in bw.minHeight until bw.maxHeight) {
                            val b = bw.getBlockAt(bx + x, y, bz + z)
                            val opt = BlockStateUtils.getOptionalCustomBlockState(b) ?: continue
                            if (opt.isEmpty) continue
                            val st = opt.get()

                            // filter only aquarium block
                            if (st.toString().substringBefore('[') != "elitefantasy:aquarium_block") continue

                            val owner = st.owner().value() ?: continue
                            val fishPropAny = owner.getProperty("fish") ?: continue
                            @Suppress("UNCHECKED_CAST")
                            val fishProp = fishPropAny as Property<FishType>
                            val fish = st.get(fishProp, FishType.none)
                            if (fish == FishType.none) continue

                            val ceWorld = BukkitAdaptor.adapt(bw)
                            ensureTaskRunning()
                            syncFishDisplay(ceWorld, BlockPos(b.x, b.y, b.z), fish)
                        }
                    }
                }
            }
        }

        private fun isAquariumCell(bw: org.bukkit.World, p: BlockPos, expectedId: String): Boolean {
            val b = bw.getBlockAt(p.x(), p.y(), p.z())
            val st = CraftEngineBlocks.getCustomBlockState(b.blockData) ?: return false
            val id = ownerIdString(st)
            return id == expectedId
        }

        fun resyncChunkAquariums(bw: org.bukkit.World, chunk: Chunk) {
            val bx = chunk.x shl 4
            val bz = chunk.z shl 4

            for (x in 0..15) for (z in 0..15) {
                for (y in bw.minHeight until bw.maxHeight) {
                    val b = bw.getBlockAt(bx + x, y, bz + z)

                    val st = CraftEngineBlocks.getCustomBlockState(b.blockData) ?: continue
                    if (st.toString().substringBefore('[') != "elitefantasy:aquarium_block") continue

                    val owner = st.owner().value() ?: continue
                    val fishPropAny = owner.getProperty("fish") ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val fishProp = fishPropAny as Property<FishType>

                    val fish = st.get(fishProp, FishType.none)
                    if (fish == FishType.none) continue

                    val ceWorld = BukkitAdaptor.adapt(bw)
                    syncFishDisplay(ceWorld, BlockPos(b.x, b.y, b.z), fish)
                }
            }
        }

        fun bucketToGive(bw: org.bukkit.World, tankKey: String, cellPos: BlockPos, fish: FishType): ItemStack {
            val expected = bucketForFish(fish) ?: Material.BUCKET

            // Preferred: snapshot saved in YML by type
            val snap = readBucketSnapshot(tankKey, pack(cellPos), fish)
            if (snap != null && snap.type == expected) {
                snap.amount = 1
                return snap
            }

            // Fallback: create normal bucket (if there is no snapshot)
            val out = ItemStack(expected).also { it.amount = 1 }

            // (Recommended optional) If it is axolotl/tropical and there was NO snapshot,
            // try to copy the goal of some living entity from the tank:
            if (fish == FishType.axolotl || fish == FishType.tropical_fish) {
                val ids = fishEntities[tankKey]
                val ent = ids?.asSequence()
                    ?.mapNotNull { bw.getEntity(it) as? LivingEntity }
                    ?.firstOrNull { fishTypeOfEntity(it) == fish }

                if (ent != null) {
                    when (fish) {
                        FishType.axolotl -> (ent as? Axolotl)?.let { ax ->
                            (out.itemMeta as? AxolotlBucketMeta)?.let { meta ->
                                meta.variant = ax.variant
                                out.itemMeta = meta
                            }
                        }
                        FishType.tropical_fish -> (ent as? TropicalFish)?.let { tf ->
                            (out.itemMeta as? TropicalFishBucketMeta)?.let { meta ->
                                meta.pattern = tf.pattern
                                meta.bodyColor = tf.bodyColor
                                meta.patternColor = tf.patternColor
                                out.itemMeta = meta
                            }
                        }
                        else -> {}
                    }
                }
            }

            return out
        }

        private fun applyBucketMetaToEntity(ent: LivingEntity, bucket: ItemStack?) {
            val meta = bucket?.itemMeta ?: return

            if (ent is Axolotl) {
                val ax = meta as? AxolotlBucketMeta
                if (ax != null) {
                    try {
                        if (ax.hasVariant()) {
                            ent.variant = ax.variant
                        }
                    } catch (_: Throwable) {
                    }
                }
            }

            if (ent is TropicalFish) {
                val tf = meta as? TropicalFishBucketMeta
                if (tf != null) {
                    try {
                        ent.pattern = tf.pattern
                        ent.bodyColor = tf.bodyColor
                        ent.patternColor = tf.patternColor
                    } catch (_: Throwable) { }
                }
            }
        }

        private fun canonicalRoot(cells: List<BlockPos>): BlockPos {
            // stable: first Y, then X, then Z
            return cells.minWith(compareBy<BlockPos>({ it.y() }, { it.x() }, { it.z() }))
        }



        fun resolveTankKey(bw: org.bukkit.World, ceWorld: World, clicked: BlockPos, expectedId: String): String {
            val worldName = bw.name
            val center = Location(bw, clicked.x() + 0.5, clicked.y() + 0.5, clicked.z() + 0.5)

            // Cache per cell -> tankKey
            val cached = cellToTankKey[worldName]?.get(pack(clicked))
            if (cached != null && isCachedKeyValid(worldName, clicked, cached, expectedId)) {
                return cached
            }

            // BFS ONLY if there was no cache/fallback
            val cells = scanConnectedBukkit(bw, clicked, expectedId, 16, 512)
            val root = if (cells.isNotEmpty()) canonicalRoot(cells) else clicked
            val k = key(ceWorld, root)

            // Cache all cells for future clicks
            if (cells.isNotEmpty()) cacheTankCells(worldName, k, cells) else worldCellMap(worldName)[pack(clicked)] = k

            return k
        }

        private val suppress = ConcurrentHashMap<String, Long>() // key=world:x,y,z  value=expireMs

        public fun suppressPos(world: World, pos: BlockPos, ms: Long = 150L) {
            suppress["${world.name()}:${pos.x()},${pos.y()},${pos.z()}"] = System.currentTimeMillis() + ms
        }

        private fun isSuppressed(world: World, pos: BlockPos): Boolean {
            val k = "${world.name()}:${pos.x()},${pos.y()},${pos.z()}"
            val until = suppress[k] ?: return false
            if (System.currentTimeMillis() > until) {
                suppress.remove(k)
                return false
            }
            return true
        }
    }

    // Hook that is definitely present in your behaviors: updateShape.
    // Every time something changes around you, resync.
    override fun updateShape(thisBlock: Any, args: Array<Any>, superMethod: Callable<Any>): Any {
        val world = args.getOrNull(3) as? World ?: return superMethod.call()
        val pos   = args.getOrNull(4) as? BlockPos ?: return superMethod.call()

        if (isSuppressed(world, pos)) return superMethod.call()

        val opt = BlockStateUtils.getOptionalCustomBlockState(args[0]) ?: return superMethod.call()
        if (opt.isEmpty) return superMethod.call()
        val state = opt.get()
        val fish = try { state.get(fishProperty) } catch (_: Throwable) { FishType.none }

        // in case there was trash with the local
        ensureTaskRunning()
        syncFishDisplay(world, pos, fish)
        return superMethod.call()
    }

    override fun onPlace(thisBlock: Any, args: Array<Any>, superMethod: Callable<Any>) {
        superMethod.call()
    }

    /*override fun onRemove(thisBlock: Any, args: Array<Any>, superMethod: Callable<Any>) {
        handleRemoval(thisBlock, args,superMethod);
    }*/

    override fun affectNeighborsAfterRemoval(thisBlock: Any, args: Array<Any>, superMethod: Callable<Any>) {
        handleRemoval(thisBlock, args,superMethod);
    }

    private fun handleRemoval(thisBlock: Any, args: Array<Any>, superMethod: Callable<Any>) {
        try {
            val nmsWorld = args.getOrNull(1)
            val nmsPos = args.getOrNull(2)

            if (nmsWorld == null || nmsPos == null) {
                superMethod.call()
                return
            }

            val craftWorld = Bukkit.getWorlds().firstOrNull { w ->
                val handle = w.javaClass.getMethod("getHandle").invoke(w)
                handle == nmsWorld
            }

            if(craftWorld != null){
                val x = nmsPos.javaClass.getMethod("getX").invoke(nmsPos) as Int
                val y = nmsPos.javaClass.getMethod("getY").invoke(nmsPos) as Int
                val z = nmsPos.javaClass.getMethod("getZ").invoke(nmsPos) as Int

                val bw = craftWorld
                val ceWorld = BukkitAdaptor.adapt(bw)
                val removed = BlockPos(x, y, z)
                val packedCell = pack(removed)
                val worldName = bw.name

                val tkFromMem = tanks.entries
                    .firstOrNull { (_, ref) -> ref.worldName == worldName && ref.cellSet.contains(packedCell) }
                    ?.key

                val tankKey =
                    cellToTankKey[worldName]?.get(packedCell)
                        ?: tkFromMem
                        ?: FishTankDataStore.findTankKeyByCell(worldName, packedCell)
                        ?: key(ceWorld, removed) // last fallback

                // 2) kill entity linked to that cell
                val uuid = FishTankDataStore.getCellEntityUuid(tankKey, packedCell)
                if (uuid != null) {
                    bw.getEntity(uuid)?.remove()
                    fishEntities[tankKey]?.remove(uuid)
                    navByEntity.remove(uuid)
                }

                // 3) delete snapshot of that cell
                FishTankDataStore.removeCellSnapshot(tankKey, packedCell)

                // 4) clear cache cell->tankKey from that cell
                cellToTankKey[worldName]?.remove(packedCell)

                // 5) “Quick resync”: from neighbors who remain aquarium
                Bukkit.getScheduler().runTask(UnearthMechanic.getInstance(), Runnable {
                    val seen = HashSet<String>()
                    var didResync = false

                    for (d in DIRS) {
                        val np = BlockPos(removed.x() + d[0], removed.y() + d[1], removed.z() + d[2])

                        val st = CraftEngineBlocks.getCustomBlockState(bw.getBlockAt(np.x(), np.y(), np.z()).blockData) ?: continue
                        val owner = st.owner().value() ?: continue
                        val propAny = owner.getProperty("fish") ?: continue
                        @Suppress("UNCHECKED_CAST") val prop = propAny as Property<FishType>
                        val fish = st.get(prop, FishType.none)

                        val expectedId = ownerIdString(st)
                        val tk = resolveTankKey(bw, ceWorld, np, expectedId)
                        if (seen.add(tk)) {
                            didResync = true
                            ensureTaskRunning()
                            syncFishDisplay(ceWorld, np, fish, forceRescan = true)
                        }
                    }

                    // If there are no aquarium neighbors, it was the last block: delete everything by tankKey
                    if (!didResync) {
                        val center = Location(bw, removed.x() + 0.5, removed.y() + 0.5, removed.z() + 0.5)

                        fishEntities.remove(tankKey)?.forEach { id ->
                            bw.getEntity(id)?.remove()
                            navByEntity.remove(id)
                        }

                        cleanupTankEntities(bw, center, tankKey, null)

                        tanks.remove(tankKey)
                        FishTankDataStore.removeTank(tankKey)
                        cellToTankKey[worldName]?.entries?.removeIf { it.value == tankKey }
                    }
                })

                val center = Location(bw, removed.x() + 0.5, removed.y() + 0.5, removed.z() + 0.5)
                for (near in bw.getNearbyEntities(center, 1.2, 1.2, 1.2)) {
                    val le = near as? LivingEntity ?: continue
                    if (!isDisplayFish(le)) continue
                    val k2 = readTankKey(le)
                    if (k2 == null || k2 == tankKey) {
                        le.remove()
                        fishEntities[tankKey]?.remove(le.uniqueId)
                        navByEntity.remove(le.uniqueId)
                    }
                }

                /*

                cellToTankKey[worldName]?.remove(packed)

                val ref = tanks.remove(realKey)
                if (ref != null && ref.cells.isNotEmpty()) {
                    uncacheTankCells(ref.worldName, realKey, ref.cells)
                }

                cleanupTankEntities(
                    craftWorld,
                    Location(craftWorld, x + 0.5, y + 0.5, z + 0.5),
                    realKey,
                    null
                )

                val ids = fishEntities.remove(realKey)
                if (ids != null) {
                    for (id in ids) {
                        craftWorld.getEntity(id)?.remove()
                    }
                }

                FishTankDataStore.removeTank(realKey)
                if (kLocal != realKey) {
                    FishTankDataStore.removeTank(kLocal)

                    cleanupTankEntities(
                        craftWorld,
                        Location(craftWorld, x + 0.5, y + 0.5, z + 0.5),
                        kLocal,
                        null
                    )

                    val idsLocal = fishEntities.remove(kLocal)
                    if (idsLocal != null) {
                        for (id in idsLocal) {
                            craftWorld.getEntity(id)?.remove()
                        }
                    }
                    tanks.remove(kLocal)
                }*/
            }
        } catch (_: Throwable) { }
        superMethod.call()
    }
}
