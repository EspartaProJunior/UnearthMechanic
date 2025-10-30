package dev.wuason.unearthMechanic.compatibilities.craftengine

import dev.wuason.unearthMechanic.UnearthMechanic
import net.luckperms.api.LuckPermsProvider
import org.bukkit.entity.Player

class CraftEngineComp(private val core: UnearthMechanic) {

    init {
        core.logger.info("CraftEngine found! Enabling compatibility...")
    }
}