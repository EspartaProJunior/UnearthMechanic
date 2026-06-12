package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.meerkat_cache

import com.tcoded.folialib.wrapper.task.WrappedTask
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.utils.FoliaUtils
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

object MeerkatCacheDataStore {
    private val plugin get() = UnearthMechanic.getInstance()
    private val dbFile: File by lazy {
        File(plugin.dataFolder, "internal-data/meerkat-cache-data").also {
            it.parentFile?.mkdirs()
        }
    }

    data class CacheData(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val kind: String,
        val originalBlock: Material,
        val item: ItemStack?,
        var shelteredMeerkats: Int = 0,
        var brushProgress: Int = 0,
        var updatedAt: Long = 0L
    )

    private lateinit var connection: Connection
    private val cache = ConcurrentHashMap<String, CacheData>()
    private val dirtyKeys = ConcurrentHashMap.newKeySet<String>()
    private val deletedKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var saveTask: WrappedTask? = null

    fun load() {
        initDatabase()
        cache.clear()
        dirtyKeys.clear()
        deletedKeys.clear()

        connection.prepareStatement(
            """
            SELECT cache_key, world, x, y, z, kind, original_block, item_data, sheltered_meerkats, brush_progress, updated_at
            FROM meerkat_caches
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val item = rs.getString("item_data")?.let { decodeItem(it) }
                    val original = Material.matchMaterial(rs.getString("original_block")) ?: Material.SAND
                    cache[rs.getString("cache_key")] = CacheData(
                        world = rs.getString("world"),
                        x = rs.getInt("x"),
                        y = rs.getInt("y"),
                        z = rs.getInt("z"),
                        kind = rs.getString("kind") ?: "cache",
                        originalBlock = original,
                        item = item,
                        shelteredMeerkats = rs.getInt("sheltered_meerkats"),
                        brushProgress = rs.getInt("brush_progress"),
                        updatedAt = rs.getLong("updated_at")
                    )
                }
            }
        }
    }

    private fun initDatabase() {
        Class.forName("org.h2.Driver")
        connection = DriverManager.getConnection(
            "jdbc:h2:${dbFile.absolutePath};MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_ON_EXIT=FALSE"
        )
        connection.autoCommit = true

        connection.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS meerkat_caches (
                    cache_key VARCHAR(256) NOT NULL PRIMARY KEY,
                    world VARCHAR(128) NOT NULL,
                    x INT NOT NULL,
                    y INT NOT NULL,
                    z INT NOT NULL,
                    kind VARCHAR(32) NOT NULL DEFAULT 'cache',
                    original_block VARCHAR(64) NOT NULL,
                    item_data CLOB,
                    sheltered_meerkats INT NOT NULL DEFAULT 0,
                    brush_progress INT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate("ALTER TABLE meerkat_caches ADD COLUMN IF NOT EXISTS sheltered_meerkats INT NOT NULL DEFAULT 0")
            st.executeUpdate("ALTER TABLE meerkat_caches ADD COLUMN IF NOT EXISTS kind VARCHAR(32) NOT NULL DEFAULT 'cache'")
            runCatching {
                st.executeUpdate("ALTER TABLE meerkat_caches ALTER COLUMN item_data CLOB NULL")
            }
        }
    }

    fun put(key: String, data: CacheData) {
        cache[key] = data.copy(updatedAt = System.currentTimeMillis())
        dirtyKeys.add(key)
        deletedKeys.remove(key)
        scheduleSave()
    }

    fun peek(key: String): CacheData? = cache[key]

    fun all(): Map<String, CacheData> = cache.toMap()

    fun incrementBrushProgress(key: String): CacheData? {
        val data = cache[key] ?: return null
        data.brushProgress += 1
        touch(key, data)
        return data
    }

    fun remove(key: String): CacheData? {
        val removed = cache.remove(key)
        dirtyKeys.remove(key)
        deletedKeys.add(key)
        scheduleSave()
        return removed
    }

    fun countInChunk(world: String, chunkX: Int, chunkZ: Int): Int =
        cache.values.count { data ->
            data.world == world && (data.x shr 4) == chunkX && (data.z shr 4) == chunkZ
        }

    private fun touch(key: String, data: CacheData) {
        data.updatedAt = System.currentTimeMillis()
        dirtyKeys.add(key)
        deletedKeys.remove(key)
        scheduleSave()
    }

    fun flushSaveNow() {
        if (!::connection.isInitialized) return

        val toDelete = deletedKeys.toList()
        val toSave = dirtyKeys.toList()
        if (toDelete.isEmpty() && toSave.isEmpty()) return

        connection.autoCommit = false
        try {
            connection.prepareStatement("DELETE FROM meerkat_caches WHERE cache_key = ?").use { ps ->
                for (key in toDelete) {
                    ps.setString(1, key)
                    ps.addBatch()
                }
                ps.executeBatch()
            }

            connection.prepareStatement(
                """
                MERGE INTO meerkat_caches (
                    cache_key, world, x, y, z, kind, original_block, item_data, sheltered_meerkats, brush_progress, updated_at
                )
                KEY(cache_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                for (key in toSave) {
                    val data = cache[key] ?: continue
                    ps.setString(1, key)
                    ps.setString(2, data.world)
                    ps.setInt(3, data.x)
                    ps.setInt(4, data.y)
                    ps.setInt(5, data.z)
                    ps.setString(6, data.kind)
                    ps.setString(7, data.originalBlock.name)
                    ps.setString(8, data.item?.let { encodeItem(it) })
                    ps.setInt(9, data.shelteredMeerkats)
                    ps.setInt(10, data.brushProgress)
                    ps.setLong(11, data.updatedAt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }

            connection.commit()
            toDelete.forEach { deletedKeys.remove(it) }
            toSave.forEach { dirtyKeys.remove(it) }
        } catch (t: Throwable) {
            connection.rollback()
            Bukkit.getLogger().warning("[MeerkatCacheDataStore] save failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            connection.autoCommit = true
        }
    }

    private fun scheduleSave(delayTicks: Long = 40L) {
        if (saveTask != null) return
        saveTask = FoliaUtils.runLater(delayTicks) {
            saveTask = null
            flushSaveNow()
        }
    }

    fun close() {
        saveTask?.cancel()
        saveTask = null
        flushSaveNow()
        if (::connection.isInitialized) connection.close()
    }

    private fun encodeItem(item: ItemStack): String {
        val output = ByteArrayOutputStream()
        BukkitObjectOutputStream(output).use { it.writeObject(item) }
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    private fun decodeItem(raw: String): ItemStack? = runCatching {
        val bytes = Base64.getDecoder().decode(raw)
        BukkitObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as? ItemStack }
    }.getOrNull()
}
