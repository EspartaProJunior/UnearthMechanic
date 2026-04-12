package dev.wuason.unearthMechanic.config

import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.system.LiveTool
import org.bukkit.Location
import org.bukkit.entity.Player
import kotlin.random.Random

open class Stage(
    private val stage: Int, private val adapterData: AdapterData?, private val drops: List<Drop>, private val remove: Boolean, private val removeItemMainHand: Boolean,
    private val durabilityToRemove: Int,
    private val usagesIaToRemove: Int, private val permissionStage: String,
    private val onlyOneDrop: Boolean,
    private val reduceItemHand: Int, private val items: List<Item>, private val onlyOneItem: Boolean, private val sounds: List<Sound>,
    private val delay: Long, private val toolAnimDelay: Boolean
) : IStage {

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
            items[Random.nextInt(items.size)].addItem(player, true)
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
}
