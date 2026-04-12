package dev.wuason.unearthMechanic.utils

import de.tr7zw.changeme.nbtapi.NBT
import de.tr7zw.changeme.nbtapi.iface.ReadWriteItemNBT
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT
import dev.wuason.adapter.Adapter
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.FireworkEffectMeta
import org.bukkit.inventory.meta.FireworkMeta
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import java.util.function.Consumer

open class ItemBuilder {

    protected var item: ItemStack
    protected var meta: ItemMeta

    companion object {
        @JvmStatic
        fun copyOf(item: ItemStack): ItemBuilder {
            return ItemBuilder(item.clone())
        }

        @JvmStatic
        fun from(item: ItemStack): ItemBuilder {
            return ItemBuilder(item)
        }
    }

    constructor(material: Material) : this(material, 1)

    constructor(material: Material, amount: Int) {
        this.item = ItemStack(material, amount)
        this.meta = this.item.itemMeta
    }

    constructor(adapterId: String, amount: Int) {
        this.item = Adapter.getItemStack(adapterId)
            ?: throw IllegalArgumentException("Adapter with id $adapterId is not valid")
        this.item.amount = amount
        this.meta = this.item.itemMeta
    }

    constructor(nbtJson: String) {
        this.item = NBT.itemStackFromNBT(NBT.parseNBT(nbtJson))!!
        this.meta = this.item.itemMeta
    }

    constructor(item: ItemStack) {
        this.item = item
        this.meta = item.itemMeta
    }

    protected constructor() : this(ItemStack(Material.AIR))

    constructor(item: ItemStack, amount: Int) {
        this.item = item
        this.meta = item.itemMeta
        this.item.amount = amount
    }

    fun adapter(adapterId: String): ItemBuilder {
        val temp = Adapter.getItemStack(adapterId)
            ?: throw IllegalArgumentException("Adapter with id $adapterId is not valid")
        this.item = temp
        this.meta = this.item.itemMeta
        return this
    }

    fun replaceItem(item: ItemStack): ItemBuilder {
        this.item = item
        this.meta = item.itemMeta
        return this
    }

    fun setName(name: String?): ItemBuilder {
        if (name == null) return this
        meta.displayName(Component.text(name))
        return this
    }

    fun setName(name: Component?): ItemBuilder {
        if (name == null) return this
        meta.displayName(name)
        return this
    }

    fun setNameWithMiniMessage(name: String?): ItemBuilder {
        if (name == null) return this
        setName(AdventureUtils.deserialize(name))
        return this
    }

    fun setSkullOwner(player: Player): ItemBuilder {
        if (item.type != Material.PLAYER_HEAD) return this
        val skullMeta = meta as? SkullMeta ?: return this
        skullMeta.owningPlayer = player
        meta = skullMeta
        return this
    }

    fun setSkullOwner(texture: String): ItemBuilder {
        if (item.type != Material.PLAYER_HEAD) return this

        if (VersionDetector.getServerVersion().isLessThan(VersionDetector.ServerVersion.v1_20_5)) {
            editNBT { nbt ->
                val skullOwnerCompound = nbt.getOrCreateCompound("SkullOwner")
                skullOwnerCompound.setUUID("Id", UUID.randomUUID())
                skullOwnerCompound.getOrCreateCompound("Properties")
                    .getCompoundList("textures")
                    .addCompound()
                    .setString("Value", texture)
            }
        } else {
            editNBTComponents { nbt ->
                val profileNbt = nbt.getOrCreateCompound("minecraft:profile")
                profileNbt.setUUID("id", UUID.randomUUID())
                val propertiesNbt = profileNbt.getCompoundList("properties").addCompound()
                propertiesNbt.setString("name", "textures")
                propertiesNbt.setString("value", texture)
            }
        }

        return this
    }

    fun buildWithVoidName(): ItemStack {
        setVoidName()
        return build()
    }

    fun setVoidName(): ItemBuilder {
        meta.displayName(Component.text(""))
        return this
    }

    fun setAmount(amount: Int): ItemBuilder {
        item.amount = amount
        return this
    }

    fun addEnchantment(enchantment: Enchantment, level: Int): ItemBuilder {
        meta.addEnchant(enchantment, level, true)
        return this
    }

    fun removeEnchantment(enchantment: Enchantment): ItemBuilder {
        meta.removeEnchant(enchantment)
        return this
    }

    fun addFlag(flag: ItemFlag): ItemBuilder {
        meta.addItemFlags(flag)
        return this
    }

    fun removeFlag(flag: ItemFlag): ItemBuilder {
        meta.removeItemFlags(flag)
        return this
    }

    fun setLore(lore: List<String>?): ItemBuilder {
        meta.lore = lore?.toList() ?: emptyList()
        return this
    }

    fun setLoreWithMiniMessage(lore: List<String>?): ItemBuilder {
        meta.lore(AdventureUtils.deserialize(lore ?: emptyList()))
        return this
    }

    fun addLoreWithMiniMessage(line: String): ItemBuilder {
        val lore = meta.lore()?.toMutableList() ?: mutableListOf()
        lore.add(AdventureUtils.deserialize(line))
        meta.lore(lore)
        return this
    }

    fun addLoreLine(line: String): ItemBuilder {
        val lore = meta.lore?.toMutableList() ?: mutableListOf()
        lore.add(line)
        meta.lore = lore
        return this
    }

    fun addLoreLines(lines: List<String>): ItemBuilder {
        val lore = meta.lore?.toMutableList() ?: mutableListOf()
        lore.addAll(lines)
        meta.lore = lore
        return this
    }

    fun removeLoreLine(line: String): ItemBuilder {
        val lore = meta.lore?.toMutableList() ?: mutableListOf()
        lore.remove(line)
        meta.lore = lore
        return this
    }

    fun removeLastLoreLine(): ItemBuilder {
        val lore = meta.lore?.toMutableList() ?: mutableListOf()
        if (lore.isEmpty()) return this
        lore.removeAt(lore.lastIndex)
        meta.lore = lore
        return this
    }

    fun removeFirstLoreLine(): ItemBuilder {
        val lore = meta.lore?.toMutableList() ?: mutableListOf()
        if (lore.isNotEmpty()) {
            lore.removeAt(0)
            meta.lore = lore
        }
        return this
    }

    fun removeLoreLine(index: Int): ItemBuilder {
        val lore = meta.lore?.toMutableList() ?: mutableListOf()
        if (index in lore.indices) {
            lore.removeAt(index)
            meta.lore = lore
        }
        return this
    }

    fun setUnbreakable(unbreakable: Boolean): ItemBuilder {
        meta.isUnbreakable = unbreakable
        return this
    }

    fun setColor(color: Color): ItemBuilder {
        val leatherMeta = meta as? LeatherArmorMeta ?: return this
        leatherMeta.setColor(color)
        meta = leatherMeta
        return this
    }

    fun setPotionEffect(effectType: PotionEffectType, duration: Int, amplifier: Int): ItemBuilder {
        val potionMeta = meta as? PotionMeta ?: return this
        potionMeta.addCustomEffect(PotionEffect(effectType, duration, amplifier), true)
        meta = potionMeta
        return this
    }

    fun setPotionColor(color: Color): ItemBuilder {
        val potionMeta = meta as? PotionMeta ?: return this
        potionMeta.color = color
        meta = potionMeta
        return this
    }

    fun clearPotionEffects(): ItemBuilder {
        val potionMeta = meta as? PotionMeta ?: return this
        potionMeta.clearCustomEffects()
        meta = potionMeta
        return this
    }

    fun setMaterial(material: Material): ItemBuilder {
        item.type = material
        return this
    }

    fun setDurability(durability: Int): ItemBuilder {
        val damageable = meta as? Damageable ?: return this
        damageable.damage = durability
        meta = damageable
        return this
    }

    fun setGlowing(glowing: Boolean): ItemBuilder {
        if (glowing) {
            meta.addEnchant(Enchantment.AQUA_AFFINITY, 1, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        } else {
            meta.removeEnchant(Enchantment.AQUA_AFFINITY)
            meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS)
        }
        return this
    }

    fun setInvisible(invisible: Boolean): ItemBuilder {
        if (invisible) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        } else {
            meta.removeItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return this
    }

    fun setCustomModelData(data: Int): ItemBuilder {
        meta.setCustomModelData(data)
        return this
    }

    fun setLeatherArmorColor(color: Color): ItemBuilder {
        val leatherMeta = meta as? LeatherArmorMeta ?: return this
        leatherMeta.setColor(color)
        meta = leatherMeta
        return this
    }

    fun clearMeta(): ItemBuilder {
        meta = item.itemMeta
        return this
    }

    fun removeLore(): ItemBuilder {
        meta.lore = emptyList()
        return this
    }

    @Suppress("DEPRECATION")
    fun setDamage(damage: Int): ItemBuilder {
        item.durability = damage.toShort()
        return this
    }

    fun replaceLoreLine(index: Int, newLine: String): ItemBuilder {
        val lore = meta.lore?.toMutableList() ?: mutableListOf()
        if (index in lore.indices) {
            lore[index] = newLine
            meta.lore = lore
        }
        return this
    }

    fun setLoreLine(index: Int, line: String): ItemBuilder {
        val lore = meta.lore?.toMutableList() ?: mutableListOf()
        if (index in lore.indices) {
            lore[index] = line
            meta.lore = lore
        }
        return this
    }

    fun setFireworkEffect(effect: FireworkEffect): ItemBuilder {
        val fireworkEffectMeta = meta as? FireworkEffectMeta ?: return this
        fireworkEffectMeta.effect = effect
        meta = fireworkEffectMeta
        return this
    }

    fun addPage(page: String): ItemBuilder {
        val bookMeta = meta as? BookMeta ?: return this
        bookMeta.addPage(page)
        meta = bookMeta
        return this
    }

    fun setAuthor(author: String): ItemBuilder {
        val bookMeta = meta as? BookMeta ?: return this
        bookMeta.author = author
        meta = bookMeta
        return this
    }

    fun setTitle(title: String): ItemBuilder {
        val bookMeta = meta as? BookMeta ?: return this
        bookMeta.title = title
        meta = bookMeta
        return this
    }

    fun setGeneration(generation: BookMeta.Generation): ItemBuilder {
        val bookMeta = meta as? BookMeta ?: return this
        bookMeta.generation = generation
        meta = bookMeta
        return this
    }

    fun setPower(power: Int): ItemBuilder {
        if (item.type != Material.FIREWORK_ROCKET) return this
        val fireworkMeta = meta as? FireworkMeta ?: return this
        fireworkMeta.power = power
        meta = fireworkMeta
        return this
    }

    fun addPersistentData(key: NamespacedKey, value: String): ItemBuilder {
        meta.persistentDataContainer.set(key, PersistentDataType.STRING, value)
        return this
    }

    fun addPersistentData(key: NamespacedKey, value: Int): ItemBuilder {
        meta.persistentDataContainer.set(key, PersistentDataType.INTEGER, value)
        return this
    }

    fun addPersistentData(key: NamespacedKey, value: Double): ItemBuilder {
        meta.persistentDataContainer.set(key, PersistentDataType.DOUBLE, value)
        return this
    }

    fun addPersistentData(key: NamespacedKey, value: Float): ItemBuilder {
        meta.persistentDataContainer.set(key, PersistentDataType.FLOAT, value)
        return this
    }

    fun addPersistentData(key: NamespacedKey, value: Long): ItemBuilder {
        meta.persistentDataContainer.set(key, PersistentDataType.LONG, value)
        return this
    }

    fun addPersistentData(key: NamespacedKey, value: ByteArray): ItemBuilder {
        meta.persistentDataContainer.set(key, PersistentDataType.BYTE_ARRAY, value)
        return this
    }

    fun addPersistentData(key: NamespacedKey, value: IntArray): ItemBuilder {
        meta.persistentDataContainer.set(key, PersistentDataType.INTEGER_ARRAY, value)
        return this
    }

    fun addPersistentData(key: NamespacedKey, value: LongArray): ItemBuilder {
        meta.persistentDataContainer.set(key, PersistentDataType.LONG_ARRAY, value)
        return this
    }

    fun removePersistentData(key: NamespacedKey): ItemBuilder {
        meta.persistentDataContainer.remove(key)
        return this
    }

    fun hasPersistentData(key: NamespacedKey): Boolean {
        return meta.persistentDataContainer.has(key, PersistentDataType.STRING)
    }

    fun meta(consumer: Consumer<ItemMeta>): ItemBuilder {
        consumer.accept(meta)
        return this
    }

    fun edit(consumer: Consumer<ItemStack>): ItemBuilder {
        consumer.accept(item)
        meta = item.itemMeta
        return this
    }

    fun editNBT(consumer: Consumer<ReadWriteItemNBT>): ItemBuilder {
        item.itemMeta = meta
        NBT.modify(item, consumer)
        meta = item.itemMeta
        return this
    }

    fun editNBTComponents(consumer: Consumer<ReadWriteNBT>): ItemBuilder {
        item.itemMeta = meta
        NBT.modifyComponents(item, consumer)
        meta = item.itemMeta
        return this
    }

    fun getPersistentDataString(key: NamespacedKey): String? {
        return meta.persistentDataContainer.get(key, PersistentDataType.STRING)
    }

    fun getPersistentDataInteger(key: NamespacedKey): Int? {
        return meta.persistentDataContainer.get(key, PersistentDataType.INTEGER)
    }

    fun getPersistentDataDouble(key: NamespacedKey): Double? {
        return meta.persistentDataContainer.get(key, PersistentDataType.DOUBLE)
    }

    fun getPersistentDataFloat(key: NamespacedKey): Float? {
        return meta.persistentDataContainer.get(key, PersistentDataType.FLOAT)
    }

    fun getPersistentDataLong(key: NamespacedKey): Long? {
        return meta.persistentDataContainer.get(key, PersistentDataType.LONG)
    }

    fun getPersistentDataByteArray(key: NamespacedKey): ByteArray? {
        return meta.persistentDataContainer.get(key, PersistentDataType.BYTE_ARRAY)
    }

    fun getPersistentDataIntegerArray(key: NamespacedKey): IntArray? {
        return meta.persistentDataContainer.get(key, PersistentDataType.INTEGER_ARRAY)
    }

    fun build(): ItemStack {
        item.itemMeta = meta
        return item
    }

}