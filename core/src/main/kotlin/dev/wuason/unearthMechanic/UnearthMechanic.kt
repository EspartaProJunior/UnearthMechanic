package dev.wuason.unearthMechanic

import dev.wuason.mechanics.utils.AdventureUtils
import dev.wuason.unearthMechanic.compatibilities.craftengine.CraftEngineComp
import dev.wuason.unearthMechanic.compatibilities.craftengine.CraftEnginePlugin
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.ColumnBlockBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.WindowConnectTileBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.types.UneProperties
import dev.wuason.unearthMechanic.compatibilities.luckperms.LuckPermsComp
import dev.wuason.unearthMechanic.compatibilities.luckperms.LuckPermsPlugin
import dev.wuason.unearthMechanic.compatibilities.worldguard.WorldGuardComp
import dev.wuason.unearthMechanic.compatibilities.worldguard.WorldGuardPlugin
import dev.wuason.unearthMechanic.config.ConfigManager
import dev.wuason.unearthMechanic.system.IStageManager
import dev.wuason.unearthMechanic.system.StageManager
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors
import net.momirealms.craftengine.core.util.Key
import org.bukkit.Bukkit

class UnearthMechanic : UnearthMechanicPlugin() {

    companion object {

        val COMPATIBILITIES: Array<String> = arrayOf(
            "ItemsAdder",
            "Oraxen",
            "Nexo",
            "CraftEngine",
            "Vanilla"
        )

        private lateinit var instance: UnearthMechanic

        fun getInstance(): UnearthMechanic {
            return instance
        }
    }

    init {
        instance = this
    }

    private lateinit var commandManager: CommandManager
    private lateinit var configManager: ConfigManager
    private lateinit var stageManager: StageManager

    private lateinit var worldGuardComp: WorldGuardComp
    private lateinit var luckPermsComb: LuckPermsComp
    private lateinit var craftEngineComb: CraftEngineComp

    override fun onMechanicLoad() {
        if (WorldGuardPlugin.isWorldGuardLoaded()) worldGuardComp = WorldGuardComp(this)
        if(LuckPermsPlugin.isLuckPermsLoaded()) luckPermsComb = LuckPermsComp(this)
        if(CraftEnginePlugin.isCraftEngineLoaded()) craftEngineComb = CraftEngineComp(this)
    }

    override fun onMechanicEnable() {

        AdventureUtils.sendMessagePluginConsole(this, " <gold>Starting UnearthMechanic...")
        AdventureUtils.sendMessagePluginConsole(this, "<gray>-----------------------------------------------------------")
        AdventureUtils.sendMessagePluginConsole(this, "<gray>-----------------------------------------------------------")
        AdventureUtils.sendMessagePluginConsole(this, "<gold>                         $name");
        AdventureUtils.sendMessagePluginConsole(this, "");
        AdventureUtils.sendMessagePluginConsole(this, "<gold> Selected compatibility: <aqua>${checkCompatibility()}");

        //if (check()) return

        configManager = ConfigManager(this)
        configManager.loadConfig()

        commandManager = CommandManager(this)
        commandManager.loadCommands()

        stageManager = StageManager(this)
        if(CraftEnginePlugin.isCraftEngineEnabled()){
            BlockBehaviors.register(
                Key.from("painter:column_block"),
                ColumnBlockBehavior.FACTORY
            )
            BlockBehaviors.register(
                Key.from("painter:window_connect_tile"),
                WindowConnectTileBehavior.FACTORY
            )
            UneProperties.registerAll()

            logger.info("Registered ColumnBlockBehavior for painter:column_block")
            logger.info("Registered WindowConnectTileBehavior for painter:window_connect_tile")
        }
        AdventureUtils.sendMessagePluginConsole(this, "<gray>-----------------------------------------------------------")
        AdventureUtils.sendMessagePluginConsole(this, "<gray>-----------------------------------------------------------")

    }

    override fun onMechanicDisable() {

    }

    override fun getConfigManager(): ConfigManager {
        return configManager
    }

    override fun getCommandManager(): CommandManager {
        return commandManager
    }

    override fun getStageManager(): IStageManager {
        return stageManager
    }

    fun getWorldGuardComp(): WorldGuardComp {
        return worldGuardComp
    }

    fun getLuckPermsComb(): LuckPermsComp {
        return luckPermsComb
    }

    fun getCraftEngineComb(): CraftEngineComp {
        return craftEngineComb
    }

    fun checkCompatibility(): String? {
        for (compatibility in COMPATIBILITIES) {
            if (Bukkit.getPluginManager().getPlugin(compatibility) != null) {
                return compatibility
            }
        }
        return null
    }

    private fun check(): Boolean {
        if (checkCompatibility() == null) {
            logger.severe("-----------------------------------------------------------")
            logger.severe("-----------------------------------------------------------")
            logger.severe("                 UnearthMechanic is disabled               ")
            logger.severe("       None of the required dependencies were detected     ")
            logger.severe("      " + COMPATIBILITIES.joinToString(" or ") + " are required")
            logger.severe("-----------------------------------------------------------")
            logger.severe("-----------------------------------------------------------")
            Bukkit.getPluginManager().disablePlugin(this)
            return true
        }
        return false
    }

}
