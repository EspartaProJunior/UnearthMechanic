package dev.wuason.unearthMechanic.system

import dev.wuason.adapter.Adapter
import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.UnearthMechanic
import org.bukkit.Bukkit
import org.bukkit.Location
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.optionals.getOrNull

object PreviousBlockDataStore {

    private val plugin get() = UnearthMechanic.getInstance()

    private val dbFile: File by lazy {
        File(plugin.dataFolder, "internal-data/previous-block-data").also {
            it.parentFile?.mkdirs()
        }
    }

    data class PreviousBlockSnapshot(
        val adapterData: AdapterData,
        val props: Map<String, String> = emptyMap()
    )

    private data class LocKey(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int
    ) {
        fun asString(): String = "$world:$x,$y,$z"
    }

    private data class ParsedBlockId(
        val cleanId: String,
        val props: Map<String, String>
    )

    private data class PendingUpsert(
        val locKey: LocKey,
        val rawBlockId: String
    )

    private lateinit var connection: Connection

    private val dirty = AtomicBoolean(false)

    private val snapshotCache = ConcurrentHashMap<String, PreviousBlockSnapshot>()

    private val pendingUpserts = ConcurrentHashMap<String, PendingUpsert>()
    private val pendingDeletes = ConcurrentHashMap<String, LocKey>()

    @Volatile
    private var saveTaskId: Int = -1

    private fun locKey(loc: Location): LocKey {
        val world = loc.world?.name ?: "unknown"
        val block = loc.block.location

        return LocKey(
            world = world,
            x = block.blockX,
            y = block.blockY,
            z = block.blockZ
        )
    }

    private fun parseBlockStateId(raw: String): ParsedBlockId {
        val value = raw.trim()
        val start = value.indexOf('[')
        val end = value.lastIndexOf(']')

        if (start == -1 || end == -1 || end <= start) {
            return ParsedBlockId(value, emptyMap())
        }

        val cleanId = value.substring(0, start).trim()
        val propsRaw = value.substring(start + 1, end)

        val props = propsRaw.split(",")
            .mapNotNull {
                val split = it.split("=", limit = 2)
                if (split.size != 2) return@mapNotNull null
                split[0].trim().lowercase() to split[1].trim().lowercase()
            }
            .toMap()

        return ParsedBlockId(cleanId, props)
    }

    private fun serializeBlockStateId(
        adapterData: AdapterData,
        props: Map<String, String>
    ): String {
        val adapterType = adapterData.adapter?.type ?: return adapterData.id
        val cleanId = "$adapterType:${adapterData.id}"

        if (props.isEmpty()) return cleanId

        val propsText = props.entries.joinToString(",") { "${it.key}=${it.value}" }
        return "$cleanId[$propsText]"
    }

    fun load() {
        initDatabase()

        snapshotCache.clear()
        pendingUpserts.clear()
        pendingDeletes.clear()
        dirty.set(false)
    }

    private fun initDatabase() {
        Class.forName("org.h2.Driver")

        val url = "jdbc:h2:${dbFile.absolutePath};MODE=MySQL;DATABASE_TO_UPPER=false"

        connection = DriverManager.getConnection(url)
        connection.autoCommit = true

        connection.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS previous_block_data (
                    world VARCHAR(128) NOT NULL,
                    x INT NOT NULL,
                    y INT NOT NULL,
                    z INT NOT NULL,
                    raw_block_id VARCHAR(512) NOT NULL,
                    updated_at BIGINT NOT NULL,
                    PRIMARY KEY (world, x, y, z)
                )
                """.trimIndent()
            )

            st.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_previous_block_data_world_xz
                ON previous_block_data(world, x, z)
                """.trimIndent()
            )
        }
    }

    fun save(
        loc: Location,
        adapterData: AdapterData,
        props: Map<String, String> = emptyMap()
    ) {
        val locKey = locKey(loc)
        val cacheKey = locKey.asString()
        val rawBlockId = serializeBlockStateId(adapterData, props)

        snapshotCache[cacheKey] = PreviousBlockSnapshot(adapterData, props)

        pendingDeletes.remove(cacheKey)
        pendingUpserts[cacheKey] = PendingUpsert(locKey, rawBlockId)

        scheduleSave()
    }

    fun get(loc: Location): PreviousBlockSnapshot? {
        val locKey = locKey(loc)
        val cacheKey = locKey.asString()

        if (pendingDeletes.containsKey(cacheKey)) {
            return null
        }

        snapshotCache[cacheKey]?.let {
            return it
        }

        val rawBlockId = getRawFromDatabase(locKey) ?: return null
        val parsed = parseBlockStateId(rawBlockId)

        val adapterData = Adapter.getAdapterData(parsed.cleanId).getOrNull() ?: return null

        val snapshot = PreviousBlockSnapshot(adapterData, parsed.props)
        snapshotCache[cacheKey] = snapshot

        return snapshot
    }

    private fun getRawFromDatabase(locKey: LocKey): String? {
        connection.prepareStatement(
            """
            SELECT raw_block_id
            FROM previous_block_data
            WHERE world = ? AND x = ? AND y = ? AND z = ?
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, locKey.world)
            ps.setInt(2, locKey.x)
            ps.setInt(3, locKey.y)
            ps.setInt(4, locKey.z)

            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getString("raw_block_id") else null
            }
        }
    }

    fun remove(loc: Location) {
        val locKey = locKey(loc)
        val cacheKey = locKey.asString()

        snapshotCache.remove(cacheKey)

        pendingUpserts.remove(cacheKey)
        pendingDeletes[cacheKey] = locKey

        scheduleSave()
    }

    fun flushSaveNow() {
        if (!::connection.isInitialized) return

        val upserts = pendingUpserts.values.toList()
        val deletes = pendingDeletes.values.toList()

        if (upserts.isEmpty() && deletes.isEmpty()) {
            dirty.set(false)
            return
        }

        connection.autoCommit = false

        try {
            if (deletes.isNotEmpty()) {
                connection.prepareStatement(
                    """
                    DELETE FROM previous_block_data
                    WHERE world = ? AND x = ? AND y = ? AND z = ?
                    """.trimIndent()
                ).use { ps ->
                    for (locKey in deletes) {
                        bindLoc(ps, locKey)
                        ps.addBatch()
                    }

                    ps.executeBatch()
                }
            }

            if (upserts.isNotEmpty()) {
                connection.prepareStatement(
                    """
                    MERGE INTO previous_block_data (
                        world, x, y, z, raw_block_id, updated_at
                    )
                    KEY(world, x, y, z)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    for (upsert in upserts) {
                        val locKey = upsert.locKey

                        ps.setString(1, locKey.world)
                        ps.setInt(2, locKey.x)
                        ps.setInt(3, locKey.y)
                        ps.setInt(4, locKey.z)
                        ps.setString(5, upsert.rawBlockId)
                        ps.setLong(6, System.currentTimeMillis())
                        ps.addBatch()
                    }

                    ps.executeBatch()
                }
            }

            connection.commit()

            for (upsert in upserts) {
                pendingUpserts.remove(upsert.locKey.asString())
            }

            for (locKey in deletes) {
                pendingDeletes.remove(locKey.asString())
            }

            dirty.set(false)
        } catch (t: Throwable) {
            connection.rollback()

            Bukkit.getLogger().warning(
                "[PreviousBlockDataStore] H2 save failed: ${t.javaClass.simpleName}: ${t.message}"
            )
        } finally {
            connection.autoCommit = true
        }
    }

    private fun bindLoc(ps: PreparedStatement, locKey: LocKey) {
        ps.setString(1, locKey.world)
        ps.setInt(2, locKey.x)
        ps.setInt(3, locKey.y)
        ps.setInt(4, locKey.z)
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
                Bukkit.getLogger().warning(
                    "[PreviousBlockDataStore] save failed: ${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }, delayTicks).taskId
    }

    fun close() {
        try {
            flushSaveNow()
        } catch (t: Throwable) {
            Bukkit.getLogger().warning(
                "[PreviousBlockDataStore] close flush failed: ${t.javaClass.simpleName}: ${t.message}"
            )
        }

        if (::connection.isInitialized) {
            try {
                connection.close()
            } catch (_: Throwable) {
            }
        }
    }
}