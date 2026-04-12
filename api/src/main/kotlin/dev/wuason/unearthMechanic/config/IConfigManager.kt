package dev.wuason.unearthMechanic.config

import dev.wuason.adapter.AdapterData

/**
 * Represents a manager responsible for handling configuration settings.
 * Provides methods to load configurations, validate base item IDs and tools, and retrieve generics data.
 */
interface IConfigManager {
    /**
     * Loads the configuration settings from a predefined source.
     *
     * This method is typically called to initialize or refresh the configuration data used by the application.
     * Implementations may vary in terms of where and how they load the configuration, such as from
     * a file, a database, or an external service.
     */
    fun loadConfig()
    /**
     * Retrieves a map of generics with their associated string keys.
     *
     * @return A hashmap where the keys are strings representing generic identifiers,
     *         and the values are instances of IGeneric.
     */
    fun getGenerics(): HashMap<String, IGeneric>
    /**
     * Retrieves a nested HashMap structure mapping AdapterData to another HashMap,
     * where the inner HashMap maps AdapterData keys to IGeneric values.
     *
     * @return A HashMap where the outer keys are AdapterData instances,
     *         the values are HashMaps mapping AdapterData to IGeneric instances.
     */
    fun getGenericsBaseItemId(): HashMap<AdapterData, HashMap<AdapterData, IGeneric>>
    /**
     * Validates whether the given base item ID is valid.
     *
     * @param baseItemId The identifier of the base item to validate.
     * @return True if the base item ID is valid, false otherwise.
     */
    fun validBaseItemId(baseAdapterData: AdapterData): Boolean
    /**
     * Validates whether a given tool is compatible with a specific base item ID.
     *
     * @param baseItemId The base item to be validated against.
     * @param tool The tool to be checked for compatibility with the base item.
     * @return `true` if the tool is compatible with the base item ID, `false` otherwise.
     */
    fun validTool(baseAdapterData: AdapterData, tool: AdapterData): Boolean
    /**
     * Retrieves a generic instance associated with the provided base item ID and tool.
     *
     * This method is useful for obtaining an IGeneric object based on a specific
     * combination of base item ID and tool. It may return null if no corresponding
     * generic is found for the input parameters.
     *
     * @param baseItemId The base identifier of the item, represented as an AdapterData object.
     * @param tool The tool associated with the item, also represented as an AdapterData object.
     * @return The corresponding IGeneric object if found, or null if no match exists.
     */
    fun getGeneric(baseAdapterData: AdapterData, tool: AdapterData): IGeneric?
}