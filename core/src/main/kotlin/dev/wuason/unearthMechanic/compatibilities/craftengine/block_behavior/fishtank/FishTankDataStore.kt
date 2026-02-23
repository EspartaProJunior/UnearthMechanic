package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.fishtank

import dev.wuason.libs.boostedyaml.YamlDocument
import dev.wuason.libs.boostedyaml.settings.dumper.DumperSettings
import dev.wuason.libs.boostedyaml.settings.general.GeneralSettings
import dev.wuason.libs.boostedyaml.settings.loader.LoaderSettings
import dev.wuason.libs.boostedyaml.settings.updater.UpdaterSettings
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
        var updatedAt: Long = 0L
    )

    private val cache = ConcurrentHashMap<String, TankData>()

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

            cache[tankKey] = td
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
        snap.entityUuid = uuid?.toString()
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
        cache.remove(tankKey)
        scheduleSave()
    }

    fun migrateKey(oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        val old = cache.remove(oldKey) ?: return
        cache[newKey] = old
        scheduleSave()
    }
}