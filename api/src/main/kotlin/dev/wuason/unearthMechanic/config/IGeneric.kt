package dev.wuason.unearthMechanic.config

import dev.wuason.adapter.AdapterData

/**
 * Represents a generic interface that provides various methods to access and manage generic items.
 */
interface IGeneric {

    /**
     * Retrieves the unique identifier associated with this instance.
     *
     * @return the ID as a string
     */
    fun getId(): String

    /**
     * Retrieves the set of tools associated with the generic item.
     *
     * @return A `Set` of `ITool` instances representing the tools.
     */
    fun getTools(): Set<ITool>

    /**
     * Retrieves the base stage of the current instance.
     *
     * @return An instance of `IStage` representing the base stage.
     */
    fun getBaseStage(): IStage

    /**
     * Retrieves the list of stages associated with the current object.
     *
     * @return A list of instances implementing the IStage interface.
     */
    fun getStages(): List<IStage>

    /**
     * Retrieves a hashmap mapping stage item IDs to their corresponding IStage instances.
     *
     * @return A hashmap where the keys are stage item IDs (String) and the values are IStage instances.
     */
    fun getStagesAdapterData(): HashMap<AdapterData, IStage>


    /**
     * Retrieves the tool associated with the specified adapter data.
     *
     * @param tool The AdapterData representing the tool to retrieve.
     * @return An ITool instance if a tool corresponding to the adapter data exists, or null otherwise.
     */
    fun getTool(tool: AdapterData): ITool?

    /**
     * Determines if a specific tool exists within the system.
     *
     * @param tool The `AdapterData` representing the tool to check for existence.
     * @return `true` if the tool exists, otherwise `false`.
     */
    fun existsTool(tool: AdapterData): Boolean

    /**
     * Determines if the specified stage is the last stage.
     *
     * @param stage The stage to check.
     * @return True if the stage is the last stage; otherwise, false.
     */
    fun isLastStage(stage: IStage): Boolean

    /**
     * Checks if the current instance should not be protected.
     *
     * @return true if the instance is not protected, false otherwise.
     */
    fun isNotProtect(): Boolean

    /**
     * Retrieves the stage that precedes the provided current stage.
     *
     * @param currentStage The current stage from which to retrieve the preceding stage.
     * @return The stage instance that precedes the current stage.
     */
    fun getBackStage(currentStage: IStage): IStage

    /**
     * Retrieves the interaction mode required to trigger this generic.
     *
     * Supported modes:
     * - INTERACT: normal right click / interact without shift.
     * - INTERACT_SHIFT: interact while sneaking.
     *
     * This is checked centrally inside StageManager, so each compatibility
     * does not need to implement its own shift logic.
     *
     * YAML:
     * mode: INTERACT
     * mode: INTERACT_SHIFT
     *
     * @return The configured interaction mode.
     */
    fun getInteractionMode(): InteractionMode
}