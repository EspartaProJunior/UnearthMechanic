package dev.wuason.unearthMechanic.config

import dev.wuason.libs.adapter.AdapterData


/**
 * Represents a generic tool with specific attributes and behaviors.
 * This interface defines the contract that any tool implementation must follow
 * in order to interact properly within the system.
 */
interface ITool {

    /**
     * Retrieves adapter data as a string representation.
     *
     * @return A `String` containing adapter-specific data.
     */
    fun getAdapterData(): AdapterData

    /**
     * Retrieves the size attribute of the tool.
     *
     * @return The size as an `Int`.
     */
    fun getSize(): Int

    /**
     * Retrieves the depth value associated with the tool.
     *
     * @return The depth value as an integer.
     */
    fun getDeep(): Int

    /**
     * Retrieves the depth value of the tool.
     *
     * @return the depth as an Int.
     */
    fun getDepth(): Int

    /**
     * Determines if the tool can execute multiple interactions or processes simultaneously.
     *
     * @return `true` if the tool supports multiple interactions, `false` otherwise.
     */
    fun isMultiple(): Boolean

    /**
     * Retrieves the sound associated with the tool.
     *
     * @return The `ISound` object representing the sound, or `null` if no sound is associated.
     */
    fun getSound(): ISound?

    /**
     * Retrieves the animation associated with the tool.
     *
     * @return The IAnimation instance representing the animation for the tool,
     * or null if no animation is associated.
     */
    fun getAnimation(): IAnimation?

    /**
     * Retrieves the replacement item identifier when the tool is using
     *
     * @return The permission of the item to replace the broken tool, or null if no replacement is specified.
     */
    fun getToolPermission(): String?

    /**
     * Retrieves the delay in milliseconds associated with the tool.
     *
     * @return The delay in milliseconds as a `Long`.
     */
    fun getDelay(): Long

    /**
     * Retrieves the replacement item identifier when the tool breaks.
     *
     * @return The identifier of the item to replace the broken tool, or null if no replacement is specified.
     */
    fun getReplaceOnBreak(): String?

    /**
     * Retrieves the color identifier that will be applied when the tool is used.
     *
     * @return The identifier of the element that will give an rgb for the substitution or null if no substitution is specified.
     */
    fun getTintFurniture(): String?
}