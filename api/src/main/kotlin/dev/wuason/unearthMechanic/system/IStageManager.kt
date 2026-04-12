package dev.wuason.unearthMechanic.system

import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.system.animations.IAnimationManager
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility

/**
 * Manages stages and their compatibilities, providing functionalities to retrieve and check compatibility information,
 * handle animations, and interact with loaded compatibilities.
 */
interface IStageManager {

    /**
     * Retrieves a mutable list of loaded compatibility interfaces.
     *
     * @return A MutableList containing instances of ICompatibility that represent the loaded compatibilities.
     */
    fun getCompatibilitiesLoaded(): MutableList<ICompatibility>

    fun getCompatibilityByAdapterId(adapterData: AdapterData): ICompatibility?

    /**
     * Determines whether the specified adapter data and compatibility object share similar compatibility.
     *
     * @param adapterData The adapter data object that contains information to compare.
     * @param compatibility The compatibility interface instance to be compared with the adapter data.
     * @return true if the adapter data and compatibility are similar; false otherwise.
     */
    fun isSimilarCompatibility(adapterData: AdapterData, compatibility: ICompatibility): Boolean

    /**
     * Retrieves the animation manager responsible for handling player animations.
     *
     * @return the animation manager instance
     */
    fun getAnimator(): IAnimationManager

}