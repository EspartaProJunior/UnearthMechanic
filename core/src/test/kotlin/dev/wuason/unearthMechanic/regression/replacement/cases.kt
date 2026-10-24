package dev.wuason.unearthMechanic.regression.replacement

import dev.lone.itemsadder.api.CustomFurniture
import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.Rotation
import org.bukkit.block.BlockFace
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.EntityType
import org.mockito.Mockito.*
import java.util.UUID

fun runCases() {
    val cases = listOf("south", "west", "north", "east", "yaw", "pitch", "position", "world",
        "uuid", "invalid", "dead", "wrong-id", "unresolved", "different-entity", "block-id",
        "exception", "missing-api", "hitbox", "actions", "missing-config", "missing-target", "frame-rotation", "frame-facing", "legitimate-drops", "v3-valid-remap", "v3-inplace", "v3-wrong-id", "v3-uuid", "v3-rotation", "lookup-error-before", "lookup-error-after")
    for (case in cases) {
        val scenario = when(case) {
            "v3-valid-remap" -> "block-id"
            "v3-inplace" -> "legitimate-drops"
            "v3-wrong-id" -> "wrong-id"
            "v3-uuid" -> "uuid"
            "v3-rotation" -> "frame-rotation"
            else -> case
        }
        val world = mock(World::class.java)
        `when`(world.uid).thenReturn(UUID.randomUUID())
        val block = mock(Block::class.java)
        val base = Location(world, 0.0, 64.0, 0.0)
        `when`(world.getBlockAt(0, 64, 0)).thenReturn(block)
        `when`(world.getBlockAt(any(Location::class.java))).thenReturn(block)
        `when`(block.location).thenReturn(base)
        val yaw = when(scenario) { "south" -> 0f; "north" -> 180f; "east" -> -90f; else -> 90f }
        var pose = Location(world, 0.5, 64.0, 0.5, yaw, 0f)
        var uuid = UUID.randomUUID()
        val originalUuid = uuid
        var valid = true
        var dead = false
        var replaced = false
        var removals = 0
        val isFrame = scenario in listOf("south", "west", "north", "east", "frame-rotation", "frame-facing", "legitimate-drops", "v3-valid-remap", "v3-inplace", "v3-wrong-id", "v3-uuid", "v3-rotation", "lookup-error-before", "lookup-error-after")
        var frameRotation = when(scenario) { "south" -> Rotation.NONE; "north" -> Rotation.FLIPPED; "east" -> Rotation.COUNTER_CLOCKWISE; else -> Rotation.CLOCKWISE }
        val originalFrameRotation = frameRotation
        var facing = BlockFace.UP
        val entity: Entity = if(isFrame) mock(ItemFrame::class.java) else mock(ItemDisplay::class.java)
        if(entity is ItemFrame) {
            pose.yaw = 0f; pose.pitch = -90f
            `when`(entity.rotation).thenAnswer { frameRotation }
            `when`(entity.facing).thenAnswer { facing }
        }
        `when`(entity.uniqueId).thenAnswer { uuid }
        `when`(entity.location).thenAnswer { pose.clone() }
        `when`(entity.isValid).thenAnswer { valid }
        `when`(entity.isDead).thenAnswer { dead }
        `when`(entity.type).thenReturn(if(isFrame) EntityType.ITEM_FRAME else EntityType.ITEM_DISPLAY)
        val furniture = mock(CustomFurniture::class.java)
        val target = mock(CustomStack::class.java)
        val oldConfig = YamlConfiguration()
        val newConfig = YamlConfiguration()
        oldConfig.set("items.old.resource.model_path", "closed")
        newConfig.set("items.new.resource.model_path", "open")
        oldConfig.set("items.old.behaviours.furniture.entity", "item_display")
        newConfig.set("items.new.behaviours.furniture.entity", "item_display")
        oldConfig.set("items.old.behaviours.furniture.solid", true)
        newConfig.set("items.new.behaviours.furniture.solid", scenario != "hitbox")
        if(scenario == "legitimate-drops") {
            oldConfig.set("items.old.behaviours.furniture.cancel_drop", false)
            newConfig.set("items.new.behaviours.furniture.cancel_drop", true)
            oldConfig.set("items.old.events.placed_furniture.break.drop_item.item", "test:old")
            newConfig.set("items.new.events.placed_furniture.break.drop_item.item", "test:new")
        }
        if(scenario == "actions") newConfig.set("items.new.events.interact.execute_commands.cmd.command", "test")
        if(scenario == "missing-config") newConfig.set("items.new", null)
        `when`(furniture.id).thenReturn("old")
        `when`(target.id).thenReturn("new")
        `when`(furniture.config).thenReturn(oldConfig)
        `when`(target.config).thenReturn(newConfig)
        `when`(furniture.entity).thenReturn(entity)
        `when`(furniture.namespacedID).thenAnswer { if(replaced && scenario != "wrong-id") "test:new" else "test:old" }
        doAnswer {
            replaced = true
            when(scenario) {
                "frame-rotation" -> frameRotation = Rotation.FLIPPED
                "frame-facing" -> facing = BlockFace.DOWN
                "yaw" -> pose.yaw = 0f
                "pitch" -> pose.pitch = 10f
                "position" -> pose.x += 1.0
                "world" -> pose.world = mock(World::class.java)
                "uuid" -> uuid = UUID.randomUUID()
                "invalid" -> valid = false
                "dead" -> dead = true
                "exception" -> throw IllegalStateException("replacement failure")
                "missing-api" -> throw NoSuchMethodError("replaceFurniture")
                "different-entity" -> {
                    val other = mock(Entity::class.java)
                    `when`(other.uniqueId).thenReturn(UUID.randomUUID())
                    `when`(furniture.entity).thenReturn(other)
                }
            }
            null
        }.`when`(furniture).replaceFurniture("test:new")
        doAnswer { removals++; valid = false; null }.`when`(furniture).remove(false)
        mockStatic(CustomFurniture::class.java).use { cf ->
            mockStatic(CustomStack::class.java).use { cs ->
                cf.`when`<CustomFurniture?> { CustomFurniture.byAlreadySpawned(entity) }.thenAnswer {
                    if(replaced && scenario == "unresolved") null else furniture
                }
                cf.`when`<CustomFurniture?> { CustomFurniture.byAlreadySpawned(block) }.thenAnswer {
                    if(scenario == "lookup-error-before" || replaced && scenario == "lookup-error-after") throw IllegalStateException("spatial lookup failed")
                    if(replaced && scenario == "block-id") null else furniture
                }
                cs.`when`<CustomStack?> { CustomStack.getInstance("test:new") }.thenReturn(if(scenario == "missing-target") null else target)
                val harness = Harness()
                val success = harness.tryReplaceFurniture(entity, "ia:test:new", base)
                val expected = scenario in listOf("south", "west", "north", "east", "hitbox", "actions", "missing-config", "legitimate-drops", "block-id", "lookup-error-before", "lookup-error-after")
                check(success == expected) { "$scenario: expected=$expected actual=$success" }
                if(success) {
                    check(replaced)
                    verify(furniture).replaceFurniture("test:new")
                    verify(furniture, never()).remove(false)
                    verify(entity, never()).remove()
                    check(removals == 0 && uuid == originalUuid && pose.yaw == (if(isFrame) 0f else yaw))
                    check(harness.bases[uuid] == base)
                    if(entity is ItemFrame) {
                        check(frameRotation == originalFrameRotation && facing == BlockFace.UP)
                        verify(entity, never()).setRotation(any(Rotation::class.java))
                        verify(entity, never()).setFacingDirection(any(BlockFace::class.java))
                    }
                    verify(entity, never()).setRotation(anyFloat(), anyFloat())
                } else if(replaced) {
                    check(removals >= 1) { "$scenario: failed replacement was not removed" }
                    check(harness.rotationMap[base] == (if(isFrame) (0f to -90f) else (yaw to 0f)))
                } else check(removals == 0)
            }
        }
        println("PASS $case")
    }
    println("${cases.size} focused replacement tests passed (mocked API; no server integration)")
}
