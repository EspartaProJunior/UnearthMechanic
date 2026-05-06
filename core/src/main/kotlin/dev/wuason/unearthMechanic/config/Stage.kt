package dev.wuason.unearthMechanic.config

import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.compatibilities.worldguard.WorldGuardPlugin
import dev.wuason.unearthMechanic.system.LiveTool
import org.bukkit.Location
import org.bukkit.entity.Player
import kotlin.random.Random

open class Stage(
    private val stage: Int, private val adapterData: AdapterData?,
    private val drops: List<Drop>,
    private val remove: Boolean,
    private val removeItemMainHand: Boolean,
    private val durabilityToRemove: Int,
    private val usagesIaToRemove: Int, private val permissionStage: String,
    private val onlyOneDrop: Boolean,
    private val reduceItemHand: Int, private val items: List<Item>, private val onlyOneItem: Boolean,
    private val sounds: List<Sound>,
    private val delay: Long, private val toolAnimDelay: Boolean,
    private val executeCommands: List<IStageCommand>
) : IStage {

    private var randomStageOptions: List<RandomStageOption> = emptyList()

    fun setRandomStageOptions(options: List<RandomStageOption>) {
        this.randomStageOptions = options.filter { it.chance > 0 }
    }

    fun getRandomStageOptions(): List<RandomStageOption> {
        return randomStageOptions
    }

    fun hasRandomStageOptions(): Boolean {
        return randomStageOptions.isNotEmpty()
    }

    // Returns the final AdapterData for the stage:
    // - If there is a fixed adapterData, use that
    // - If there are randomStageOptions, choose one based on weight
    fun resolveAdapterData(): AdapterData? {
        if (randomStageOptions.isEmpty()) return adapterData
        return rollWeightedAdapter()
    }

    // Create a RESOLVED copy of the stage, so that during that execution
    // the random result does not change.
    fun resolveStage(): Stage {
        val resolvedAdapter = resolveAdapterData()

        val constructor = this::class.java.declaredConstructors.first()
        constructor.isAccessible = true

        val resolved = constructor.newInstance(
            stage,
            resolvedAdapter,
            drops,
            remove,
            removeItemMainHand,
            durabilityToRemove,
            usagesIaToRemove,
            permissionStage,
            onlyOneDrop,
            reduceItemHand,
            items,
            onlyOneItem,
            sounds,
            delay,
            toolAnimDelay,
            executeCommands
        ) as Stage

        resolved.setExplicitBlockProperties(getExplicitBlockProperties())
        resolved.setRememberPrevious(this.shouldRememberPrevious())
        resolved.setUsePrevious(this.shouldUsePrevious())
        resolved.setFallbackAdapterData(this.getFallbackAdapterData())
        resolved.setFallbackProperties(this.getFallbackProperties())
        resolved.setRegionConditions(this.getRegionConditions())
        resolved.setTimedSequenceInteraction(this.getTimedSequenceInteraction())

        sequenceStages?.let { resolved.setSequenceStages(it) }
        return resolved
    }

    private fun rollWeightedAdapter(): AdapterData? {
        if (randomStageOptions.isEmpty()) return null

        val total = randomStageOptions.sumOf { it.chance }
        if (total <= 0.0) return null

        val roll = Random.nextDouble(0.0, total)
        var current = 0.0

        for (option in randomStageOptions) {
            current += option.chance
            if (roll < current) {
                return option.adapterData
            }
        }

        return randomStageOptions.lastOrNull()?.adapterData
    }

    override fun isRemoveItemMainHand(): Boolean {
        return removeItemMainHand
    }

    override fun getUsagesIaToRemove(): Int {
        return usagesIaToRemove
    }

    override fun isRemove(): Boolean {
        return remove
    }

    override fun isOnlyOneDrop(): Boolean {
        return onlyOneDrop
    }

    override fun getDurabilityToRemove(): Int {
        return durabilityToRemove
    }

    override fun getPermissionStage(): String {
        return permissionStage
    }

    override fun getAdapterData(): AdapterData? {
        return adapterData
    }

    override fun getStage(): Int {
        return stage
    }

    override fun getDrops(): List<Drop> {
        return drops
    }

    override fun getReduceItemHand(): Int {
        return reduceItemHand
    }

    override fun getItems(): List<Item> {
        return items
    }

    override fun isOnlyOneItem(): Boolean {
        return onlyOneItem
    }

    override fun getSounds(): List<Sound> {
        return sounds
    }

    override fun getDelay(): Long {
        return delay
    }

    override fun isToolAnimDelay(): Boolean {
        return toolAnimDelay
    }

    override fun getExecuteCommands(): List<IStageCommand> = executeCommands

    override fun dropItems(loc: Location) {
        if(drops.isEmpty()) return
        if (isOnlyOneDrop()) {
            val results = drops.filter { drop ->
                drop.rollItem(true) != null
            }
            if (results.isEmpty()) return
            results.random().dropItem(loc, false)

            //drops[Random.nextInt(drops.size)].dropItem(loc, false)
            return
        }
        drops.forEach { it.dropItem(loc, true) }
    }

    override fun addItems(player: Player) {
        if(items.isEmpty()) return
        if (isOnlyOneItem()) {
            val results = items.filter { drop ->
                drop.rollItem(true) != null
            }
            if (results.isEmpty()) return
            results.random().addItem(player, true)

            //items[Random.nextInt(items.size)].addItem(player, true)
            return
        }
        items.forEach { it.addItem(player, true) }
    }

    fun getMaxCorrectDelay(toolUsed: LiveTool): Long {
        return if (toolUsed.getITool().getDelay() > 0) {
            toolUsed.getITool().getDelay()
        } else if (getDelay() > 0) {
            getDelay()
        } else {
            return -1
        }
    }

    private var sequenceStages: Map<Long, Stage>? = null

    fun setSequenceStages(sequence: Map<Long, Stage>) {
        this.sequenceStages = sequence
    }

    fun getSequenceStages(): Map<Long, Stage>? {
        return sequenceStages
    }

    private var timedSequenceInteraction: TimedSequenceInteraction? = null

    fun setTimedSequenceInteraction(value: TimedSequenceInteraction?) {
        timedSequenceInteraction = value
    }

    fun getTimedSequenceInteraction(): TimedSequenceInteraction? {
        return timedSequenceInteraction
    }

    fun hasTimedSequenceInteraction(): Boolean {
        return timedSequenceInteraction != null
    }

    private var explicitBlockProperties: Map<String, String> = emptyMap()

    fun setExplicitBlockProperties(props: Map<String, String>) {
        explicitBlockProperties = props
    }

    fun getExplicitBlockProperties(): Map<String, String> {
        return explicitBlockProperties
    }

    private var rememberPrevious: Boolean = false
    private var usePrevious: Boolean = false

    fun setRememberPrevious(value: Boolean) {
        rememberPrevious = value
    }

    fun shouldRememberPrevious(): Boolean {
        return rememberPrevious
    }

    fun setUsePrevious(value: Boolean) {
        usePrevious = value
    }

    fun shouldUsePrevious(): Boolean {
        return usePrevious
    }

    fun copyWithAdapterData(newAdapterData: AdapterData?): Stage {
        val constructor = this::class.java.declaredConstructors.first()
        constructor.isAccessible = true

        val copy = constructor.newInstance(
            stage,
            newAdapterData,
            drops,
            remove,
            removeItemMainHand,
            durabilityToRemove,
            usagesIaToRemove,
            permissionStage,
            onlyOneDrop,
            reduceItemHand,
            items,
            onlyOneItem,
            sounds,
            delay,
            toolAnimDelay,
            executeCommands
        ) as Stage

        copy.setExplicitBlockProperties(getExplicitBlockProperties())
        copy.setRememberPrevious(this.shouldRememberPrevious())
        copy.setUsePrevious(this.shouldUsePrevious())
        copy.setFallbackAdapterData(this.getFallbackAdapterData())
        copy.setFallbackProperties(this.getFallbackProperties())
        copy.setRegionConditions(this.getRegionConditions())
        copy.setTimedSequenceInteraction(this.getTimedSequenceInteraction())
        sequenceStages?.let { copy.setSequenceStages(it) }

        return copy
    }

    private var fallbackAdapterData: AdapterData? = null

    fun setFallbackAdapterData(adapterData: AdapterData?) {
        fallbackAdapterData = adapterData
    }

    fun getFallbackAdapterData(): AdapterData? {
        return fallbackAdapterData
    }

    private var fallbackProperties: Map<String, String> = emptyMap()

    fun setFallbackProperties(props: Map<String, String>) {
        fallbackProperties = props
    }

    fun getFallbackProperties(): Map<String, String> {
        return fallbackProperties
    }

    private var regionConditions: List<RegionCondition> = emptyList()

    fun setRegionConditions(conditions: List<RegionCondition>) {
        this.regionConditions = conditions
    }

    fun getRegionConditions(): List<RegionCondition> {
        return regionConditions
    }

    fun hasRegionConditions(): Boolean {
        return regionConditions.isNotEmpty()
    }

    fun matchesRegionConditions(location: Location): Boolean {
        if (regionConditions.isEmpty()) return true

        if (!WorldGuardPlugin.isWorldGuardEnabled()) {
            return false
        }

        val currentRegions = WorldGuardPlugin.getRegionIdsAt(location)

        return regionConditions.all { condition ->
            condition.matches(currentRegions)
        }
    }
}
