package dev.wuason.unearthMechanic.compatibilities.worldguard

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry
import dev.wuason.unearthMechanic.UnearthMechanic
import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * WorldGuard compatibility. Delegates all region decisions to WorldGuard's RegionQuery
 * so global/region priority, inheritance, membership and bypass are handled natively.
 */
class WorldGuardComp(private val core: UnearthMechanic) {

    companion object {
        private const val INTERACT_FLAG = "unearth-interact"
        private const val ASHES_SPAWN_FLAG = "unearth-ashes-spawn"
    }

    // Default false so an unset flag resolves to null (not set) rather than a forced allow/deny.
    private var unearthInteractFlag: StateFlag? = null

    // Default true: ashes spawn unless a region denies it.
    private var unearthAshesSpawnFlag: StateFlag? = null

    init {
        core.logger.info("WorldGuard found! Enabling compatibility...")
        val registry = WorldGuard.getInstance().flagRegistry

        unearthInteractFlag = registerStateFlag(registry, INTERACT_FLAG, false)
        unearthAshesSpawnFlag = registerStateFlag(registry, ASHES_SPAWN_FLAG, true)
    }

    private fun regionQuery() =
        WorldGuard.getInstance().platform.regionContainer.createQuery()

    private fun registerStateFlag(
        registry: FlagRegistry,
        name: String,
        defaultValue: Boolean
    ): StateFlag {
        return try {
            val flag = StateFlag(name, defaultValue)
            registry.register(flag)
            flag
        } catch (e: FlagConflictException) {
            val existing = registry[name]
            if (existing is StateFlag) {
                existing
            } else {
                throw RuntimeException("Another plugin is using the flag name $name")
            }
        }
    }

    /**
     * Whether [player] may stage/modify a block at [target].
     *
     * Order: WorldGuard bypass, then the `unearth-interact` flag if set, otherwise
     * WorldGuard's native build protection.
     */
    fun canModify(player: Player, target: Location?): Boolean {
        if (target == null) return true

        val flag = unearthInteractFlag ?: return true
        val localPlayer = WorldGuardPlugin.inst().wrapPlayer(player)

        if (WorldGuard.getInstance().platform.sessionManager
                .hasBypass(localPlayer, BukkitAdapter.adapt(player.world))) {
            return true
        }

        val query = regionQuery()
        val wgLocation = BukkitAdapter.adapt(target)

        // Honor an explicit unearth-interact flag; null means it isn't set anywhere applicable.
        val override = query.queryValue(wgLocation, localPlayer, flag)
        if (override != null) {
            return override == StateFlag.State.ALLOW
        }

        // No flag set: fall back to the normal "can this player build here?" check.
        return query.testBuild(wgLocation, localPlayer)
    }

    /** Whether ashes may spawn at [target], gated by the `unearth-ashes-spawn` flag (default allow). */
    fun canSpawnAshes(target: Location?): Boolean {
        if (target == null) return false

        val flag = unearthAshesSpawnFlag ?: return true

        return regionQuery().testState(BukkitAdapter.adapt(target), null, flag)
    }
}
