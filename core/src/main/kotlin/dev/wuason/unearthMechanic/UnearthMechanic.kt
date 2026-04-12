package dev.wuason.unearthMechanic

import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.CommandAPIPaperConfig
import dev.wuason.adapter.Adapter
import dev.wuason.unearthMechanic.compatibilities.craftengine.CraftEngineComp
import dev.wuason.unearthMechanic.compatibilities.craftengine.CraftEnginePlugin
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.*
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.ashes.AshesEnvironmentListener
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.fishtank.FishTankBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.fishtank.FishTankChunkListener
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.fishtank.FishTankDataStore
import dev.wuason.unearthMechanic.compatibilities.craftengine.types.UneProperties
import dev.wuason.unearthMechanic.compatibilities.luckperms.LuckPermsComp
import dev.wuason.unearthMechanic.compatibilities.luckperms.LuckPermsPlugin
import dev.wuason.unearthMechanic.compatibilities.worldguard.WorldGuardComp
import dev.wuason.unearthMechanic.compatibilities.worldguard.WorldGuardPlugin
import dev.wuason.unearthMechanic.config.ConfigManager
import dev.wuason.unearthMechanic.system.IStageManager
import dev.wuason.unearthMechanic.system.StageManager
import dev.wuason.unearthMechanic.utils.AdventureUtils
import dev.wuason.unearthMechanic.utils.ItemRemoverManager
import net.momirealms.antigrieflib.AntiGriefLib
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
            "MythicCrucible",
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

    private var antiGriefLib: AntiGriefLib? = null

    override fun onLoad() {
        CommandAPI.onLoad(CommandAPIPaperConfig(this).verboseOutput(false))
        if(WorldGuardPlugin.isWorldGuardLoaded()) worldGuardComp = WorldGuardComp(this)
        if(LuckPermsPlugin.isLuckPermsLoaded()) luckPermsComb = LuckPermsComp(this)
        if(CraftEnginePlugin.isCraftEngineLoaded()) craftEngineComb = CraftEngineComp(this)
    }

    lateinit var ashesEnvironmentListener: AshesEnvironmentListener
        private set

    override fun onEnable(){

        AdventureUtils.sendMessagePluginConsole(this, " <gold>Starting UnearthMechanic...")
        AdventureUtils.sendMessagePluginConsole(this, "<gray>-----------------------------------------------------------")
        AdventureUtils.sendMessagePluginConsole(this, "<gray>-----------------------------------------------------------")
        AdventureUtils.sendMessagePluginConsole(this, "<gold>                         $name");
        AdventureUtils.sendMessagePluginConsole(this, "");
        AdventureUtils.sendMessagePluginConsole(this, "<gold> Selected compatibility: <aqua>${checkCompatibility()}");

        //if (check()) return
        Adapter.init(this);
        configManager = ConfigManager(this)
        configManager.loadConfig()

        CommandAPI.onEnable()
        commandManager = CommandManager(this)
        commandManager.loadCommands()

        stageManager = StageManager(this)

        Bukkit.getPluginManager().registerEvents(ItemRemoverManager(this), this)

        if (CraftEnginePlugin.isCraftEngineEnabled()) {
            onCraftEngineReady();
        }

        AdventureUtils.sendMessagePluginConsole(this, "<gray>-----------------------------------------------------------")
        AdventureUtils.sendMessagePluginConsole(this, "<gray>-----------------------------------------------------------")

    }

    private fun onCraftEngineReady() {
        logger.info("CraftEngine is ready. Loading CraftEngine compatibility...")

        try {
            BlockBehaviors.register(Key.from("painter:column_block"), ColumnBlockBehavior.FACTORY)
            BlockBehaviors.register(Key.from("painter:window_connect_tile"), WindowConnectTileBehavior.FACTORY)
            BlockBehaviors.register(Key.from("painter:sofa_connect_tile"), SofaConnectTileBehavior.FACTORY)
            BlockBehaviors.register(Key.from("painter:fish_tank"), FishTankBehavior.FACTORY)
            BlockBehaviors.register(Key.from("painter:ashes_merge"), AshesMergeBehavior.FACTORY)
            BlockBehaviors.register(Key.from("painter:curtain_block"), CurtainBlockBehavior.FACTORY)

            UneProperties.registerAll()
            FishTankDataStore.load()

            Bukkit.getScheduler().runTaskLater(this, Runnable {
                FishTankBehavior.ensureTaskRunning()
                FishTankBehavior.resyncAllLoadedAquariums()
                Bukkit.getPluginManager().registerEvents(FishTankChunkListener(), this)

                ashesEnvironmentListener = AshesEnvironmentListener(this)
                server.pluginManager.registerEvents(ashesEnvironmentListener, this)
            }, 40L)
        } catch (t: Throwable) {
            logger.severe("[UnearthMechanic] CraftEngine hook failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    override fun onDisable() {
        CommandAPI.onDisable()
        if(CraftEnginePlugin.isCraftEngineEnabled()){
            FishTankDataStore.flushSaveNow()
        }
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

    fun getAntiGriefLib(): AntiGriefLib {
        if (antiGriefLib == null) {
            antiGriefLib = AntiGriefLib.builder(this)
                .ignoreOP(true)
                .silentLogs(false)
                .build()
        }
        return antiGriefLib!!
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
