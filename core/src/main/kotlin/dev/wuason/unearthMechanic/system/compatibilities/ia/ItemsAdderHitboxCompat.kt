package dev.wuason.unearthMechanic.system.compatibilities.ia

import dev.lone.itemsadder.api.CustomFurniture
import dev.lone.itemsadder.api.CustomStack
import dev.wuason.unearthMechanic.utils.DebugSettings
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Rotation
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemFrame
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Exact IA 4.0.18 geometry compatibility. No block writes or extra delayed repairs. */
object ItemsAdderHitboxCompat {
    private val states = ConcurrentHashMap<UUID, State>()

    @Volatile private var restore: Method? = null

    private val prepareFailures = ConcurrentHashMap<UUID, String>()
    private const val PATH = "behaviours.furniture"

    data class Cell(val x: Int, val y: Int, val z: Int)

    private class State(entity: ItemFrame, val target: String, val manual: Boolean, val count: Long) {
        val uuid: UUID = entity.uniqueId
        val location: Location = entity.location.clone()
        val facing = entity.facing
        val rotation: Rotation = entity.rotation
        val tick: Int = if (DebugSettings.isItemsAdderEnabled()) Bukkit.getCurrentTick() else 0
        var expected: Set<Cell>? = null
        var error: String? = null
    }

    private fun warn(message: String) {
        try { Bukkit.getLogger().warning("[UM-IA] $message") } catch (_: Throwable) {}
    }

    private fun normalize(value: String): String = if (value.startsWith("ia:")) value.substring(3) else value

    private fun settings(stack: CustomStack?): ConfigurationSection? = ItemsAdderFurnitureSettings.get(stack)

    private fun log(message: String) {
        try { Bukkit.getLogger().info("[UM-IA-V43] $message tick=${Bukkit.getCurrentTick()}") } catch (_: Throwable) {}
    }

    // Optional debug snapshots only; the guard precedes copies, lookups and formatting.
    private fun configSnapshot(section: ConfigurationSection?): String {
        if (section == null) return "NULL"
        val hitbox = section.getConfigurationSection("hitbox")
        return "{path=${section.currentPath},solidPresent=${section.contains("solid")},solid=${section.get("solid")}" +
                ",entity=${section.get("entity")},hitbox=${hitbox?.getValues(true) ?: section.get("hitbox")}" +
                ",fixed_rotation=${section.get("fixed_rotation")},cancel_drop=${section.get("cancel_drop")}}"
    }

    private fun stackSnapshot(stack: CustomStack?): String {
        if (stack == null) return "NULL"
        val resolved = try {
            val copy = CustomStack::class.java.getMethod("getConfigSectionCopy", Boolean::class.javaPrimitiveType!!)
                .invoke(stack, true) as? ConfigurationSection
            configSnapshot(copy?.getConfigurationSection(PATH))
        } catch (failure: Throwable) {
            "unavailable:${failure.javaClass.simpleName}"
        }
        return "{class=${stack.javaClass.name},id=${stack.id},namespacedId=${stack.namespacedID}" +
                ",settingsPath=items.${stack.id}.$PATH" +
                ",raw=${configSnapshot(stack.config?.getConfigurationSection("items.${stack.id}.$PATH"))},resolved=$resolved}"
    }

    private fun prepareDiagnostic(
        entity: Entity, target: String, manual: Boolean, event: String, reason: String,
        to: CustomStack?, from: CustomStack?
    ) {
        if (!DebugSettings.isItemsAdderEnabled()) return
        try {
            val source = from ?: if (entity is ItemFrame) CustomFurniture.byAlreadySpawned(entity) else null
            log(
                "$event reason=$reason uuid=${entity.uniqueId} currentId=${source?.namespacedID ?: "unresolved"}" +
                        " targetId=$target route=${if (manual) "interaction" else "sequence_hook"} bbox=${entity.boundingBox}" +
                        " sourceLookup=${stackSnapshot(source)} targetLookup=${stackSnapshot(to)}"
            )
        } catch (_: Throwable) {}
    }

    private fun afterDiagnostic(entity: Entity, target: String, manual: Boolean, reason: String) {
        if (!DebugSettings.isItemsAdderEnabled()) return
        try {
            val current = CustomFurniture.byAlreadySpawned(entity)
            log(
                "HITBOX_AFTER_DIAG reason=$reason uuid=${entity.uniqueId} currentId=${current?.namespacedID ?: "unresolved"}" +
                        " targetId=$target route=${if (manual) "interaction" else "sequence_hook"} bbox=${entity.boundingBox}"
            )
        } catch (_: Throwable) {}
    }

    @JvmStatic
    fun before(entity: Entity, target: String, manual: Boolean) {
        try {
            if (entity !is ItemFrame) {
                prepareDiagnostic(entity, target, manual, "HITBOX_PREPARE_SKIPPED", "not_item_frame", null, null)
                return
            }
            val normalizedTarget = normalize(target)
            val targetStack = CustomStack.getInstance(normalizedTarget)
            val to = settings(targetStack)
            if (to == null || !to.getBoolean("solid", false)) {
                prepareDiagnostic(entity, normalizedTarget, manual, "HITBOX_PREPARE_SKIPPED",
                    if (to == null) "target_settings_missing" else "target_not_solid", targetStack, null)
                return
            }

            val sourceFurniture = CustomFurniture.byAlreadySpawned(entity)
            val from = settings(sourceFurniture)
            if (from != null && !from.getBoolean("solid", false)) {
                // Non-solid source uses IA's inline ADD; not this compatibility.
                prepareDiagnostic(entity, normalizedTarget, manual, "HITBOX_PREPARE_SKIPPED", "source_not_solid", targetStack, sourceFurniture)
                return
            }

            var count = 1L
            for (axis in arrayOf("length", "width", "height")) {
                val dimension = to.getDouble("hitbox.$axis", 1.0)
                if (!dimension.isFinite() || dimension < 1 || dimension != Math.floor(dimension) || dimension > 128) {
                    count = -1
                    break
                }
                count *= dimension.toLong()
                if (count > 4096) {
                    count = -1
                    break
                }
            }

            val state = State(entity, normalizedTarget, manual, count)
            if (from == null) state.error = "source_config_unavailable"
            if (count < 1) state.error = "unsupported_grid_dimensions"
            states[state.uuid] = state
            prepareDiagnostic(entity, normalizedTarget, manual, "HITBOX_PREPARE_READY", state.error ?: "prepared", targetStack, sourceFurniture)
        } catch (failure: Throwable) {
            try { prepareFailures[entity.uniqueId] = failure.javaClass.simpleName } catch (_: Throwable) {}
            warn("HITBOX_PREPARE_FAILED reason=${failure.javaClass.simpleName}")
        }
    }

    @Throws(Exception::class)
    private fun operation(): Method {
        val cached = restore
        val plugin: Plugin = Bukkit.getPluginManager().getPlugin("ItemsAdder")
            ?: throw IllegalStateException("unsupported_ia_version")
        if (!plugin.isEnabled || plugin.description.version != "4.0.18") throw IllegalStateException("unsupported_ia_version")

        val loader = plugin.javaClass.classLoader
        if (loader != CustomFurniture::class.java.classLoader) throw IllegalStateException("api_classloader_mismatch")
        if (cached != null && cached.declaringClass.classLoader == loader) return cached

        for (check in arrayOf(
            arrayOf("itemsadder/m/js.class", "9F185F454BB4B60FC4D9A47132C06EE8D653BF627590616F6FA51C4BE6A5B2B2"),
            arrayOf("itemsadder/m/jl.class", "A9BE476F9657498869791B6CB4AC16739CA9046E80AE2F18C73E2E42DE70D4AB")
        )) {
            loader.getResourceAsStream(check[0]).use { input ->
                if (input == null || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))
                        .equals(check[1], ignoreCase = true)
                ) throw IllegalStateException("unsupported_ia_bytecode")
            }
        }

        val method = Class.forName("itemsadder.m.js", false, loader).getMethod("x", Entity::class.java)
        if (!Modifier.isStatic(method.modifiers) || method.returnType != Void.TYPE) throw IllegalStateException("unsupported_ia_signature")
        restore = method
        return method
    }

    @JvmStatic
    fun grid(box: BoundingBox): Set<Cell> {
        val contracted = box.clone().expand(-0.5)
        val cells = LinkedHashSet<Cell>()
        var sampled = 0L
        var x = contracted.minX
        while (x <= contracted.maxX) {
            var y = contracted.minY
            while (y <= contracted.maxY) {
                var z = contracted.minZ
                while (z <= contracted.maxZ) {
                    if (++sampled > 4096) throw IllegalStateException("grid_too_large")
                    cells += Cell(Math.floor(x).toInt(), Math.floor(y).toInt(), Math.floor(z).toInt())
                    z++
                }
                y++
            }
            x++
        }
        return Collections.unmodifiableSet(cells)
    }

    /** Reuse the exact V4.3 version/fingerprint gate without entering replacement state. */
    @JvmStatic
    @Throws(Exception::class)
    fun loadedGeometryOperation(): Method = operation()

    private fun identity(state: State, entity: ItemFrame): Boolean {
        val furniture = CustomFurniture.byAlreadySpawned(entity)
        return entity.isValid && !entity.isDead && state.uuid == entity.uniqueId &&
                state.location == entity.location && state.facing == entity.facing && state.rotation == entity.rotation &&
                furniture != null && state.target == normalize(furniture.namespacedID)
    }

    @JvmStatic
    fun after(entity: Entity, target: String, manual: Boolean) {
        try {
            val state = states[entity.uniqueId]
            if (state == null || state.target != normalize(target)) {
                afterDiagnostic(entity, target, manual, if (state == null) "state_missing" else "state_target_mismatch")
                return
            }
            afterDiagnostic(entity, target, manual, "state_prepared_before_restore")
            try {
                state.error?.let { throw IllegalStateException(it) }
                val itemFrame = entity as ItemFrame
                if (!identity(state, itemFrame)) throw IllegalStateException("identity_before_restore_failed")
                val before = if (DebugSettings.isItemsAdderEnabled()) itemFrame.boundingBox else null
                operation().invoke(null, itemFrame)
                if (!identity(state, itemFrame)) throw IllegalStateException("identity_after_restore_failed")
                val cells = grid(itemFrame.boundingBox)
                if (cells.size.toLong() != state.count) throw IllegalStateException("target_geometry_count_mismatch")
                state.expected = cells
                if (DebugSettings.isItemsAdderEnabled()) log(
                    "HITBOX_GEOMETRY_RESTORED uuid=${state.uuid} targetId=${state.target} bboxBefore=$before" +
                            " bboxAfter=${itemFrame.boundingBox} expectedGrid=$cells restoreElapsedTicks=${Bukkit.getCurrentTick() - state.tick}"
                )
            } catch (failure: Throwable) {
                state.error = if (failure is InvocationTargetException && failure.cause != null) failure.cause!!.javaClass.simpleName
                else java.lang.String.valueOf(failure.message)
                warn("HITBOX_RESTORE_FAILED uuid=${state.uuid} targetId=${state.target} reason=${state.error} fallback=false")
            } finally {
                if (!state.manual) states.remove(state.uuid, state)
            }
        } catch (failure: Throwable) {
            warn("HITBOX_RESTORE_FAILED reason=${failure.javaClass.simpleName} fallback=false")
        }
    }

    @JvmStatic
    fun stabilityReason(entity: Entity, target: String, base: Location): String {
        val state = states[entity.uniqueId]
        if (state == null) {
            if (prepareFailures.containsKey(entity.uniqueId)) {
                warn("HITBOX_FAIL_SAFE uuid=${entity.uniqueId} reason=hitbox_prepare_failed_fail_safe action=release_lock_without_claiming_stable")
                return "hitbox_prepare_failed_fail_safe"
            }
            return "stable_after_itemsadder_window"
        }

        var reason = "hitbox_validation_failed_fail_safe"
        try {
            val expected = state.expected
            if (state.target != normalize(target) || state.error != null || expected == null) reason = "hitbox_restore_failed_fail_safe"
            else if (entity !is ItemFrame || !identity(state, entity)) reason = "hitbox_identity_failed_fail_safe"
            else {
                val geometry = grid(entity.boundingBox)
                val actual = LinkedHashSet<Cell>()
                val world: World = entity.world
                for (cell in expected) {
                    if (world.isChunkLoaded(cell.x shr 4, cell.z shr 4) &&
                        world.getBlockAt(cell.x, cell.y, cell.z).type == Material.BARRIER) actual += cell
                }
                val equal = expected == geometry && expected == actual
                reason = if (equal) "stable_after_itemsadder_window" else "hitbox_incomplete_fail_safe"
                if (DebugSettings.isItemsAdderEnabled()) log(
                    "HITBOX_VALIDATION uuid=${state.uuid} targetId=${state.target} expectedGrid=$expected" +
                            " geometryGrid=$geometry actualBarrierGrid=$actual equal=$equal reason=$reason"
                )
            }
        } catch (_: Throwable) {
            reason = "hitbox_validation_exception_fail_safe"
        }
        if (reason != "stable_after_itemsadder_window") {
            warn("HITBOX_FAIL_SAFE uuid=${state.uuid} targetId=${state.target} reason=$reason restoreError=${state.error} action=release_lock_without_claiming_stable")
        }
        return reason
    }

    @JvmStatic
    fun released(released: Boolean, lease: FurnitureTransitionGate.Lease) {
        if (released) {
            states.remove(lease.uuid)
            prepareFailures.remove(lease.uuid)
        }
    }
}