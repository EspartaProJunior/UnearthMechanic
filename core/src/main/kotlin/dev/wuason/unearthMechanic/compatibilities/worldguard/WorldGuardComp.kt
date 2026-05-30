package dev.wuason.unearthMechanic.compatibilities.worldguard

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry
import dev.wuason.unearthMechanic.UnearthMechanic
import org.bukkit.Location
import org.bukkit.entity.Player

class WorldGuardComp(private val core: UnearthMechanic) {

    companion object {
        private const val INTERACT_FLAG = "unearth-interact"
        private const val ASHES_SPAWN_FLAG = "unearth-ashes-spawn"
    }

    private var unearthInteractFlag: StateFlag? = null
    private var unearthAshesSpawnFlag: StateFlag? = null

    init {
        core.logger.info("WorldGuard found! Enabling compatibility...")
        val registry = WorldGuard.getInstance().flagRegistry

        unearthInteractFlag = registerStateFlag(registry, INTERACT_FLAG, false)
        unearthAshesSpawnFlag = registerStateFlag(registry, ASHES_SPAWN_FLAG, true)
        /*try {
            val flag = StateFlag(INTERACT_FLAG, false)
            registry.register(flag)
            unearthInteractFlag = flag
        } catch (e: FlagConflictException) {
            val existing = registry[INTERACT_FLAG]
            if (existing is StateFlag) {
                unearthInteractFlag = existing
            } else {
                throw RuntimeException("Another plugin is using the flag name " + INTERACT_FLAG)
            }
        }*/
    }

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

    fun canInteractCustom(player: Player, target: Location?): Boolean {
        if (target == null) return true

        val flag = unearthInteractFlag ?: return true
        val localPlayer = WorldGuardPlugin.inst().wrapPlayer(player)
        val world = BukkitAdapter.adapt(player.world)

        val hasBypass = WorldGuard.getInstance().platform.sessionManager.hasBypass(
            localPlayer,
            world
        )

        if (hasBypass) return true

        val query = WorldGuard.getInstance().platform.regionContainer.createQuery()
        val wgLocation = BukkitAdapter.adapt(target)
        val regions = query.getApplicableRegions(wgLocation)

        // Outside the regions: allow normal interaction
        if (regions.size() == 0) {
            return true
        }

        // If the unearth-interact flag is explicitly set in a region,
        // WorldGuard must decide
        val hasExplicitUnearthFlag = regions.regions.any { region ->
            region.getFlag(flag) != null
        }

        if (hasExplicitUnearthFlag) {
            return regions.testState(localPlayer, flag)
        }

        // If the unearth-interact flag does NOT exist in the region,
        // automatically grant access to owners and members.
        val isMemberOrOwner = regions.regions.any { region ->
            region.isOwner(localPlayer) || region.isMember(localPlayer)
        }

        if (isMemberOrOwner) {
            return true
        }

        // If you are not a member or owner and there is no explicit flag, deny access.
        return false
    }

    fun canInteract(player: Player, target: Location?): Boolean {
        if (target == null) return true

        val localPlayer = WorldGuardPlugin.inst().wrapPlayer(player)
        val query = WorldGuard.getInstance().platform.regionContainer.createQuery()
        val wgLocation = BukkitAdapter.adapt(target)

        val hasBypass = WorldGuard.getInstance().platform.sessionManager.hasBypass(
            localPlayer,
            BukkitAdapter.adapt(player.world)
        )

        if (hasBypass) return true

        val regions = query.getApplicableRegions(wgLocation)

        // Outside regions: allow normal interaction.
        if (regions.size() == 0) {
            return true
        }

        // Within the region: follow the standard INTERACT procedure.
        return regions.testState(localPlayer, Flags.INTERACT)
    }

    fun canSpawnAshes(target: Location?): Boolean {
        if (target == null) return false

        val flag = unearthAshesSpawnFlag ?: return true

        return WorldGuard.getInstance().platform.regionContainer.createQuery()
            .testState(BukkitAdapter.adapt(target), null, flag)
    }
}