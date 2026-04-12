package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.fishtank

import dev.dejvokep.boostedyaml.YamlDocument
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.compatibilities.craftengine.types.FishType
import org.bukkit.Bukkit
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object FishTankDataStore {

    private val plugin get() = UnearthMechanic.getInstance()

    private val file: File by lazy {
        File(plugin.dataFolder, "fish-tank-data.yml").also { it.parentFile?.mkdirs() }
    }

    private lateinit var yml: YamlDocument

    data class CellSnapshot(
        var fishType: String? = null,
        var bucketB64: String? = null,
        var entityUuid: String? = null
    )

    data class TankData(
        val cellSnapshots: MutableMap<Long, CellSnapshot> = ConcurrentHashMap(),
        val legacyByType: MutableMap<String, String> = ConcurrentHashMap(),
        val chunkKeys: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        var updatedAt: Long = 0L
    )

    private val cache = ConcurrentHashMap<String, TankData>()
    private val chunkIndex = ConcurrentHashMap<String, MutableSet<String>>()

    private fun chunkKey(worldName: String, chunkX: Int, chunkZ: Int): String =
        "$worldName:$chunkX,$chunkZ"

    private fun addToChunkIndex(chunkKey: String, tankKey: String) {
        chunkIndex.computeIfAbsent(chunkKey) { ConcurrentHashMap.newKeySet() }.add(tankKey)
    }

    private fun removeFromChunkIndex(chunkKey: String, tankKey: String) {
        val set = chunkIndex[chunkKey] ?: return
        set.remove(tankKey)
        if (set.isEmpty()) {
            chunkIndex.remove(chunkKey, set)
        }
    }

    private fun unpackSigned(raw: Long, bits: Int): Int {
        val shift = 64 - bits
        return (raw shl shift shr shift).toInt()
    }

    private fun unpackPackedPos(packed: Long): Triple<Int, Int, Int> {
        val x = unpackSigned((packed ushr 38) and 0x3FFFFFF, 26)
        val y = unpackSigned(packed and 0xFFF, 12)
        val z = unpackSigned((packed ushr 12) and 0x3FFFFFF, 26)
        return Triple(x, y, z)
    }

    private fun deriveChunkKeysFromSnapshots(worldName: String, td: TankData): Set<String> {
        val out = LinkedHashSet<String>()
        for (packed in td.cellSnapshots.keys) {
            val (x, _, z) = unpackPackedPos(packed)
            out.add(chunkKey(worldName, x shr 4, z shr 4))
        }
        return out
    }

    private fun reindexTank(tankKey: String, td: TankData, oldChunkKeys: Collection<String> = emptySet()) {
        for (old in oldChunkKeys) {
            removeFromChunkIndex(old, tankKey)
        }

        val worldName = tankKey.substringBefore(':')
        val effectiveChunkKeys =
            if (td.chunkKeys.isNotEmpty()) td.chunkKeys.toSet()
            else deriveChunkKeysFromSnapshots(worldName, td)

        td.chunkKeys.clear()
        td.chunkKeys.addAll(effectiveChunkKeys)

        for (ck in effectiveChunkKeys) {
            addToChunkIndex(ck, tankKey)
        }
    }

    private val dirty = AtomicBoolean(false)
    @Volatile private var saveTaskId: Int = -1

    // We store packedPos as an unsigned STRING to avoid rare negative keys.
    private fun packedKey(packed: Long): String = java.lang.Long.toUnsignedString(packed)
    private fun parsePackedKey(s: String): Long = java.lang.Long.parseUnsignedLong(s)

    fun load() {
        yml = YamlDocument.create(
            file,
            GeneralSettings.DEFAULT,
            LoaderSettings.DEFAULT,
            DumperSettings.DEFAULT,
            UpdaterSettings.DEFAULT
        )

        cache.clear()
        chunkIndex.clear()
        val root = yml.getSection("fish-tank-data") ?: return

        for (tankKey in root.getRoutesAsStrings(false)) {
            val s = root.getSection(tankKey) ?: continue
            val td = TankData(
                updatedAt = s.getLong("updated_at", 0L)
            )

            // cell_snapshots
            val cs = s.getSection("cell_snapshots")
            if (cs != null) {
                for (pkStr in cs.getRoutesAsStrings(false)) {
                    val cellSec = cs.getSection(pkStr) ?: continue
                    val packed = try { parsePackedKey(pkStr) } catch (_: Throwable) { continue }
                    td.cellSnapshots[packed] = CellSnapshot(
                        fishType = cellSec.getString("fish"),
                        bucketB64 = cellSec.getString("bucket_b64"),
                        entityUuid = cellSec.getString("entity_uuid")
                    )
                }
            }

            // LEGACY: bucket_snapshots.<fishType>=b64 o bucket_b64 + fish
            val legacy = s.getSection("bucket_snapshots")
            if (legacy != null) {
                for (ft in legacy.getRoutesAsStrings(false)) {
                    val b64 = legacy.getString(ft) ?: continue
                    td.legacyByType[ft] = b64
                }
            } else {
                val legacyB64 = s.getString("bucket_b64")
                val legacyFish = s.getString("fish")
                if (legacyB64 != null && legacyFish != null) {
                    td.legacyByType[legacyFish] = legacyB64
                }
            }

            val storedChunks = s.getStringList("chunks")
            if (storedChunks != null) {
                td.chunkKeys.addAll(storedChunks)
            }

            cache[tankKey] = td
            reindexTank(tankKey, td)
        }
    }

    fun flushSaveNow() {
        if (!::yml.isInitialized) return
        writeCacheToYaml()
        yml.save()
        dirty.set(false)
    }

    fun scheduleSave(delayTicks: Long = 40L) {
        dirty.set(true)
        if (saveTaskId != -1) return

        saveTaskId = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            saveTaskId = -1
            if (!dirty.get()) return@Runnable
            try {
                flushSaveNow()
            } catch (t: Throwable) {
                Bukkit.getLogger().warning("[FishTankDataStore] save failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }, delayTicks).taskId
    }

    private fun writeCacheToYaml() {
        yml.set("fish-tank-data", null)
        val root = yml.createSection("fish-tank-data")

        for ((tankKey, v) in cache) {
            val s = root.createSection(tankKey)
            s.set("chunks", v.chunkKeys.toList().sorted())

            // cell_snapshots
            val cs = s.createSection("cell_snapshots")
            for ((packed, snap) in v.cellSnapshots) {
                val cell = cs.createSection(packedKey(packed))
                cell.set("fish", snap.fishType)
                cell.set("bucket_b64", snap.bucketB64)
                cell.set("entity_uuid",snap.entityUuid)
            }
            s.set("updated_at", v.updatedAt)

            // clean legacy
        }
    }

    private fun get(tankKey: String): TankData =
        cache.computeIfAbsent(tankKey) { TankData() }

    fun setCellBucketSnapshotB64(tankKey: String, packedCell: Long, fishType: FishType, b64: String) {
        val d = get(tankKey)
        val snap = d.cellSnapshots.computeIfAbsent(packedCell) { CellSnapshot() }

        if (snap.fishType == fishType.name && snap.bucketB64 == b64) return

        snap.fishType = fishType.name
        snap.bucketB64 = b64
        d.updatedAt = System.currentTimeMillis()
        scheduleSave()
    }

    fun getCellSnapshot(tankKey: String, packedCell: Long): CellSnapshot? =
        cache[tankKey]?.cellSnapshots?.get(packedCell)

    fun removeCellSnapshot(tankKey: String, packedCell: Long) {
        val d = cache[tankKey] ?: return
        if (d.cellSnapshots.remove(packedCell) != null) {
            d.updatedAt = System.currentTimeMillis()
            scheduleSave()
        }
    }

    fun setCellEntityUuid(tankKey: String, packedCell: Long, uuid: UUID?) {
        val d = get(tankKey)
        val snap = d.cellSnapshots.computeIfAbsent(packedCell) { CellSnapshot() }
        val newValue = uuid?.toString()
        if (snap.entityUuid == newValue) return

        snap.entityUuid = newValue
        d.updatedAt = System.currentTimeMillis()
        scheduleSave()
    }

    fun getCellEntityUuid(tankKey: String, packedCell: Long): UUID? {
        val s = cache[tankKey]?.cellSnapshots?.get(packedCell)?.entityUuid ?: return null
        return runCatching { UUID.fromString(s) }.getOrNull()
    }

    fun findTankKeyByCell(worldName: String, packedCell: Long): String? {
        return cache.entries
            .firstOrNull { (k, v) -> k.startsWith("$worldName:") && v.cellSnapshots.containsKey(packedCell) }
            ?.key
    }

    fun removeTank(tankKey: String) {
        val removed = cache.remove(tankKey) ?: return
        val oldChunkKeys =
            if (removed.chunkKeys.isNotEmpty()) removed.chunkKeys.toList()
            else deriveChunkKeysFromSnapshots(tankKey.substringBefore(':'), removed).toList()

        for (ck in oldChunkKeys) {
            removeFromChunkIndex(ck, tankKey)
        }

        scheduleSave()
    }

    fun migrateKey(oldKey: String, newKey: String) {
        if (oldKey == newKey) return

        val old = cache.remove(oldKey) ?: return

        val oldChunkKeys =
            if (old.chunkKeys.isNotEmpty()) old.chunkKeys.toList()
            else deriveChunkKeysFromSnapshots(oldKey.substringBefore(':'), old).toList()

        for (ck in oldChunkKeys) {
            removeFromChunkIndex(ck, oldKey)
        }

        cache[newKey] = old
        reindexTank(newKey, old)
        scheduleSave()
    }

    fun setTankChunkKeys(tankKey: String, chunkKeys: Collection<String>) {
        val d = get(tankKey)
        val newChunkKeys = chunkKeys.toSet()

        if (d.chunkKeys.size == newChunkKeys.size && d.chunkKeys.containsAll(newChunkKeys)) {
            return
        }

        val oldChunkKeys = d.chunkKeys.toList()

        d.chunkKeys.clear()
        d.chunkKeys.addAll(newChunkKeys)

        reindexTank(tankKey, d, oldChunkKeys)
        d.updatedAt = System.currentTimeMillis()
        scheduleSave()
    }

    fun getTankKeysForChunk(worldName: String, chunkX: Int, chunkZ: Int): Set<String> {
        return chunkIndex[chunkKey(worldName, chunkX, chunkZ)]?.toSet() ?: emptySet()
    }

    fun findAnchorCellInChunk(tankKey: String, chunkX: Int, chunkZ: Int): Long? {
        val td = cache[tankKey] ?: return null

        for ((packed, snap) in td.cellSnapshots) {
            if (snap.fishType == null) continue
            val (x, _, z) = unpackPackedPos(packed)
            if ((x shr 4) == chunkX && (z shr 4) == chunkZ) {
                return packed
            }
        }

        return null
    }

    fun findAnyAnchorCell(tankKey: String): Long? {
        val td = cache[tankKey] ?: return null
        return td.cellSnapshots.entries.firstOrNull { it.value.fishType != null }?.key
    }
}