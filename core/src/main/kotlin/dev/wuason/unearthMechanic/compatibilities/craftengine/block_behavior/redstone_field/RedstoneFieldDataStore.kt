package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field

import com.tcoded.folialib.wrapper.task.WrappedTask
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.utils.FoliaUtils
import org.bukkit.Bukkit
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object RedstoneFieldDataStore {
    private val plugin get() = UnearthMechanic.getInstance()

    private val dbFile: File by lazy {
        File(plugin.dataFolder, "internal-data/redstone-field-data").also {
            it.parentFile?.mkdirs()
        }
    }

    private lateinit var connection: Connection

    private val fieldKeys = ConcurrentHashMap.newKeySet<SourceKey>()
    private val resonatorKeys = ConcurrentHashMap.newKeySet<SourceKey>()

    private val keysToSave = ConcurrentHashMap.newKeySet<SourceKey>()
    private val keysToDelete = ConcurrentHashMap.newKeySet<SourceKey>()
    private val dirty = AtomicBoolean(false)

    @Volatile
    private var saveTask: WrappedTask? = null

    fun load() {
        initDatabase()

        fieldKeys.clear()
        resonatorKeys.clear()
        keysToSave.clear()
        keysToDelete.clear()

        loadFromDatabase()
        dirty.set(false)
    }

    private fun initDatabase() {
        Class.forName("org.h2.Driver")

        val url = "jdbc:h2:${dbFile.absolutePath};MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_ON_EXIT=FALSE"

        connection = DriverManager.getConnection(url)
        connection.autoCommit = true

        connection.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS redstone_field_blocks (
                    world_name VARCHAR(128) NOT NULL,
                    x INT NOT NULL,
                    y INT NOT NULL,
                    z INT NOT NULL,
                    kind VARCHAR(32) NOT NULL,
                    updated_at BIGINT NOT NULL,
                    PRIMARY KEY (world_name, x, y, z, kind)
                )
                """.trimIndent()
            )

            st.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_redstone_field_blocks_world_kind
                ON redstone_field_blocks(world_name, kind)
                """.trimIndent()
            )
        }
    }

    private fun loadFromDatabase() {
        connection.prepareStatement(
            """
            SELECT world_name, x, y, z, kind
            FROM redstone_field_blocks
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val kind = sourceKind(rs.getString("kind")) ?: continue
                    val key = SourceKey(
                        world = rs.getString("world_name"),
                        x = rs.getInt("x"),
                        y = rs.getInt("y"),
                        z = rs.getInt("z"),
                        kind = kind
                    )

                    when (kind) {
                        SourceKind.REDSTONE -> fieldKeys.add(key)
                        SourceKind.RESONATOR -> resonatorKeys.add(key)
                    }
                }
            }
        }
    }

    fun fields(): Set<SourceKey> = fieldKeys.toSet()

    fun resonators(): Set<SourceKey> = resonatorKeys.toSet()

    fun addField(key: SourceKey) {
        if (fieldKeys.add(key)) markSave(key)
    }

    fun removeField(key: SourceKey) {
        if (fieldKeys.remove(key)) markDelete(key)
    }

    fun addResonator(key: SourceKey) {
        if (resonatorKeys.add(key)) markSave(key)
    }

    fun removeResonator(key: SourceKey) {
        if (resonatorKeys.remove(key)) markDelete(key)
    }

    private fun markSave(key: SourceKey) {
        keysToDelete.remove(key)
        keysToSave.add(key)
        scheduleSave()
    }

    private fun markDelete(key: SourceKey) {
        keysToSave.remove(key)
        keysToDelete.add(key)
        scheduleSave()
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
                    "[RedstoneFieldDataStore] save failed: ${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
    }

    fun flushSaveNow() {
        if (!::connection.isInitialized) return

        val saveSnapshot = keysToSave.toList()
        val deleteSnapshot = keysToDelete.toList()

        if (saveSnapshot.isEmpty() && deleteSnapshot.isEmpty()) {
            dirty.set(false)
            return
        }

        connection.autoCommit = false

        try {
            if (deleteSnapshot.isNotEmpty()) {
                deleteKeys(deleteSnapshot)
            }

            if (saveSnapshot.isNotEmpty()) {
                saveKeys(saveSnapshot)
            }

            connection.commit()

            for (key in saveSnapshot) keysToSave.remove(key)
            for (key in deleteSnapshot) keysToDelete.remove(key)

            dirty.set(keysToSave.isNotEmpty() || keysToDelete.isNotEmpty())
        } catch (t: Throwable) {
            connection.rollback()

            Bukkit.getLogger().warning(
                "[RedstoneFieldDataStore] H2 save failed: ${t.javaClass.simpleName}: ${t.message}"
            )
        } finally {
            connection.autoCommit = true
        }
    }

    private fun saveKeys(keys: Collection<SourceKey>) {
        connection.prepareStatement(
            """
            MERGE INTO redstone_field_blocks (
                world_name,
                x,
                y,
                z,
                kind,
                updated_at
            )
            KEY(world_name, x, y, z, kind)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            val now = System.currentTimeMillis()

            for (key in keys) {
                ps.setString(1, key.world)
                ps.setInt(2, key.x)
                ps.setInt(3, key.y)
                ps.setInt(4, key.z)
                ps.setString(5, key.kind.name)
                ps.setLong(6, now)
                ps.addBatch()
            }

            ps.executeBatch()
        }
    }

    private fun deleteKeys(keys: Collection<SourceKey>) {
        connection.prepareStatement(
            """
            DELETE FROM redstone_field_blocks
            WHERE world_name = ?
              AND x = ?
              AND y = ?
              AND z = ?
              AND kind = ?
            """.trimIndent()
        ).use { ps ->
            for (key in keys) {
                ps.setString(1, key.world)
                ps.setInt(2, key.x)
                ps.setInt(3, key.y)
                ps.setInt(4, key.z)
                ps.setString(5, key.kind.name)
                ps.addBatch()
            }

            ps.executeBatch()
        }
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
                "[RedstoneFieldDataStore] close flush failed: ${t.javaClass.simpleName}: ${t.message}"
            )
        }

        if (::connection.isInitialized) {
            try {
                if (!connection.isClosed) connection.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun sourceKind(raw: String?): SourceKind? {
        return SourceKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}