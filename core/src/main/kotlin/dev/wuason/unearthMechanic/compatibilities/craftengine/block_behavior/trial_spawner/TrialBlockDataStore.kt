package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.trial_spawner

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.UUID

/**
 * Small synchronous store. Opens and combat completions are rare, so saving immediately is
 * preferable to losing vault/cooldown data on a crash. All calls are made on the server thread.
 */
object TrialBlockDataStore {
    private lateinit var file: File
    private lateinit var yaml: YamlConfiguration
    private var loaded = false

    private fun ensureLoaded(plugin: Plugin) {
        if (loaded) return
        plugin.dataFolder.mkdirs()
        file = File(plugin.dataFolder, "trial-blocks.yml")
        yaml = YamlConfiguration.loadConfiguration(file)
        loaded = true
    }

    fun openedPlayers(plugin: Plugin, key: BlockPosKey): MutableList<UUID> {
        ensureLoaded(plugin)
        return yaml.getStringList("vaults.${key.encoded()}.opened")
            .mapNotNull { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            .toMutableList()
    }

    fun saveOpenedPlayers(plugin: Plugin, key: BlockPosKey, players: List<UUID>) {
        ensureLoaded(plugin)
        yaml.set("vaults.${key.encoded()}.opened", players.map(UUID::toString))
        save()
    }

    fun cooldownEnd(plugin: Plugin, key: BlockPosKey): Long {
        ensureLoaded(plugin)
        return yaml.getLong("spawners.${key.encoded()}.cooldown-end", 0L)
    }

    fun saveCooldownEnd(plugin: Plugin, key: BlockPosKey, epochMillis: Long) {
        ensureLoaded(plugin)
        yaml.set("spawners.${key.encoded()}.cooldown-end", epochMillis.takeIf { it > 0L })
        save()
    }

    fun remove(plugin: Plugin, key: BlockPosKey) {
        ensureLoaded(plugin)
        yaml.set("vaults.${key.encoded()}", null)
        yaml.set("spawners.${key.encoded()}", null)
        save()
    }

    private fun save() {
        runCatching { yaml.save(file) }
    }
}

data class BlockPosKey(
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int
) {
    fun encoded(): String = "${worldId}_${x}_${y}_${z}"
}
