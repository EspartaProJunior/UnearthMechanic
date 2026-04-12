package dev.wuason.unearthMechanic.system.animations

import com.jeff_media.morepersistentdatatypes.DataType
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.config.IAnimation
import dev.wuason.unearthMechanic.utils.ItemRemoverManager
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import java.util.*

class AnimationManager(
    private val core: UnearthMechanic
): IAnimationManager {

    companion object {
        val ANIM_NAMESPACED_KEY: NamespacedKey = NamespacedKey(UnearthMechanic.getInstance(), "animItem")
        val ANIM_ITEM_MAIN_HAND_NAMESPACED_KEY: NamespacedKey = NamespacedKey(UnearthMechanic.getInstance(), "animItemMainHand")
        val ANIM_ITEM_MAIN_HAND_ANIM_ITEM_NAMESPACED_KEY: NamespacedKey = NamespacedKey(UnearthMechanic.getInstance(), "animItemMainHandAnimItem")

        init {
            ItemRemoverManager.addCheck { item ->
                if (!item.type.isAir && item.hasItemMeta()) return@addCheck item.itemMeta.persistentDataContainer.has(
                    ANIM_NAMESPACED_KEY
                )
                return@addCheck false
            }
        }
    }

    init {
        core.getServer().pluginManager.registerEvents(object : Listener {
            @EventHandler(priority = EventPriority.LOWEST)
            fun onPlayerJoin(event: PlayerJoinEvent) {
                val persistentData: PersistentDataContainer = event.player.persistentDataContainer
                if (persistentData.has(ANIM_ITEM_MAIN_HAND_NAMESPACED_KEY) && persistentData.has(
                        ANIM_ITEM_MAIN_HAND_ANIM_ITEM_NAMESPACED_KEY)) {
                    val itemMainHand: ItemStack? = persistentData.get(ANIM_ITEM_MAIN_HAND_NAMESPACED_KEY, DataType.ITEM_STACK)
                    val itemAnimation: ItemStack? = persistentData.get(ANIM_ITEM_MAIN_HAND_ANIM_ITEM_NAMESPACED_KEY, DataType.ITEM_STACK)

                    if (itemMainHand != null && itemAnimation != null) {
                        event.player.inventory.contents.withIndex().forEach { (index, item) ->
                            if (item != null && item.isSimilar(itemAnimation)) {
                                event.player.inventory.setItem(index, itemMainHand)
                            }
                        }
                    }
                }
                persistentData.remove(ANIM_ITEM_MAIN_HAND_NAMESPACED_KEY)
                persistentData.remove(ANIM_ITEM_MAIN_HAND_ANIM_ITEM_NAMESPACED_KEY)
            }
        }, core)
    }

    private val animations: WeakHashMap<Player, IAnimationRunner> = WeakHashMap()

    override fun isAnimating(player: Player): Boolean {
        return animations.containsKey(player)
    }

    override fun getAnimation(player: Player): IAnimationRunner? {
        return animations[player]
    }

    override fun playAnimation(player: Player, animation: IAnimation) {

        if(isAnimating(player)) return

        val animationRunner: AnimationRunner = object : AnimationRunner(player, animation) {
            override fun onStart() {

            }

            override fun onFinish() {
                animations.remove(player)
            }
        }

        animations[player] = animationRunner

        animationRunner.start(true)
    }

    override fun stopAnimation(player: Player) {
        if (!isAnimating(player)) return
        val anim: AnimationRunner = animations[player]!! as AnimationRunner
        anim.cancel()
    }

    override fun getAnimations(): WeakHashMap<Player, IAnimationRunner> {
        return animations
    }
}