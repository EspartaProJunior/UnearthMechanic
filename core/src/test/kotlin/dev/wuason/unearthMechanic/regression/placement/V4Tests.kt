package dev.wuason.unearthMechanic.regression.placement

import dev.lone.itemsadder.api.CustomFurniture
import dev.lone.itemsadder.api.Events.*
import dev.wuason.unearthMechanic.system.StageData
import dev.wuason.unearthMechanic.system.StageManager
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.IStage
import dev.wuason.unearthMechanic.events.FakePlayerInteractEvent
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import dev.wuason.unearthMechanic.system.compatibilities.ia.ItemsAdderImpl
import dev.wuason.unearthMechanic.system.features.SwingHandFeature
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.entity.*
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.mockito.Mockito.*
import org.objenesis.ObjenesisStd
import java.util.*
import sun.misc.Unsafe

fun runCases() {
    val unsafe=Unsafe::class.java.getDeclaredField("theUnsafe").apply {isAccessible=true}.get(null) as Unsafe
    val companionField=StageData::class.java.getDeclaredField("Companion")
    val companionBase=unsafe.staticFieldBase(companionField)
    val companionOffset=unsafe.staticFieldOffset(companionField)
    val originalCompanion=StageData.Companion
    val stageDataMock=mock(originalCompanion.javaClass)
    unsafe.putObject(companionBase,companionOffset,stageDataMock)
    var count=0
    fun pass(label:String) {count++;println("PASS $label")}
    try {
        val world=mock(World::class.java)
        `when`(world.uid).thenReturn(UUID.randomUUID())
        val real=Location(world,512.0,23.0,2416.0)
        val adjacent=Location(world,512.0,23.0,2417.0)
        val stale=Location(world,512.0,23.0,2418.0)
        val block=mock(Block::class.java)
        `when`(block.location).thenAnswer {real.clone()}
        `when`(world.getBlockAt(512,23,2416)).thenReturn(block)
        `when`(world.getBlockAt(any(Location::class.java))).thenAnswer {
            val location=it.getArgument<Location>(0)
            if(location.blockX==512&&location.blockY==23&&location.blockZ==2416) block else {
                val otherBlock=mock(Block::class.java)
                `when`(otherBlock.location).thenReturn(Location(world,location.blockX.toDouble(),location.blockY.toDouble(),location.blockZ.toDouble()))
                otherBlock
            }
        }
        val old=mock(ItemFrame::class.java)
        val fresh=mock(ItemFrame::class.java)
        for(e in listOf(old,fresh)) {
            `when`(e.uniqueId).thenReturn(UUID.randomUUID())
            `when`(e.location).thenAnswer {Location(world,512.5,23.03125,2416.5,0f,-90f)}
            `when`(e.isValid).thenReturn(true)
            `when`(e.type).thenReturn(EntityType.ITEM_FRAME)
            `when`(e.rotation).thenReturn(Rotation.COUNTER_CLOCKWISE)
            `when`(e.facing).thenReturn(org.bukkit.block.BlockFace.UP)
        }
        val comp=ObjenesisStd().newInstance(ItemsAdderImpl::class.java)
        fun field(name:String,value:Any) {ItemsAdderImpl::class.java.getDeclaredField(name).apply{isAccessible=true}.set(comp,value)}
        field("furnitureTransitions",dev.wuason.unearthMechanic.system.compatibilities.ia.FurnitureTransitionGate())
        field("removedLocations",Collections.synchronizedSet(mutableSetOf<Location>()))
        field("lastFurnitureInteractionTick",Collections.synchronizedMap(mutableMapOf<UUID,Int>()))
        val manager=mock(StageManager::class.java)
        field("stageManager",manager)
        `when`(manager.getDelays()).thenReturn(java.util.HashMap())
        val player=mock(Player::class.java)
        `when`(player.uniqueId).thenReturn(UUID.randomUUID())
        val broken=mock(FurnitureBreakEvent::class.java)
        `when`(broken.bukkitEntity).thenReturn(old)
        comp.onFurnitureBreakCommitted(broken)
        check(comp.isRemoving(real))
        pass("committed break marks real location removing")
        comp.setRemoving(adjacent);comp.setRemoving(stale)
        val placed=mock(FurniturePlaceSuccessEvent::class.java)
        `when`(placed.bukkitEntity).thenReturn(fresh)
        `when`(placed.namespacedID).thenReturn("test:bin")
        comp.onFurniturePlaceSuccess(placed)
        check(old.uniqueId!=fresh.uniqueId&&!comp.isRemoving(real))
        pass("new UUID in success event immediately clears removing at real base")
        check(comp.isRemoving(adjacent)&&comp.isRemoving(stale))
        pass("nearby furniture and stale clicked location remain untouched")
        val bases=ItemsAdderImpl::class.java.getDeclaredField("furnitureBaseLocationByEntity").apply{isAccessible=true}.get(null) as Map<*,*>
        check(bases[fresh.uniqueId]==real)
        pass("exact event entity remembered at its own base")
        verify(world,never()).getNearbyEntities(any(Location::class.java),anyDouble(),anyDouble(),anyDouble())
        check(ItemsAdderImpl::class.java.declaredFields.none{it.name=="lastFurniturePlace"})
        check(ItemsAdderImpl::class.java.declaredMethods.none{it.name=="onFurniturePlace"||it.name=="onPlayerInteract"})
        pass("modern placement needs neither old clicked cache nor spatial search")
        val wrapper=mock(CustomFurniture::class.java)
        `when`(wrapper.entity).thenReturn(fresh)
        `when`(wrapper.namespacedID).thenReturn("test:bin")
        val input=mock(FurnitureInteractEvent::class.java)
        `when`(input.bukkitEntity).thenReturn(fresh)
        `when`(input.furniture).thenReturn(wrapper)
        `when`(input.namespacedID).thenReturn("test:bin")
        `when`(input.player).thenReturn(player)
        var forwarded=false
        doAnswer {
            val location=it.getArgument<Location>(2)
            check(location==real&&!comp.isRemoving(location))
            forwarded=true;null
        }.`when`(manager).interact(player,"ia:test:bin",real,input,comp)
        mockStatic(Bukkit::class.java).use { b ->
            b.`when`<Int> {Bukkit.getCurrentTick()}.thenReturn(200)
            comp.onInteractFurniture(input)
        }
        check(forwarded)
        pass("first click forwards with IS_REMOVING false at unchanged StageManager gate")
        comp.onFurniturePlaceSuccess(placed)
        check(!comp.isRemoving(real)&&comp.isRemoving(adjacent))
        pass("duplicate success delivery is idempotent")
        comp.setRemoving(real)
        `when`(fresh.isValid).thenReturn(false)
        comp.onFurniturePlaceSuccess(placed)
        check(!comp.isRemoving(real))
        pass("transient invalid alive entity immediately clears removal state")
        comp.setRemoving(real)
        `when`(fresh.isValid).thenReturn(true)
        `when`(fresh.isDead).thenReturn(true)
        comp.onFurniturePlaceSuccess(placed)
        check(comp.isRemoving(real))
        pass("dead new entity cannot clear removal state")
        `when`(fresh.isDead).thenReturn(false)
        // Model the actual lifecycle: success in tick N before insertion, valid in N+1.
        `when`(fresh.isValid).thenReturn(false)
        comp.setRemoving(real)
        comp.onFurniturePlaceSuccess(placed)
        check(!comp.isRemoving(real) && bases[fresh.uniqueId] == real)
        pass("tick N invalid alive success clears exact base without retries")
        `when`(fresh.isValid).thenReturn(true)
        forwarded=false
        mockStatic(Bukkit::class.java).use { b ->
            b.`when`<Int> {Bukkit.getCurrentTick()}.thenReturn(201)
            comp.onInteractFurniture(input)
        }
        check(forwarded)
        pass("same UUID becomes valid at N+1 and first subsequent click forwards")
        val unrelated=mock(ItemFrame::class.java)
        `when`(unrelated.uniqueId).thenReturn(UUID.randomUUID())
        `when`(unrelated.location).thenReturn(Location(world,512.5,23.03125,2417.5,180f,-90f))
        val otherPlaced=mock(FurniturePlaceSuccessEvent::class.java)
        `when`(otherPlaced.bukkitEntity).thenReturn(unrelated)
        `when`(otherPlaced.namespacedID).thenReturn("test:other")
        comp.setRemoving(real)
        comp.onFurniturePlaceSuccess(otherPlaced)
        check(comp.isRemoving(real) && !comp.isRemoving(adjacent))
        check(bases[unrelated.uniqueId]==adjacent && bases[fresh.uniqueId]==real)
        pass("different UUID only clears its own event base, never another placement")
        comp.onFurniturePlaceSuccess(otherPlaced)
        check(!comp.isRemoving(adjacent) && comp.isRemoving(real))
        pass("placement at unused base registers exact UUID and stays usable")
        comp.setRemoving(adjacent)
        repeat(5) { attempt ->
            comp.onFurnitureBreakCommitted(broken)
            `when`(fresh.isValid).thenReturn(false)
            comp.onFurniturePlaceSuccess(placed)
            check(!comp.isRemoving(real) && comp.isRemoving(adjacent) && comp.isRemoving(stale))
            `when`(fresh.isValid).thenReturn(true)
            forwarded=false
            mockStatic(Bukkit::class.java).use { b ->
                b.`when`<Int> {Bukkit.getCurrentTick()}.thenReturn(202+attempt)
                comp.onInteractFurniture(input)
            }
            check(forwarded)
        }
        pass("five break-replace cycles allow first click and preserve nearby removal flags")
        check(real==fresh.location.block.location && fresh.location!=real)
        check(real.yaw==0f && real.pitch==0f)
        pass("fractional entity coordinates and pitch normalize to identical old/new block key")
        val feature=SwingHandFeature()
        val loc=real
        val tool=mock(ILiveTool::class.java)
        val stage=mock(IStage::class.java)
        val generic=mock(IGeneric::class.java)
        feature.onPreApply(player,comp,input,loc,tool,stage,generic)
        verify(player,never()).swingMainHand()
        pass("ItemsAdder real furniture input omits artificial swing")
        // Multiple feature invocations with the same origin also omit the artificial swing.
        feature.onPreApply(player,comp,input,loc,tool,stage,generic)
        verify(player,never()).swingMainHand()
        pass("repeated pre-apply invocation still omits ItemsAdder furniture swing")
        val other=mock(ICompatibility::class.java)
        `when`(other.name()).thenReturn("Vanilla")
        val main=mock(PlayerInteractEvent::class.java)
        `when`(main.hand).thenReturn(EquipmentSlot.HAND)
        `when`(main.action).thenReturn(Action.RIGHT_CLICK_BLOCK)
        feature.onPreApply(player,other,main,loc,tool,stage,generic)
        verify(player,times(1)).swingMainHand()
        pass("vanilla main hand preserves artificial swing")
        clearInvocations(player)
        val off=mock(PlayerInteractEvent::class.java)
        `when`(off.hand).thenReturn(EquipmentSlot.OFF_HAND)
        feature.onPreApply(player,other,off,loc,tool,stage,generic)
        verify(player,never()).swingMainHand()
        pass("original off-hand exclusion preserved")
        feature.onPreApply(player,comp,mock(FakePlayerInteractEvent::class.java),loc,tool,stage,generic)
        verify(player,never()).swingMainHand()
        pass("original fake sequence exclusion preserved")
        feature.onPreApply(player,comp,mock(CustomBlockInteractEvent::class.java),loc,tool,stage,generic)
        verify(player,times(1)).swingMainHand()
        pass("ItemsAdder block interaction preserves existing swing")
        clearInvocations(player)
        feature.onPreApply(player,other,input,loc,tool,stage,generic)
        verify(player,times(1)).swingMainHand()
        pass("other compatibility retains prior behaviour even for furniture-shaped event")
        clearInvocations(player)
        feature.onPreApply(player,comp,main,loc,tool,stage,generic)
        verify(player,times(1)).swingMainHand()
        pass("ItemsAdder compatibility alone does not suppress other event origins")
        verify(fresh,never()).remove();verify(fresh,never()).setRotation(anyFloat(),anyFloat())
        pass("placement and swing changes perform no entity removal or orientation restoration")
    } finally {unsafe.putObject(companionBase,companionOffset,originalCompanion)}
    println("$count V4.1 placement/swing tests passed (mocked APIs; no server integration)")
}
