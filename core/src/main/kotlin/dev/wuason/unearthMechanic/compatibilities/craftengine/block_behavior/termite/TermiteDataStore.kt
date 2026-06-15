package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite

import com.tcoded.folialib.wrapper.task.WrappedTask
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.utils.FoliaUtils
import org.bukkit.Bukkit
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap

object TermiteDataStore {

    private val plugin get() = UnearthMechanic.getInstance()
    private val dbFile: File by lazy {
        File(plugin.dataFolder, "internal-data/termite-data").also {
            it.parentFile?.mkdirs()
        }
    }

    data class ColonyData(
        var termites: Int = 0,
        var food: Int = 0,
        var ownerUuid: String? = null,
        var friendlyAt: Long = 0L,
        var updatedAt: Long = 0L
    )

    private lateinit var connection: Connection
    private val cache = ConcurrentHashMap<String, ColonyData>()
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
            SELECT colony_key, termites, food, owner_uuid, friendly_at, updated_at
            FROM termite_colonies
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    cache[rs.getString("colony_key")] = ColonyData(
                        termites = rs.getInt("termites"),
                        food = rs.getInt("food"),
                        ownerUuid = rs.getString("owner_uuid"),
                        friendlyAt = rs.getLong("friendly_at"),
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
                CREATE TABLE IF NOT EXISTS termite_colonies (
                    colony_key VARCHAR(256) NOT NULL PRIMARY KEY,
                    termites INT NOT NULL,
                    food INT NOT NULL,
                    owner_uuid VARCHAR(36),
                    friendly_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    fun get(key: String): ColonyData =
        cache.computeIfAbsent(key) { ColonyData(updatedAt = System.currentTimeMillis()) }

    fun peek(key: String): ColonyData? =
        cache[key]

    fun addTermites(key: String, amount: Int, max: Int): Int {
        val d = get(key)
        val accepted = amount.coerceAtMost((max - d.termites).coerceAtLeast(0))
        if (accepted <= 0) return 0

        d.termites += accepted
        touch(key, d)
        return accepted
    }

    fun takeTermite(key: String): Boolean {
        val d = cache[key] ?: return false
        if (d.termites <= 0) return false

        d.termites--
        touch(key, d)
        return true
    }

    fun addFood(key: String, amount: Int, max: Int): Int {
        val d = get(key)
        val accepted = amount.coerceAtMost((max - d.food).coerceAtLeast(0))
        if (accepted <= 0) return 0

        d.food += accepted
        touch(key, d)
        return accepted
    }

    fun takeFood(key: String, amount: Int = 1): Int {
        val d = cache[key] ?: return 0
        val taken = amount.coerceAtMost(d.food.coerceAtLeast(0))
        if (taken <= 0) return 0

        d.food -= taken
        touch(key, d)
        return taken
    }

    fun markFriendly(key: String, ownerUuid: String, now: Long = System.currentTimeMillis()) {
        val d = get(key)
        d.ownerUuid = ownerUuid
        d.friendlyAt = now
        touch(key, d)
    }

    fun remove(key: String) {
        cache.remove(key)
        dirtyKeys.remove(key)
        deletedKeys.add(key)
        scheduleSave()
    }

    private fun touch(key: String, d: ColonyData) {
        d.updatedAt = System.currentTimeMillis()
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
            connection.prepareStatement("DELETE FROM termite_colonies WHERE colony_key = ?").use { ps ->
                for (key in toDelete) {
                    ps.setString(1, key)
                    ps.addBatch()
                }
                ps.executeBatch()
            }

            connection.prepareStatement(
                """
                MERGE INTO termite_colonies (
                    colony_key, termites, food, owner_uuid, friendly_at, updated_at
                )
                KEY(colony_key)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                for (key in toSave) {
                    val d = cache[key] ?: continue
                    ps.setString(1, key)
                    ps.setInt(2, d.termites)
                    ps.setInt(3, d.food)
                    ps.setString(4, d.ownerUuid)
                    ps.setLong(5, d.friendlyAt)
                    ps.setLong(6, d.updatedAt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }

            connection.commit()
            toDelete.forEach { deletedKeys.remove(it) }
            toSave.forEach { dirtyKeys.remove(it) }
        } catch (t: Throwable) {
            connection.rollback()
            Bukkit.getLogger().warning("[TermiteDataStore] save failed: ${t.javaClass.simpleName}: ${t.message}")
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
}