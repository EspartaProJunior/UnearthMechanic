package dev.wuason.unearthMechanic.compatibilities.craftengine.item_behavior

import dev.wuason.unearthMechanic.utils.FoliaUtils
import net.momirealms.craftengine.core.item.behavior.ItemBehavior
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory
import net.momirealms.craftengine.core.pack.Pack
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.util.Key
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.nio.file.Path

class FrozenTotemOfUndyingItemBehavior(
    private val itemId: String,
    private val itemModel: String,
    private val radius: Double,
    private val freezeTicks: Int,
    private val slownessDurationTicks: Int,
    private val slownessAmplifier: Int
) : ItemBehavior() {

    companion object {
        private val FROST_DUST = Particle.DustOptions(Color.fromRGB(150, 230, 255), 1.4f)
        private val CRAFT_ENGINE_ITEM_KEYS = listOf(
            NamespacedKey("craftengine", "id"),
            NamespacedKey("craftengine", "item_id"),
            NamespacedKey("craftengine", "custom_item_id")
        )

        val FACTORY: ItemBehaviorFactory<FrozenTotemOfUndyingItemBehavior> =
            ItemBehaviorFactory { _: Pack, _: Path, _: Key, section: ConfigSection ->
                FrozenTotemOfUndyingItemBehavior(
                    itemId = section.getString("item-id", "elitefantasy:frozen_totem_of_undying"),
                    itemModel = section.getString("item-model", "elitefantasy:frozen_totem_of_undying"),
                    radius = section.getDouble("radius", 6.0),
                    freezeTicks = section.getInt("freeze-ticks", 180),
                    slownessDurationTicks = section.getInt("slowness-duration", 160),
                    slownessAmplifier = section.getInt("slowness-amplifier", 6)
                )
            }
    }

    fun matchesItem(item: ItemStack): Boolean {
        if (item.type != org.bukkit.Material.TOTEM_OF_UNDYING) return false

        val meta = item.itemMeta ?: return false
        if (meta.itemModelKey() == itemModel) return true

        val container = meta.persistentDataContainer
        return CRAFT_ENGINE_ITEM_KEYS.any { key ->
            container.get(key, PersistentDataType.STRING) == itemId
        }
    }

    private fun org.bukkit.inventory.meta.ItemMeta.itemModelKey(): String? {
        return runCatching {
            val itemModel = javaClass.getMethod("getItemModel").invoke(this) as? NamespacedKey
            itemModel?.toString()
        }.getOrNull()
    }

    fun trigger(player: Player) {
        val origin = player.location.clone()

        FoliaUtils.runAtLocation(origin) {
            playActivationBurst(origin)
            playFreezeWave(origin)

            origin.world
                ?.getNearbyLivingEntities(origin, radius)
                ?.asSequence()
                ?.filter { it.uniqueId != player.uniqueId }
                ?.filter { !it.isDead && it.isValid }
                ?.filter { it.location.distanceSquared(origin) <= radius * radius }
                ?.forEach { entity ->
                    freezeEntity(entity)
                }
        }
    }

    private fun freezeEntity(entity: LivingEntity) {
        FoliaUtils.runAtEntity(entity) {
            entity.freezeTicks = maxOf(entity.freezeTicks, entity.maxFreezeTicks + freezeTicks)
            entity.addPotionEffect(
                PotionEffect(
                    PotionEffectType.SLOWNESS,
                    slownessDurationTicks,
                    slownessAmplifier,
                    true,
                    true,
                    true
                )
            )
            playEntityFreezeParticles(entity)
        }
    }

    private fun playActivationBurst(origin: Location) {
        val world = origin.world ?: return
        val center = origin.clone().add(0.0, 1.0, 0.0)

        world.spawnParticle(Particle.SNOWFLAKE, center, 90, 0.75, 0.75, 0.75, 0.08)
        world.spawnParticle(Particle.CLOUD, center, 35, 0.45, 0.35, 0.45, 0.03)
        world.spawnParticle(Particle.DUST, center, 55, 0.65, 0.45, 0.65, 0.0, FROST_DUST)
        world.spawnParticle(Particle.ITEM_SNOWBALL, center, 24, 0.55, 0.55, 0.55, 0.12)
    }

    private fun playFreezeWave(origin: Location) {
        val world = origin.world ?: return
        world.playSound(origin, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.65f)
        world.playSound(origin, Sound.BLOCK_POWDER_SNOW_BREAK, 1.0f, 0.85f)

        val y = origin.y + 0.25
        val steps = 96

        for (ring in 1..4) {
            val ringRadius = radius * ring / 4.0
            repeat(steps) { step ->
                val angle = (Math.PI * 2.0 * step) / steps
                val x = origin.x + kotlin.math.cos(angle) * ringRadius
                val z = origin.z + kotlin.math.sin(angle) * ringRadius
                world.spawnParticle(Particle.DUST, x, y, z, 1, 0.0, 0.0, 0.0, 0.0, FROST_DUST)
                world.spawnParticle(Particle.SNOWFLAKE, x, y + 0.2, z, 1, 0.02, 0.03, 0.02, 0.0)
            }
        }
    }

    private fun playEntityFreezeParticles(entity: LivingEntity) {
        val world = entity.world
        val location = entity.location.clone().add(0.0, entity.height * 0.5, 0.0)

        world.spawnParticle(Particle.SNOWFLAKE, location, 45, 0.35, entity.height * 0.35, 0.35, 0.04)
        world.spawnParticle(Particle.DUST, location, 28, 0.3, entity.height * 0.3, 0.3, 0.0, FROST_DUST)
        world.spawnParticle(Particle.BLOCK, location, 18, 0.25, entity.height * 0.25, 0.25, 0.03, org.bukkit.Material.ICE.createBlockData())
        world.playSound(entity.location, Sound.BLOCK_GLASS_HIT, 0.7f, 1.45f)
    }

}

class FrozenTotemOfUndyingListener : Listener {

    private val itemModel = "elitefantasy:frozen_totem_of_undying"

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTotemUse(event: EntityResurrectEvent) {
        Bukkit.getLogger().info("[FrozenTotem] EntityResurrectEvent fired: ${event.entity.name}")

        val player = event.entity as? Player ?: return

        val mainHand = player.inventory.itemInMainHand
        val offHand = player.inventory.itemInOffHand

        if (!isFrozenTotem(mainHand) && !isFrozenTotem(offHand)) {
            //Bukkit.getLogger().info("[FrozenTotem] Not frozen totem")
            //Bukkit.getLogger().info("[FrozenTotem] Main hand: ${mainHand.type} ${mainHand.itemMeta}")
            //Bukkit.getLogger().info("[FrozenTotem] Off hand: ${offHand.type} ${offHand.itemMeta}")
            return
        }

        //Bukkit.getLogger().info("[FrozenTotem] Frozen totem activated")

        FrozenTotemOfUndyingItemBehavior(
            itemId = "elitefantasy:frozen_totem_of_undying",
            itemModel = itemModel,
            radius = 6.0,
            freezeTicks = 180,
            slownessDurationTicks = 160,
            slownessAmplifier = 6
        ).trigger(player)
    }

    private fun isFrozenTotem(item: ItemStack): Boolean {
        if (item.type != Material.TOTEM_OF_UNDYING) return false

        val meta = item.itemMeta ?: return false

        val model = runCatching {
            meta.javaClass.getMethod("getItemModel").invoke(meta) as? NamespacedKey
        }.getOrNull()

        if (model?.toString() == itemModel) return true

        return meta.toString().contains("elitefantasy:frozen_totem_of_undying")
    }
}