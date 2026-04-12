package dev.wuason.unearthMechanic.system.animations
import com.jeff_media.morepersistentdatatypes.DataType
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.config.Animation
import dev.wuason.unearthMechanic.config.IAnimation
import dev.wuason.unearthMechanic.utils.ItemBuilder
import dev.wuason.unearthMechanic.utils.StorageUtils
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MainHand
import org.bukkit.scheduler.BukkitRunnable

open class AnimationRunner(
    private val player: Player,
    private val animation: IAnimation
): IAnimationRunner {

    private var itemMainHand: ItemStack = player.inventory.itemInMainHand
    private var running: Boolean = false
    private val itemAnimation: ItemStack = animation.getAnimationItem()
    private var tick: Long = 0

    init {
        if (animation.getTicks() < 1) {
            throw IllegalArgumentException("Animation ticks must be greater than 0")
        }
    }

    fun start(startTask: Boolean = false) {
        if (running) return
        player.persistentDataContainer.set(AnimationManager.ANIM_ITEM_MAIN_HAND_NAMESPACED_KEY, DataType.ITEM_STACK, itemMainHand)
        player.persistentDataContainer.set(AnimationManager.ANIM_ITEM_MAIN_HAND_ANIM_ITEM_NAMESPACED_KEY, DataType.ITEM_STACK, itemAnimation)
        player.inventory.setItemInMainHand(itemAnimation)
        onStart()
        running = true
        if (startTask) {
            runTask()
        }
    }

    open fun onStart() {
    }

    open fun onFinish() {
    }

    fun runTick(): Boolean {
        tick += 1
        if (!check() || tick >= animation.getTicks()) {
            finish()
            onFinish()
            return false
        }
        return true
    }

    private fun finish() {
        if (player.inventory.itemInMainHand == itemAnimation) {
            player.inventory.setItemInMainHand(itemMainHand)
        }
        else {
            if (player.inventory.itemInMainHand.type.isAir) {
                player.inventory.setItemInMainHand(itemMainHand)
            }
            else {
                StorageUtils.addItemToInventoryOrDrop(player, itemMainHand)
            }
        }
        removeAll()
    }

    override fun updateItemMainHandData() {
        if (!running) return
        player.persistentDataContainer.set(AnimationManager.ANIM_ITEM_MAIN_HAND_NAMESPACED_KEY, DataType.ITEM_STACK, itemMainHand)
    }

    override fun setItemMainHand(item: ItemStack) {
        if (!running) return
        player.persistentDataContainer.set(AnimationManager.ANIM_ITEM_MAIN_HAND_NAMESPACED_KEY, DataType.ITEM_STACK, itemMainHand)
        itemMainHand = item
    }

    override fun getItemMainHand(): ItemStack {
        return itemMainHand
    }

    override fun isRunning(): Boolean {
        return running
    }

    private fun check(): Boolean {
        if (!running) return false
        return player.inventory.itemInMainHand == itemAnimation
    }

    private fun removeAll() {
        player.inventory.contents.withIndex().forEach { (index, item) ->
            item?.let {
                if (ItemBuilder.from(item).hasPersistentData(AnimationManager.ANIM_NAMESPACED_KEY)) {
                    player.inventory.setItem(index, null)
                }
            }
        }
    }

    private fun runTask() {
        val bukkitRunnable = object : BukkitRunnable() {
            override fun run() {
                if (!runTick()) {
                    cancel()
                    return
                }
            }
        }
        bukkitRunnable.runTaskTimer(UnearthMechanic.getInstance(), 0, 1)
    }

    override fun isValid(): Boolean {
        return player.inventory.itemInMainHand == itemAnimation
    }

    fun cancel() {
        running = false
    }
}