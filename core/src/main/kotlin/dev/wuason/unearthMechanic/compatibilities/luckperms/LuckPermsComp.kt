package dev.wuason.unearthMechanic.compatibilities.luckperms

import dev.wuason.unearthMechanic.UnearthMechanic
import net.luckperms.api.LuckPermsProvider
import org.bukkit.entity.Player

class LuckPermsComp(private val core: UnearthMechanic) {


    init {
        core.logger.info("Luckperms found! Enabling compatibility...")
    }

    fun hasPermission(player: Player, permission: String): Boolean {
        val user = LuckPermsProvider.get().getPlayerAdapter<Player?>(Player::class.java).getUser(player)
        return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean()
    }
}