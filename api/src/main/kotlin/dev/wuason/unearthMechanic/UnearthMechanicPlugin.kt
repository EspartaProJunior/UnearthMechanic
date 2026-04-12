package dev.wuason.unearthMechanic

import dev.wuason.unearthMechanic.config.IConfigManager
import dev.wuason.unearthMechanic.system.IStageManager
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

/**
 * UnearthMechanicPlugin serves as an abstract base class for managing core components
 * such as configuration, commands, and stages within the plugin.
 * It extends MechanicAddon and provides infrastructure for plugin lifecycle and component management.
 */
abstract class UnearthMechanicPlugin : JavaPlugin() {
    /**
     * This companion object holds a singleton instance of the UnearthMechanicPlugin and provides a method to access it.
     */
    companion object {
        /**
         * Holds the singleton instance of `UnearthMechanicPlugin`.
         * This variable is initialized when an instance of `UnearthMechanicPlugin` is created.
         * This instance can be accessed globally through the `getInstance` method provided by the companion object.
         */
        private lateinit var instance: UnearthMechanicPlugin

        /**
         * Returns the singleton instance of the UnearthMechanicPlugin.
         *
         * @return The UnearthMechanicPlugin singleton instance.
         */
        fun getInstance(): UnearthMechanicPlugin {
            return instance
        }
    }

    init {
        this.also { instance = it }
        val item: ItemStack? = null
    }

    /**
     * Retrieves the configuration manager responsible for managing configuration settings within the plugin.
     *
     * @return An instance of IConfigManager that provides methods to load configuration,
     * validate base item IDs and tools, and retrieve generics data.
     */
    abstract fun getConfigManager(): IConfigManager

    /**
     * Retrieves the command manager responsible for managing command registrations and executions.
     *
     * @return An instance of `ICommandManager` which handles the command-related functionalities for the plugin.
     */
    abstract fun getCommandManager(): ICommandManager

    /**
     * Retrieves the instance of `IStageManager` associated with this plugin.
     *
     * @return An instance of `IStageManager` that manages stage-related functionalities.
     */
    abstract fun getStageManager(): IStageManager


}