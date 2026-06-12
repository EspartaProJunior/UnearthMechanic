package dev.wuason.unearthMechanic

import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.CommandAPIPaperConfig
import dev.wuason.adapter.Adapter
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.BuddingAmethystBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.CraftEnginePlugin
import dev.wuason.unearthMechanic.compatibilities.craftengine.RedstoneFieldBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.RedstoneFieldManager
import dev.wuason.unearthMechanic.compatibilities.craftengine.TimedRedstoneRelayBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.*
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.ashes.AshesEnvironmentListener
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.ashes.BurnToAshesListener
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.fishtank.FishTankBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.fishtank.FishTankDataStore
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.meerkat_cache.MeerkatBurrowTask
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.meerkat_cache.MeerkatCacheDataStore
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.meerkat_cache.MeerkatCacheListener
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.meerkat_cache.MeerkatCacheSandBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.meerkat_cache.mythic.MeerkatCacheMythicSkillListener
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.mini_cubes.MiniCubesBlockBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.MultiSaplingBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.pointed_dripstone.PointedDripstoneBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.RedstoneFieldDataStore
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.RedstoneFieldResonatorBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite.TermiteComposterBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite.TermiteConsumptionTask
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite.TermiteDataStore
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite.TermiteHollowLogBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite.TermiteListener
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite.TermiteNestBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite.mythic.TermiteMythicSkillListener
import dev.wuason.unearthMechanic.compatibilities.craftengine.item_behavior.FrozenTotemOfUndyingItemBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.item_behavior.FrozenTotemOfUndyingListener
import dev.wuason.unearthMechanic.compatibilities.craftengine.item_behavior.ShiftPlaceBlockItemBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.item_behavior.TermiteBucketItemBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.item_behavior.blockspeed.BlockSpeedItemBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.item_behavior.blockspeed.BlockSpeedListener
import dev.wuason.unearthMechanic.compatibilities.craftengine.item_behavior.crop_accelerator.CropAcceleratorDispenserListener
import dev.wuason.unearthMechanic.compatibilities.craftengine.item_behavior.crop_accelerator.CropAcceleratorItemBehavior
import dev.wuason.unearthMechanic.compatibilities.craftengine.listeners.PointedDripstoneTridentListener
import dev.wuason.unearthMechanic.compatibilities.craftengine.types.UneKeys
import dev.wuason.unearthMechanic.compatibilities.craftengine.types.UneProperties
import dev.wuason.unearthMechanic.compatibilities.luckperms.LuckPermsComp
import dev.wuason.unearthMechanic.compatibilities.luckperms.LuckPermsPlugin
import dev.wuason.unearthMechanic.compatibilities.mythicmobs.MythicMobsPlugin
import dev.wuason.unearthMechanic.compatibilities.worldguard.WorldGuardComp
import dev.wuason.unearthMechanic.compatibilities.worldguard.WorldGuardPlugin
import dev.wuason.unearthMechanic.config.ConfigManager
import dev.wuason.unearthMechanic.system.IStageManager
import dev.wuason.unearthMechanic.system.PreviousBlockDataStore
import dev.wuason.unearthMechanic.system.StageManager
import dev.wuason.unearthMechanic.utils.AdventureUtils
import dev.wuason.unearthMechanic.utils.FoliaUtils
import dev.wuason.unearthMechanic.utils.ItemRemoverManager
import net.momirealms.antigrieflib.AntiGriefLib
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors
import net.momirealms.craftengine.core.util.Key
import org.bukkit.Bukkit
import org.bukkit.Material

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

    private var antiGriefLib: AntiGriefLib? = null

    override fun onLoad() {
        CommandAPI.onLoad(CommandAPIPaperConfig(this).verboseOutput(false))
        if(WorldGuardPlugin.isWorldGuardLoaded()) worldGuardComp = WorldGuardComp(this)
        if(LuckPermsPlugin.isLuckPermsLoaded()) luckPermsComb = LuckPermsComp(this)
    }

    lateinit var ashesEnvironmentListener: AshesEnvironmentListener
        private set

    override fun onEnable(){

        FoliaUtils.init(this)

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

        PreviousBlockDataStore.load()

        if (CraftEnginePlugin.isCraftEngineEnabled()) {
            onCraftEngineReady();
        }
        if (MythicMobsPlugin.isMythicMobsEnabled()) {
            server.pluginManager.registerEvents(TermiteMythicSkillListener(), this)
            server.pluginManager.registerEvents(MeerkatCacheMythicSkillListener(), this)
        }

        AdventureUtils.sendMessagePluginConsole(this, "<gray>-----------------------------------------------------------")
        AdventureUtils.sendMessagePluginConsole(this, "<gray>-----------------------------------------------------------")

    }

    private fun onCraftEngineReady() {
        AdventureUtils.sendMessagePluginConsole(this," <gray>CraftEngine is ready. <green>Loading CraftEngine compatibility<gray>...")

        try {
            // BlockBehaviors
            registerSafely(UneKeys.COLUMN_BLOCK_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.COLUMN_BLOCK_BEHAVIOR, ColumnBlockBehavior.FACTORY)
            }
            registerSafely(UneKeys.WINDOW_CONNECT_TILE_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.WINDOW_CONNECT_TILE_BEHAVIOR, WindowConnectTileBehavior.FACTORY)
            }
            registerSafely(UneKeys.SOFA_CONNECT_TILE_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.SOFA_CONNECT_TILE_BEHAVIOR, SofaConnectTileBehavior.FACTORY)
            }
            registerSafely(UneKeys.FISH_TANK_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.FISH_TANK_BEHAVIOR, FishTankBehavior.FACTORY)
            }
            registerSafely(UneKeys.ASHES_MERGE_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.ASHES_MERGE_BEHAVIOR, AshesMergeBehavior.FACTORY)
            }
            registerSafely(UneKeys.CURTAIN_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.CURTAIN_BEHAVIOR, CurtainBlockBehavior.FACTORY)
            }
            registerSafely(UneKeys.SHOWER_CURTAIN_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.SHOWER_CURTAIN_BEHAVIOR, ShowerCurtainBlockBehavior.FACTORY)
            }
            registerSafely(UneKeys.MINI_CUBES_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.MINI_CUBES_BEHAVIOR, MiniCubesBlockBehavior.FACTORY)
            }
            registerSafely(UneKeys.POINTED_DRIPSTONE_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.POINTED_DRIPSTONE_BEHAVIOR, PointedDripstoneBehavior.FACTORY)
            }
            server.pluginManager.registerEvents(
                PointedDripstoneTridentListener(
                    this,
                    Key.of("elitefantasy:ice_pointed_dripstone")
                ),
                this
            )
            registerSafely(UneKeys.AMETHYST_CRYSTAL_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.AMETHYST_CRYSTAL_BEHAVIOR, AmethystCrystalBehavior.FACTORY)
            }
            registerSafely(UneKeys.BUDDING_AMETHYST_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.BUDDING_AMETHYST_BEHAVIOR, BuddingAmethystBehavior.FACTORY)
            }
            registerSafely(UneKeys.BRITTLE_ICE_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.BRITTLE_ICE_BEHAVIOR, BrittleIceBehavior.FACTORY)
            }
            registerSafely(UneKeys.MULTIFACE_ATTACHED_BLOCK_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.MULTIFACE_ATTACHED_BLOCK_BEHAVIOR, MultifaceAttachedBlockBehavior.FACTORY)
            }
            registerSafely(UneKeys.TIMED_REDSTONE_RELAY_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.TIMED_REDSTONE_RELAY_BEHAVIOR, TimedRedstoneRelayBehavior.FACTORY)
            }
            registerSafely(UneKeys.WALL_BLOCK_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.WALL_BLOCK_BEHAVIOR, WideWallBlockBehavior.FACTORY)
            }
            registerSafely(UneKeys.REDSTONE_FIELD_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.REDSTONE_FIELD_BEHAVIOR, RedstoneFieldBehavior.FACTORY)
            }
            registerSafely(UneKeys.REDSTONE_FIELD_RESONATOR_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.REDSTONE_FIELD_RESONATOR_BEHAVIOR, RedstoneFieldResonatorBehavior.FACTORY)
            }

            registerSafely(UneKeys.TERMITE_NEST_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.TERMITE_NEST_BEHAVIOR, TermiteNestBehavior.FACTORY)
            }
            registerSafely(UneKeys.TERMITE_HOLLOW_LOG_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.TERMITE_HOLLOW_LOG_BEHAVIOR, TermiteHollowLogBehavior.FACTORY)
            }
            registerSafely(UneKeys.TERMITE_COMPOSTER_BEHAVIOR.asString()){
                BlockBehaviors.register(UneKeys.TERMITE_COMPOSTER_BEHAVIOR, TermiteComposterBehavior.FACTORY)
            }
            registerSafely(UneKeys.MEERKAT_CACHE_SAND_BEHAVIOR.asString()) {
                BlockBehaviors.register(UneKeys.MEERKAT_CACHE_SAND_BEHAVIOR, MeerkatCacheSandBehavior.FACTORY)
            }
            registerSafely(UneKeys.MULTI_SAPLING_BEHAVIOR.asString()) {
                BlockBehaviors.register(UneKeys.MULTI_SAPLING_BEHAVIOR, MultiSaplingBehavior.FACTORY)
            }

            // ItemBehaviors
            registerSafely(UneKeys.SHIFT_PLACE_BLOCK_BEHAVIOR.asString()) {
                ItemBehaviors.register(
                    UneKeys.SHIFT_PLACE_BLOCK_BEHAVIOR,
                    ShiftPlaceBlockItemBehavior.FACTORY
                )
            }
            registerSafely(UneKeys.TERMITE_BUCKET_ITEM_BEHAVIOR.asString()) {
                ItemBehaviors.register(
                    UneKeys.TERMITE_BUCKET_ITEM_BEHAVIOR,
                    TermiteBucketItemBehavior.FACTORY
                )
            }
            registerSafely(UneKeys.BLOCK_SPEED_ITEM_BEHAVIOR.asString()) {
                ItemBehaviors.register(
                    UneKeys.BLOCK_SPEED_ITEM_BEHAVIOR,
                    BlockSpeedItemBehavior.FACTORY
                )
            }
            registerSafely(UneKeys.CROP_ACCELERATOR_ITEM_BEHAVIOR.asString()) {
                ItemBehaviors.register(
                    UneKeys.CROP_ACCELERATOR_ITEM_BEHAVIOR,
                    CropAcceleratorItemBehavior.FACTORY
                )
            }
            registerSafely(UneKeys.FROZEN_TOTEM_OF_UNDYING_ITEM_BEHAVIOR.asString()) {
                ItemBehaviors.register(
                    UneKeys.FROZEN_TOTEM_OF_UNDYING_ITEM_BEHAVIOR,
                    FrozenTotemOfUndyingItemBehavior.FACTORY
                )
            }

            UneProperties.registerAll()
            FishTankDataStore.load()
            RedstoneFieldDataStore.load()
            TermiteDataStore.load()
            MeerkatCacheDataStore.load()

            FoliaUtils.runLater(40L) {
                ashesEnvironmentListener = AshesEnvironmentListener(this)

                server.pluginManager.registerEvents(BurnToAshesListener(this, ashesEnvironmentListener), this)
                server.pluginManager.registerEvents(ashesEnvironmentListener, this)

                server.pluginManager.registerEvents(BlockSpeedListener(this),this)

                server.pluginManager.registerEvents(CropAcceleratorDispenserListener(), this)

                server.pluginManager.registerEvents(FrozenTotemOfUndyingListener(), this)

                if (MythicMobsPlugin.isMythicMobsEnabled()) {
                    onMythicMobsReady();
                }

                runCatching {
                    FishTankBehavior.ensureTaskRunning()
                    FishTankBehavior.resyncAllLoadedAquariums()
                }.onFailure { t ->
                    logger.severe("FishTank init failed: ${t.javaClass.name}: ${t.message}")
                    t.printStackTrace()
                }
            }
        } catch (t: Throwable) {
            AdventureUtils.sendMessagePluginConsole(this, " <red>CraftEngine hook failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun onMythicMobsReady() {
        val termiteListener = TermiteListener(
            specialFriendItem = Material.HONEYCOMB
        )

        server.pluginManager.registerEvents(termiteListener, this)
        server.pluginManager.registerEvents(MeerkatCacheListener(), this)
        MeerkatBurrowTask.start()
        TermiteConsumptionTask.start(termiteListener)
    }

    private fun registerSafely(label: String, action: () -> Unit) {
        runCatching(action).onSuccess {
            AdventureUtils.sendMessagePluginConsole(this, " <gray>Registered <gold>$label")
        }.onFailure { error ->
            AdventureUtils.sendMessagePluginConsole(this, " <yellow>Skipped $label: ${error.message}")
        }
    }

    override fun onDisable() {
        CommandAPI.onDisable()
        if(CraftEnginePlugin.isCraftEngineEnabled()){
            FishTankDataStore.flushSaveNow()
            TermiteDataStore.flushSaveNow()
            MeerkatCacheDataStore.flushSaveNow()
            MeerkatBurrowTask.stop()
        }
        FishTankDataStore.close()
        PreviousBlockDataStore.close()
        RedstoneFieldManager.shutdown()

        TermiteConsumptionTask.stop()
        TermiteDataStore.close()
        MeerkatCacheDataStore.close()

        FoliaUtils.shutdown()
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
