package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.fishtank

import com.tcoded.folialib.wrapper.task.WrappedTask
import dev.dejvokep.boostedyaml.YamlDocument
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.compatibilities.craftengine.types.FishType
import dev.wuason.unearthMechanic.utils.FoliaUtils
import org.bukkit.Bukkit
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object FishTankDataStore {

    private val plugin get() = UnearthMechanic.getInstance()

    private val dbFile: File by lazy {
        File(plugin.dataFolder, "internal-data/fish-tank-data").also {
            it.parentFile?.mkdirs()
        }
    }

    private val legacyYamlFile: File by lazy {
        File(plugin.dataFolder, "internal-data/fish-tank-data.yml").also {
            it.parentFile?.mkdirs()
        }
    }

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

    private lateinit var connection: Connection

    private val cache = ConcurrentHashMap<String, TankData>()
    private val chunkIndex = ConcurrentHashMap<String, MutableSet<String>>()

    private val dirty = AtomicBoolean(false)

    private val dirtyTankKeys = ConcurrentHashMap.newKeySet<String>()
    private val deletedTankKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var saveTask: WrappedTask? = null

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

    private fun deriveChunkKeysFromSnapshots(
        worldName: String,
        td: TankData
    ): Set<String> {
        val out = LinkedHashSet<String>()

        for (packed in td.cellSnapshots.keys) {
            val (x, _, z) = unpackPackedPos(packed)
            out.add(chunkKey(worldName, x shr 4, z shr 4))
        }

        return out
    }

    private fun reindexTank(
        tankKey: String,
        td: TankData,
        oldChunkKeys: Collection<String> = emptySet()
    ) {
        for (old in oldChunkKeys) {
            removeFromChunkIndex(old, tankKey)
        }

        val worldName = tankKey.substringBefore(':')

        val effectiveChunkKeys =
            if (td.chunkKeys.isNotEmpty()) {
                td.chunkKeys.toSet()
            } else {
                deriveChunkKeysFromSnapshots(worldName, td)
            }

        td.chunkKeys.clear()
        td.chunkKeys.addAll(effectiveChunkKeys)

        for (ck in effectiveChunkKeys) {
            addToChunkIndex(ck, tankKey)
        }
    }

    private fun markDirty(tankKey: String) {
        deletedTankKeys.remove(tankKey)
        dirtyTankKeys.add(tankKey)
        scheduleSave()
    }

    fun load() {
        initDatabase()

        cache.clear()
        chunkIndex.clear()
        dirtyTankKeys.clear()
        deletedTankKeys.clear()

        migrateLegacyYamlIfNeeded()

        loadFromDatabase()

        dirty.set(false)
    }

    private fun migrateLegacyYamlIfNeeded() {
        if (!legacyYamlFile.exists()) return
        if (countTanks() > 0L) return

        val yml = YamlDocument.create(
            legacyYamlFile,
            GeneralSettings.DEFAULT,
            LoaderSettings.DEFAULT,
            DumperSettings.DEFAULT,
            UpdaterSettings.DEFAULT
        )

        val root = yml.getSection("fish-tank-data") ?: return

        var migratedTanks = 0
        var migratedCells = 0
        var migratedChunks = 0

        connection.autoCommit = false

        try {
            val now = System.currentTimeMillis()

            connection.prepareStatement(
                """
            MERGE INTO fish_tanks (
                tank_key,
                updated_at
            )
            KEY(tank_key)
            VALUES (?, ?)
            """.trimIndent()
            ).use { tankPs ->

                connection.prepareStatement(
                    """
                MERGE INTO fish_tank_cells (
                    tank_key,
                    packed_cell,
                    fish_type,
                    bucket_b64,
                    entity_uuid
                )
                KEY(tank_key, packed_cell)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
                ).use { cellPs ->

                    connection.prepareStatement(
                        """
                    MERGE INTO fish_tank_chunks (
                        tank_key,
                        chunk_key
                    )
                    KEY(tank_key, chunk_key)
                    VALUES (?, ?)
                    """.trimIndent()
                    ).use { chunkPs ->

                        for (tankKey in root.getRoutesAsStrings(false)) {
                            val section = root.getSection(tankKey) ?: continue

                            val updatedAt = section.getLong("updated_at", now)

                            tankPs.setString(1, tankKey)
                            tankPs.setLong(2, updatedAt)
                            tankPs.addBatch()
                            migratedTanks++

                            val cellSnapshots = section.getSection("cell_snapshots")

                            if (cellSnapshots != null) {
                                for (packedCellRaw in cellSnapshots.getRoutesAsStrings(false)) {
                                    val cellSection = cellSnapshots.getSection(packedCellRaw) ?: continue

                                    val packedCell = try {
                                        parsePackedKey(packedCellRaw)
                                    } catch (_: Throwable) {
                                        continue
                                    }

                                    val fishType = cellSection.getString("fish")
                                    val bucketB64 = cellSection.getString("bucket_b64")
                                    val entityUuid = cellSection.getString("entity_uuid")

                                    cellPs.setString(1, tankKey)
                                    cellPs.setString(2, packedKey(packedCell))
                                    cellPs.setString(3, fishType)
                                    cellPs.setString(4, bucketB64)
                                    cellPs.setString(5, entityUuid)
                                    cellPs.addBatch()

                                    migratedCells++
                                }
                            }

                            val chunks = section.getStringList("chunks")

                            if (chunks != null && chunks.isNotEmpty()) {
                                for (chunkKey in chunks) {
                                    chunkPs.setString(1, tankKey)
                                    chunkPs.setString(2, chunkKey)
                                    chunkPs.addBatch()

                                    migratedChunks++
                                }
                            } else {
                                val derivedChunks = deriveChunkKeysFromYamlPackedCells(
                                    tankKey,
                                    cellSnapshots?.getRoutesAsStrings(false) ?: emptySet()
                                )

                                for (chunkKey in derivedChunks) {
                                    chunkPs.setString(1, tankKey)
                                    chunkPs.setString(2, chunkKey)
                                    chunkPs.addBatch()

                                    migratedChunks++
                                }
                            }
                        }

                        tankPs.executeBatch()
                        cellPs.executeBatch()
                        chunkPs.executeBatch()
                    }
                }
            }

            connection.commit()

            val backup = File(
                legacyYamlFile.parentFile,
                "fish-tank-data.migrated.yml.bak"
            )

            if (backup.exists()) {
                backup.delete()
            }

            legacyYamlFile.renameTo(backup)

            Bukkit.getLogger().info(
                "[FishTankDataStore] Migrated $migratedTanks tanks, $migratedCells cells and $migratedChunks chunks from YAML to H2."
            )
        } catch (t: Throwable) {
            connection.rollback()

            Bukkit.getLogger().warning(
                "[FishTankDataStore] YAML migration failed: ${t.javaClass.simpleName}: ${t.message}"
            )
        } finally {
            connection.autoCommit = true
        }
    }

    private fun countTanks(): Long {
        connection.prepareStatement(
            """
        SELECT COUNT(*)
        FROM fish_tanks
        """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    private fun deriveChunkKeysFromYamlPackedCells(
        tankKey: String,
        packedCellKeys: Collection<String>
    ): Set<String> {
        val worldName = tankKey.substringBefore(':')
        val out = LinkedHashSet<String>()

        for (packedCellRaw in packedCellKeys) {
            val packedCell = try {
                parsePackedKey(packedCellRaw)
            } catch (_: Throwable) {
                continue
            }

            val (x, _, z) = unpackPackedPos(packedCell)
            out.add(chunkKey(worldName, x shr 4, z shr 4))
        }

        return out
    }

    private fun initDatabase() {
        Class.forName("org.h2.Driver")

        val url = "jdbc:h2:${dbFile.absolutePath};MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_ON_EXIT=FALSE"

        connection = DriverManager.getConnection(url)
        connection.autoCommit = true

        connection.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS fish_tanks (
                    tank_key VARCHAR(256) NOT NULL PRIMARY KEY,
                    updated_at BIGINT NOT NULL
                )
                """.trimIndent()
            )

            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS fish_tank_cells (
                    tank_key VARCHAR(256) NOT NULL,
                    packed_cell VARCHAR(32) NOT NULL,
                    fish_type VARCHAR(128),
                    bucket_b64 CLOB,
                    entity_uuid VARCHAR(36),
                    PRIMARY KEY (tank_key, packed_cell)
                )
                """.trimIndent()
            )

            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS fish_tank_chunks (
                    tank_key VARCHAR(256) NOT NULL,
                    chunk_key VARCHAR(256) NOT NULL,
                    PRIMARY KEY (tank_key, chunk_key)
                )
                """.trimIndent()
            )

            st.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_fish_tank_chunks_chunk_key
                ON fish_tank_chunks(chunk_key)
                """.trimIndent()
            )

            st.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_fish_tank_cells_tank_key
                ON fish_tank_cells(tank_key)
                """.trimIndent()
            )
        }
    }

    private fun loadFromDatabase() {
        connection.prepareStatement(
            """
            SELECT tank_key, updated_at
            FROM fish_tanks
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val tankKey = rs.getString("tank_key")

                    cache[tankKey] = TankData(
                        updatedAt = rs.getLong("updated_at")
                    )
                }
            }
        }

        connection.prepareStatement(
            """
            SELECT tank_key, packed_cell, fish_type, bucket_b64, entity_uuid
            FROM fish_tank_cells
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val tankKey = rs.getString("tank_key")
                    val packedCell = parsePackedKey(rs.getString("packed_cell"))

                    val td = cache.computeIfAbsent(tankKey) { TankData() }

                    td.cellSnapshots[packedCell] = CellSnapshot(
                        fishType = rs.getString("fish_type"),
                        bucketB64 = rs.getString("bucket_b64"),
                        entityUuid = rs.getString("entity_uuid")
                    )
                }
            }
        }

        connection.prepareStatement(
            """
            SELECT tank_key, chunk_key
            FROM fish_tank_chunks
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val tankKey = rs.getString("tank_key")
                    val chunkKey = rs.getString("chunk_key")

                    val td = cache.computeIfAbsent(tankKey) { TankData() }
                    td.chunkKeys.add(chunkKey)
                }
            }
        }

        for ((tankKey, td) in cache) {
            reindexTank(tankKey, td)
        }
    }

    private fun packedKey(packed: Long): String =
        java.lang.Long.toUnsignedString(packed)

    private fun parsePackedKey(s: String): Long =
        java.lang.Long.parseUnsignedLong(s)

    fun flushSaveNow() {
        if (!::connection.isInitialized) return

        val tanksToSave = dirtyTankKeys.toList()
        val tanksToDelete = deletedTankKeys.toList()

        if (tanksToSave.isEmpty() && tanksToDelete.isEmpty()) {
            dirty.set(false)
            return
        }

        connection.autoCommit = false

        try {
            if (tanksToDelete.isNotEmpty()) {
                deleteTanksFromDatabase(tanksToDelete)
            }

            if (tanksToSave.isNotEmpty()) {
                saveTanksToDatabase(tanksToSave)
            }

            connection.commit()

            for (tankKey in tanksToSave) {
                dirtyTankKeys.remove(tankKey)
            }

            for (tankKey in tanksToDelete) {
                deletedTankKeys.remove(tankKey)
            }

            dirty.set(false)
        } catch (t: Throwable) {
            connection.rollback()

            Bukkit.getLogger().warning(
                "[FishTankDataStore] H2 save failed: ${t.javaClass.simpleName}: ${t.message}"
            )
        } finally {
            connection.autoCommit = true
        }
    }

    private fun deleteTanksFromDatabase(tankKeys: Collection<String>) {
        connection.prepareStatement(
            """
            DELETE FROM fish_tank_cells
            WHERE tank_key = ?
            """.trimIndent()
        ).use { ps ->
            for (tankKey in tankKeys) {
                ps.setString(1, tankKey)
                ps.addBatch()
            }

            ps.executeBatch()
        }

        connection.prepareStatement(
            """
            DELETE FROM fish_tank_chunks
            WHERE tank_key = ?
            """.trimIndent()
        ).use { ps ->
            for (tankKey in tankKeys) {
                ps.setString(1, tankKey)
                ps.addBatch()
            }

            ps.executeBatch()
        }

        connection.prepareStatement(
            """
            DELETE FROM fish_tanks
            WHERE tank_key = ?
            """.trimIndent()
        ).use { ps ->
            for (tankKey in tankKeys) {
                ps.setString(1, tankKey)
                ps.addBatch()
            }

            ps.executeBatch()
        }
    }

    private fun saveTanksToDatabase(tankKeys: Collection<String>) {
        connection.prepareStatement(
            """
            MERGE INTO fish_tanks (
                tank_key, updated_at
            )
            KEY(tank_key)
            VALUES (?, ?)
            """.trimIndent()
        ).use { ps ->
            for (tankKey in tankKeys) {
                val td = cache[tankKey] ?: continue

                ps.setString(1, tankKey)
                ps.setLong(2, td.updatedAt)
                ps.addBatch()
            }

            ps.executeBatch()
        }

        connection.prepareStatement(
            """
            DELETE FROM fish_tank_cells
            WHERE tank_key = ?
            """.trimIndent()
        ).use { ps ->
            for (tankKey in tankKeys) {
                ps.setString(1, tankKey)
                ps.addBatch()
            }

            ps.executeBatch()
        }

        connection.prepareStatement(
            """
            DELETE FROM fish_tank_chunks
            WHERE tank_key = ?
            """.trimIndent()
        ).use { ps ->
            for (tankKey in tankKeys) {
                ps.setString(1, tankKey)
                ps.addBatch()
            }

            ps.executeBatch()
        }

        connection.prepareStatement(
            """
            INSERT INTO fish_tank_cells (
                tank_key,
                packed_cell,
                fish_type,
                bucket_b64,
                entity_uuid
            )
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            for (tankKey in tankKeys) {
                val td = cache[tankKey] ?: continue

                for ((packedCell, snap) in td.cellSnapshots) {
                    ps.setString(1, tankKey)
                    ps.setString(2, packedKey(packedCell))
                    ps.setString(3, snap.fishType)
                    ps.setString(4, snap.bucketB64)
                    ps.setString(5, snap.entityUuid)
                    ps.addBatch()
                }
            }

            ps.executeBatch()
        }

        connection.prepareStatement(
            """
            INSERT INTO fish_tank_chunks (
                tank_key,
                chunk_key
            )
            VALUES (?, ?)
            """.trimIndent()
        ).use { ps ->
            for (tankKey in tankKeys) {
                val td = cache[tankKey] ?: continue

                for (chunkKey in td.chunkKeys) {
                    ps.setString(1, tankKey)
                    ps.setString(2, chunkKey)
                    ps.addBatch()
                }
            }

            ps.executeBatch()
        }
    }

    fun scheduleSave(delayTicks: Long = 40L) {
        dirty.set(true)

        if (saveTask != null) return

        saveTask = FoliaUtils.runLater(delayTicks) {
            saveTask = null

            if (!dirty.get()) return@runLater

            try {
                flushSaveNow()
            } catch (t: Throwable) {
                Bukkit.getLogger().warning(
                    "[FishTankDataStore] save failed: ${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
    }

    private fun get(tankKey: String): TankData =
        cache.computeIfAbsent(tankKey) { TankData() }

    fun setCellBucketSnapshotB64(
        tankKey: String,
        packedCell: Long,
        fishType: FishType,
        b64: String
    ) {
        val d = get(tankKey)
        val snap = d.cellSnapshots.computeIfAbsent(packedCell) { CellSnapshot() }

        if (snap.fishType == fishType.name && snap.bucketB64 == b64) return

        snap.fishType = fishType.name
        snap.bucketB64 = b64
        d.updatedAt = System.currentTimeMillis()

        markDirty(tankKey)
    }

    fun getCellSnapshot(
        tankKey: String,
        packedCell: Long
    ): CellSnapshot? =
        cache[tankKey]?.cellSnapshots?.get(packedCell)

    fun removeCellSnapshot(
        tankKey: String,
        packedCell: Long
    ) {
        val d = cache[tankKey] ?: return

        if (d.cellSnapshots.remove(packedCell) != null) {
            d.updatedAt = System.currentTimeMillis()
            markDirty(tankKey)
        }
    }

    fun setCellEntityUuid(
        tankKey: String,
        packedCell: Long,
        uuid: UUID?
    ) {
        val d = get(tankKey)
        val snap = d.cellSnapshots.computeIfAbsent(packedCell) { CellSnapshot() }

        val newValue = uuid?.toString()

        if (snap.entityUuid == newValue) return

        snap.entityUuid = newValue
        d.updatedAt = System.currentTimeMillis()

        markDirty(tankKey)
    }

    fun getCellEntityUuid(
        tankKey: String,
        packedCell: Long
    ): UUID? {
        val s = cache[tankKey]
            ?.cellSnapshots
            ?.get(packedCell)
            ?.entityUuid
            ?: return null

        return runCatching {
            UUID.fromString(s)
        }.getOrNull()
    }

    fun findTankKeyByCell(
        worldName: String,
        packedCell: Long
    ): String? {
        return cache.entries
            .firstOrNull { (k, v) ->
                k.startsWith("$worldName:") &&
                        v.cellSnapshots.containsKey(packedCell)
            }
            ?.key
    }

    fun removeTank(tankKey: String) {
        val removed = cache.remove(tankKey) ?: return

        val oldChunkKeys =
            if (removed.chunkKeys.isNotEmpty()) {
                removed.chunkKeys.toList()
            } else {
                deriveChunkKeysFromSnapshots(
                    tankKey.substringBefore(':'),
                    removed
                ).toList()
            }

        for (ck in oldChunkKeys) {
            removeFromChunkIndex(ck, tankKey)
        }

        dirtyTankKeys.remove(tankKey)
        deletedTankKeys.add(tankKey)

        scheduleSave()
    }

    fun migrateKey(
        oldKey: String,
        newKey: String
    ) {
        if (oldKey == newKey) return

        val old = cache.remove(oldKey) ?: return

        val oldChunkKeys =
            if (old.chunkKeys.isNotEmpty()) {
                old.chunkKeys.toList()
            } else {
                deriveChunkKeysFromSnapshots(
                    oldKey.substringBefore(':'),
                    old
                ).toList()
            }

        for (ck in oldChunkKeys) {
            removeFromChunkIndex(ck, oldKey)
        }

        cache[newKey] = old
        reindexTank(newKey, old)

        dirtyTankKeys.remove(oldKey)
        deletedTankKeys.add(oldKey)
        dirtyTankKeys.add(newKey)
        deletedTankKeys.remove(newKey)

        scheduleSave()
    }

    fun setTankChunkKeys(
        tankKey: String,
        chunkKeys: Collection<String>
    ) {
        val d = get(tankKey)
        val newChunkKeys = chunkKeys.toSet()

        if (d.chunkKeys.size == newChunkKeys.size &&
            d.chunkKeys.containsAll(newChunkKeys)
        ) {
            return
        }

        val oldChunkKeys = d.chunkKeys.toList()

        d.chunkKeys.clear()
        d.chunkKeys.addAll(newChunkKeys)

        reindexTank(tankKey, d, oldChunkKeys)

        d.updatedAt = System.currentTimeMillis()

        markDirty(tankKey)
    }

    fun getTankKeysForChunk(
        worldName: String,
        chunkX: Int,
        chunkZ: Int
    ): Set<String> {
        return chunkIndex[chunkKey(worldName, chunkX, chunkZ)]
            ?.toSet()
            ?: emptySet()
    }

    fun findAnchorCellInChunk(
        tankKey: String,
        chunkX: Int,
        chunkZ: Int
    ): Long? {
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

        return td.cellSnapshots.entries
            .firstOrNull { it.value.fishType != null }
            ?.key
    }

    fun close() {
        saveTask?.let { task ->
            try {
                task.cancel()
            } catch (_: Throwable) {
            }

            saveTask = null
        }

        try {
            flushSaveNow()
        } catch (t: Throwable) {
            Bukkit.getLogger().warning(
                "[FishTankDataStore] close flush failed: ${t.javaClass.simpleName}: ${t.message}"
            )
        }

        if (::connection.isInitialized) {
            try {
                if (!connection.isClosed) {
                    connection.close()
                }
            } catch (_: Throwable) {
            }
        }
    }
}