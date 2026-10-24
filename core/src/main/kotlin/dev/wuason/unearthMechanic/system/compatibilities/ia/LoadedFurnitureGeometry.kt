package dev.wuason.unearthMechanic.system.compatibilities.ia

import dev.lone.itemsadder.api.CustomFurniture
import dev.wuason.unearthMechanic.utils.DebugSettings
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Rotation
import org.bukkit.block.BlockFace
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemFrame
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.HexFormat

/** Geometry only. No replacement state, item writes, barrier operations or scheduling. */
object LoadedFurnitureGeometry {
    private data class Access(
        val loader: ClassLoader,
        val resolve: Method,
        val direction: Method,
        val rotation: Method,
        val marker: Method,
        val key: Field,
        val values: Array<Field>
    )

    @Volatile
    private var access: Access? = null

    @JvmStatic
    fun supported(): Boolean = try {
        access(ItemsAdderHitboxCompat.loadedGeometryOperation())
        true
    } catch (failure: Throwable) {
        try {
            Bukkit.getLogger().warning("[UM-IA] Loaded furniture geometry disabled: ${failure.message}")
        } catch (_: Throwable) {
        }
        false
    }

    @Throws(Exception::class)
    private fun access(operation: Method): Access {
        val loader = operation.declaringClass.classLoader
        val cached = access
        if (cached != null && cached.loader === loader) return cached

        loader.getResourceAsStream("itemsadder/m/bcc.class").use { input ->
            if (input == null || HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())
                ) != "68ac02d01841bf03155f48c686bb2a29e8906c28c1c1423f252b94f904809649"
            ) {
                throw IllegalStateException("unsupported_ia_orientation_bytecode")
            }
        }

        val jl = Class.forName("itemsadder.m.jl", false, loader)
        val bcc = Class.forName("itemsadder.m.bcc", false, loader)
        val values = arrayOf("xv", "xw", "xx", "xz", "xA", "xy").map(jl::getField).toTypedArray()
        val resolved = Access(
            loader = loader,
            resolve = jl.getMethod("c", Entity::class.java, String::class.java),
            direction = bcc.getMethod("a", Vector::class.java),
            rotation = bcc.getMethod("a", Rotation::class.java),
            marker = operation.declaringClass.getMethod("w", Entity::class.java),
            key = jl.getField("xp"),
            values = values
        )
        access = resolved
        return resolved
    }

    /** Pure mirror of fingerprinted IA 4.0.18 z(Entity), to avoid repairing a correct box. */
    @JvmStatic
    @Throws(Exception::class)
    fun expected(entity: ItemFrame, operation: Method): BoundingBox {
        val access = access(operation)
        val behaviour = access.resolve.invoke(null, entity, "furniture")
        if (behaviour == null || !operation.declaringClass.isInstance(behaviour)) {
            throw IllegalStateException("current_furniture_metadata_unresolved")
        }

        val values = DoubleArray(6)
        for (index in values.indices) {
            values[index] = access.values[index].getFloat(behaviour).toDouble()
            if (!values[index].isFinite() || Math.abs(values[index]) > 128) {
                throw IllegalStateException("unsupported_geometry")
            }
        }

        val length = values[0]
        val width = values[1]
        val height = values[2]
        val ox = values[3]
        val oy = values[4]
        val oz = values[5]
        if (length <= 0 || width <= 0 || height <= 0 || length * width * height > 4096) {
            throw IllegalStateException("unsupported_geometry")
        }

        val location = entity.location
        val face = access.direction.invoke(null, location.direction) as BlockFace
        val rotation = access.rotation.invoke(null, entity.rotation) as BlockFace
        val x = location.x
        val y = location.y
        val z = location.z

        return if (face == BlockFace.UP || face == BlockFace.DOWN) {
            val floor = face == BlockFace.UP
            var dx = width / 2
            var dz = length / 2
            val (sx, sz) = when (rotation) {
                BlockFace.NORTH, BlockFace.NORTH_EAST, BlockFace.NORTH_WEST -> ox to if (floor) -oz else oz
                BlockFace.SOUTH, BlockFace.SOUTH_EAST, BlockFace.SOUTH_WEST -> -ox to if (floor) oz else -oz
                BlockFace.EAST -> {
                    dx = length / 2
                    dz = width / 2
                    oz to if (floor) ox else -ox
                }
                BlockFace.WEST -> {
                    dx = length / 2
                    dz = width / 2
                    -oz to if (floor) -ox else ox
                }
                else -> throw IllegalStateException("unsupported_rotation")
            }
            BoundingBox(
                x - dx,
                if (floor) y else y - height,
                z - dz,
                x + dx,
                if (floor) y + height else y,
                z + dz
            ).shift(sx, oy, sz)
        } else {
            when (face) {
                BlockFace.NORTH -> BoundingBox(x - width / 2, y - length / 2, z - height, x + width / 2, y + length / 2, z).shift(ox, oy, oz)
                BlockFace.SOUTH -> BoundingBox(x - width / 2, y - length / 2, z, x + width / 2, y + length / 2, z + height).shift(-ox, oy, oz)
                BlockFace.EAST -> BoundingBox(x, y - length / 2, z - width / 2, x + height, y + length / 2, z + width / 2).shift(-oz, oy, ox)
                BlockFace.WEST -> BoundingBox(x - height, y - length / 2, z - width / 2, x, y + length / 2, z + width / 2).shift(oz, oy, -ox)
                else -> throw IllegalStateException("unsupported_direction")
            }
        }
    }

    @JvmStatic
    fun sameBox(first: BoundingBox, second: BoundingBox): Boolean =
        Math.abs(first.minX - second.minX) < 1e-9 && Math.abs(first.minY - second.minY) < 1e-9 &&
                Math.abs(first.minZ - second.minZ) < 1e-9 && Math.abs(first.maxX - second.maxX) < 1e-9 &&
                Math.abs(first.maxY - second.maxY) < 1e-9 && Math.abs(first.maxZ - second.maxZ) < 1e-9

    @JvmStatic
    fun repair(entity: ItemFrame, source: String): Boolean {
        return try {
            if (!entity.isValid || entity.isDead) return false

            val location = entity.location.clone()
            if (!entity.world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return false

            val furniture = CustomFurniture.byAlreadySpawned(entity)
            if (furniture == null || furniture.entity !== entity) return false

            val config: ConfigurationSection? = ItemsAdderFurnitureSettings.get(furniture);
            if (config == null || !config.getBoolean("solid", false)) return false

            val operation = ItemsAdderHitboxCompat.loadedGeometryOperation()
            val access = access(operation)
            if (access.marker.invoke(null, entity) != true) return false

            val id = furniture.namespacedID
            val persistedId = entity.persistentDataContainer.get(
                access.key.get(null) as NamespacedKey,
                PersistentDataType.STRING
            )
            if (id != persistedId) throw IllegalStateException("current_item_and_persisted_id_mismatch")

            val expected = expected(entity, operation)
            val before = entity.boundingBox
            if (sameBox(before, expected)) return false

            val uuid = entity.uniqueId
            val facing = entity.facing
            val rotation = entity.rotation
            val item: ItemStack = entity.item.clone()
            operation.invoke(null, entity)

            val after = CustomFurniture.byAlreadySpawned(entity)
            if (uuid != entity.uniqueId || location != entity.location || facing != entity.facing ||
                rotation != entity.rotation || item != entity.item || after == null ||
                id != after.namespacedID || !sameBox(expected, entity.boundingBox)
            ) {
                throw IllegalStateException("loaded_geometry_postcondition_failed")
            }

            if (DebugSettings.isItemsAdderEnabled()) {
                Bukkit.getLogger().info(
                    "[UM-IA] LOADED_FURNITURE_GEOMETRY_REPAIRED uuid=$uuid id=$id source=$source bboxBefore=$before bboxAfter=${entity.boundingBox}"
                )
            }
            true
        } catch (failure: Throwable) {
            try {
                Bukkit.getLogger().warning(
                    "[UM-IA] LOADED_FURNITURE_GEOMETRY_FAILED source=$source reason=${failure.javaClass.simpleName}:${failure.message}"
                )
            } catch (_: Throwable) {
            }
            false
        }
    }
}