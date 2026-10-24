package dev.wuason.unearthMechanic.regression.transitions

import dev.lone.itemsadder.api.*
import dev.lone.itemsadder.api.Events.*
import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.system.*
import dev.wuason.unearthMechanic.config.*
import dev.wuason.unearthMechanic.system.compatibilities.*
import dev.wuason.unearthMechanic.system.compatibilities.ia.*
import dev.wuason.unearthMechanic.utils.FoliaUtils
import com.tcoded.folialib.wrapper.task.WrappedTask
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.*
import org.bukkit.event.Event
import org.mockito.Mockito.*
import org.objenesis.ObjenesisStd
import sun.misc.Unsafe
import java.util.*
import java.util.concurrent.*

fun <T> arg(): T = org.mockito.Mockito.any<T>()
fun <T> equal(value:T):T { org.mockito.Mockito.eq(value); return value }
fun runCases() {
    var passed=0
    fun pass(s:String){passed++;println("PASS $s")}
    check(FurnitureTransitionGate.settlingTicks(org.bukkit.util.BoundingBox(0.0,0.0,0.0,1.0,1.0,1.0))==6L)
    pass("one-block furniture settling derives from +5 operation and next tick boundary")
    check(FurnitureTransitionGate.settlingTicks(org.bukkit.util.BoundingBox(0.0,0.0,0.0,4.0,4.0,4.0))==19L)
    pass("64-location hitbox includes IA five-locations-per-tick nested batches")
    val gate=FurnitureTransitionGate()
    val base=Location(null,0.0,64.0,0.0)
    val x=UUID.randomUUID();val y=UUID.randomUUID()
    val ev=mock(FurnitureInteractEvent::class.java)
    val first=gate.acquire(x,ev,base,0)!!
    check(gate.acquire(x,mock(Event::class.java),base,1)==null)
    pass("first lease acquired, second event for same UUID rejected")
    val second=gate.acquire(y,mock(Event::class.java),base.clone().add(2.0,0.0,0.0),1)!!
    check(gate.owns(first)&&gate.owns(second));pass("other furniture independently acquires lease")
    gate.release(first);val replacement=gate.acquire(x,mock(Event::class.java),base,2)!!
    check(!gate.release(first)&&gate.owns(replacement));pass("stale callback cannot release newer lease for same UUID")
    gate.release(replacement);gate.release(second)
    val pool=Executors.newFixedThreadPool(8);val barrier=CyclicBarrier(8)
    val results=(1..8).map{pool.submit<Boolean>{barrier.await();gate.acquire(x,mock(Event::class.java),base,3)!=null}}.map{it.get()}
    pool.shutdown();check(results.count{it}==1);gate.release(gate.current(x)!!)
    pass("eight simultaneous acquisition attempts admit exactly one owner")

    val unsafe=Unsafe::class.java.getDeclaredField("theUnsafe").apply{isAccessible=true}.get(null) as Unsafe
    fun replaceStatic(owner:Class<*>,name:String,value:Any):Any {
        val f=owner.getDeclaredField(name);val b=unsafe.staticFieldBase(f);val o=unsafe.staticFieldOffset(f)
        val old=unsafe.getObject(b,o);unsafe.putObject(b,o,value);return old
    }
    val folia=mock(FoliaUtils::class.java)
    val oldFolia=replaceStatic(FoliaUtils::class.java,"INSTANCE",folia)
    val data=mock(StageData.Companion.javaClass)
    `when`(data.compare(arg<StageData>(),arg<Location>())).thenReturn(true)
    val oldData=replaceStatic(StageData::class.java,"Companion",data)
    try {
        var tick=0;var order=0
        var observe:()->Unit = {}
        data class Job(val at:Int,val order:Int,val callback:()->Unit)
        val jobs=PriorityQueue<Job>(compareBy<Job>{it.at}.thenBy{it.order})
        fun schedule(delay:Int, action:()->Unit){jobs.add(Job(tick+delay,order++,action))}
        fun advance(until:Int){while(jobs.isNotEmpty()&&jobs.peek().at<=until){val j=jobs.remove();tick=j.at;j.callback();observe()};tick=until}
        `when`(folia.runLater(anyLong(),arg())).thenAnswer{a->schedule(a.getArgument<Long>(0).toInt()){a.getArgument<()->Unit>(1).invoke()};mock(WrappedTask::class.java)}
        doAnswer{it.getArgument<()->Unit>(1).invoke();observe();null}.`when`(folia).runAtLocation(arg<Location>(),arg())
        `when`(folia.runTimerAtLocation(arg<Location>(),anyLong(),anyLong(),arg())).thenAnswer{a->
            val task=mock(WrappedTask::class.java);var cancelled=false
            doAnswer{cancelled=true;null}.`when`(task).cancel()
            fun repeatJob(){if(!cancelled){a.getArgument<()->Unit>(3).invoke();if(!cancelled)schedule(1){repeatJob()}}}
            schedule(a.getArgument<Long>(1).toInt()){repeatJob()};task
        }
        mockStatic(Bukkit::class.java).use { b ->
            b.`when`<Int>{Bukkit.getCurrentTick()}.thenAnswer{tick}
            val world=mock(World::class.java);`when`(world.uid).thenReturn(UUID.randomUUID())
            `when`(world.getBlockAt(arg<Location>())).thenAnswer{a->val l=a.getArgument<Location>(0);mock(Block::class.java).also{`when`(it.location).thenReturn(Location(world,l.blockX.toDouble(),l.blockY.toDouble(),l.blockZ.toDouble()))}}
            val manager=mock(StageManager::class.java);val delays=HashMap<Location,WrappedTask>();val activeSequences=Collections.synchronizedSet(mutableSetOf<Location>())
            `when`(manager.getDelays()).thenReturn(delays)
            `when`(manager.activeSequences).thenReturn(activeSequences)
            val comp=ObjenesisStd().newInstance(ItemsAdderImpl::class.java)
            fun field(n:String,v:Any){ItemsAdderImpl::class.java.getDeclaredField(n).apply{isAccessible=true}.set(comp,v)}
            field("stageManager",manager);field("removedLocations",Collections.synchronizedSet(mutableSetOf<Location>()))
            field("furnitureTransitions",FurnitureTransitionGate());field("lastFurnitureInteractionTick",Collections.synchronizedMap(mutableMapOf<UUID,Int>()))
            val leases=ItemsAdderImpl::class.java.getDeclaredField("furnitureTransitions").apply{isAccessible=true}.get(comp) as FurnitureTransitionGate
            val tool=mock(LiveTool::class.java);`when`(tool.isValid()).thenReturn(true)
            val iTool=mock(ITool::class.java);`when`(tool.getITool()).thenReturn(iTool)
            val validation=mock(Validation::class.java);`when`(validation.validate()).thenReturn(true)
            val generic=mock(IGeneric::class.java)
            var configuredDelay=4L;val delayedStage=mock(Stage::class.java);`when`(delayedStage.getMaxCorrectDelay(tool)).thenAnswer{configuredDelay}
            val furnitureStage=mock(IFurnitureStage::class.java)
            val adapter=mock(AdapterData::class.java);`when`(adapter.id).thenReturn("test:new")
            val entities=mutableMapOf<UUID,Entity>();val wrappers=mutableMapOf<UUID,CustomFurniture>();val ids=mutableMapOf<UUID,String>()
            val exposedIds=mutableMapOf<UUID,String>()
            val players=List(2){mock(Player::class.java).also{p->`when`(p.uniqueId).thenReturn(UUID.randomUUID());`when`(p.isOnline).thenReturn(true)}}
            fun frame(offset:Int):ItemFrame {
                val e=mock(ItemFrame::class.java);val id=UUID.randomUUID();entities[id]=e;ids[id]="test:old"
                `when`(e.uniqueId).thenReturn(id);`when`(e.location).thenAnswer{Location(world,offset+.5,64.03125,.5,0f,-90f)}
                `when`(e.isValid).thenReturn(true);`when`(e.type).thenReturn(EntityType.ITEM_FRAME)
                `when`(e.facing).thenReturn(BlockFace.UP);`when`(e.rotation).thenReturn(Rotation.CLOCKWISE)
                val f=mock(CustomFurniture::class.java);wrappers[id]=f
                `when`(f.entity).thenReturn(e);`when`(f.namespacedID).thenAnswer{exposedIds[id] ?: ids[id]};`when`(f.id).thenAnswer{ids[id]!!.substringAfter(":")}
                return e
            }
            val frameX=frame(0);val frameY=frame(4)
            `when`(world.getEntity(any(UUID::class.java))).thenAnswer{entities[it.getArgument(0)]}
            fun input(e:Entity,p:Player=players[0]):FurnitureInteractEvent=mock(FurnitureInteractEvent::class.java).also{
                val uuid=e.uniqueId
                `when`(it.bukkitEntity).thenReturn(e);`when`(it.furniture).thenReturn(wrappers[uuid]);`when`(it.namespacedID).thenAnswer{exposedIds[uuid] ?: ids[uuid]};`when`(it.player).thenReturn(p)
            }
            var accepted=0;var replacements=0;var activeReplace=0;var maxReplace=0;var activeDelays=0;var maxDelays=0
            var maxTransitions=0
            val inFlight=mutableMapOf<UUID,Int>()
            observe = { inFlight.keys.toList().forEach { if(leases.current(it)==null) inFlight.remove(it) } }
            var failDispatch=false;var rejectApply=false
            val registered=mock(CustomStack::class.java);var requestedTarget="test:new"
            val replacementConfig=org.bukkit.configuration.file.YamlConfiguration()
            for(id in listOf("old","new","extractor_llenar_1_1","extractor_llenar_1_2"))replacementConfig.set("items.$id.functional_marker","same")
            `when`(registered.id).thenAnswer{requestedTarget.substringAfter(":")};`when`(registered.config).thenReturn(replacementConfig)
            for(f in wrappers.values)`when`(f.config).thenReturn(replacementConfig)
            mockStatic(CustomFurniture::class.java).use { cf -> mockStatic(CustomStack::class.java).use { cs ->
                cf.`when`<CustomFurniture>{CustomFurniture.byAlreadySpawned(any(Entity::class.java))}.thenAnswer{wrappers[it.getArgument<Entity>(0).uniqueId]}
                cs.`when`<CustomStack>{CustomStack.getInstance(anyString())}.thenAnswer{requestedTarget=it.getArgument(0);registered}
                for((uuid,f) in wrappers)doAnswer{
                    replacements++;activeReplace++;maxReplace=maxOf(maxReplace,activeReplace);ids[uuid]=it.getArgument(0)
                    // Simulate the bytecode-proven +5 deferred barrier operation after API return.
                    schedule(5){activeReplace--};null
                }.`when`(f).replaceFurniture(anyString())
                doAnswer{a->
                    val p=a.getArgument<Player>(0);val l=a.getArgument<Location>(2);val event=a.getArgument<FurnitureInteractEvent>(3)
                    accepted++;if(failDispatch)throw IllegalStateException("dispatch failure")
                    if(!rejectApply){
                        val uuid=event.bukkitEntity.uniqueId
                        inFlight[uuid]=(inFlight[uuid]?:0)+1
                        maxTransitions=maxOf(maxTransitions,inFlight[uuid]!!)
                        check(inFlight[uuid]==1)
                        val delay=DelayTask(manager,p,comp,event,l,tool,generic,delayedStage,validation,null)
                        delay.start()
                    }
                    FurnitureTransitionHooks.dispatchFinished(manager,event,comp,l);null
                }.`when`(manager).interact(arg<Player>(),arg<String>(),arg<Location>(),arg<Event>(),equal(comp))
                var oldStageDispatches=0
                doAnswer{a->if(a.getArgument<Long>(0)>=configuredDelay-1L){
                    oldStageDispatches++
                    activeDelays=delays.size;maxDelays=maxOf(maxDelays,activeDelays)
                    comp.handleStage(a.getArgument(1),adapter,a.getArgument(3),a.getArgument(4),tool,generic,furnitureStage)
                };null}.`when`(manager).onProcessStage(anyLong(),arg<Player>(),equal(comp),arg<Event>(),arg<Location>(),equal(tool),equal(generic),equal(delayedStage),equal(validation),isNull())
                val start=input(frameX);comp.onInteractFurniture(start)
                check(leases.current(frameX.uniqueId)!=null);pass("actual first interaction acquires UUID lease before real DelayTask.start")
                repeat(9){comp.onInteractFurniture(input(frameX))};check(accepted==1&&delays.size==1)
                pass("ten same-tick interactions create exactly one real DelayTask")
                advance(3);check(delays.isEmpty()&&leases.current(frameX.uniqueId)?.phase==FurnitureTransitionGate.Phase.SCHEDULED)
                pass("real DelayTask.cancel retains owner while furniture application is pending")
                comp.onInteractFurniture(input(frameX));check(accepted==1);pass("click in delay-to-application gap rejected")
                advance(5);check(replacements==1&&activeReplace==1&&leases.current(frameX.uniqueId)?.phase==FurnitureTransitionGate.Phase.SETTLING)
                pass("actual replacement returns while deferred IA work remains protected")
                advance(10);comp.onInteractFurniture(input(frameX));check(accepted==1&&activeReplace==0)
                pass("+5 tick still locked even after deferred work finishes")
                advance(11);check(leases.current(frameX.uniqueId)==null);pass("+6 validation releases exact UUID after settling")
                comp.onInteractFurniture(input(frameX));check(accepted==2);pass("new click accepted after unlock")
                comp.onInteractFurniture(input(frameY,players[1]));check(accepted==3&&delays.size==2)
                pass("different furniture and different player proceed while X is locked")
                advance(22);check(leases.current(frameX.uniqueId)==null&&leases.current(frameY.uniqueId)==null)
                for(interval in 1..4){
                    maxDelays=0;maxReplace=0;maxTransitions=0;val beforeAccepted=accepted;val beforeReplace=replacements
                    repeat(100){advance(tick+interval);comp.onInteractFurniture(input(frameX))}
                    advance(tick+20)
                    check(maxTransitions==1&&maxDelays==1&&maxReplace==1&&activeReplace==0&&delays.isEmpty()&&leases.current(frameX.uniqueId)==null)
                    check(frameX.isValid&&!frameX.isDead&&frameX.uniqueId in wrappers)
                    verify(frameX,never()).remove();verify(frameX,never()).setRotation(anyFloat(),anyFloat())
                    println("STRESS interval=$interval clicks=100 accepted=${accepted-beforeAccepted} replace=${replacements-beforeReplace} maxConcurrentTransitions=$maxTransitions maxConcurrentDelayTasks=$maxDelays maxConcurrentReplaceFurniture=$maxReplace")
                    pass("100 clicks every $interval ticks preserve valid unique furniture and serialize deferred replacements")
                }
                failDispatch=true
                try{comp.onInteractFurniture(input(frameX));error("expected failure")}catch(_:IllegalStateException){}
                check(leases.current(frameX.uniqueId)==null);failDispatch=false
                pass("dispatch exception releases UUID lease")
                rejectApply=true;comp.onInteractFurniture(input(frameX));check(leases.current(frameX.uniqueId)==null);rejectApply=false
                pass("non-applicable transition releases without scheduling")
                comp.onInteractFurniture(input(frameX));val broken=mock(FurnitureBreakEvent::class.java);`when`(broken.bukkitEntity).thenReturn(frameX)
                comp.onFurnitureBreakCommitted(broken);check(leases.current(frameX.uniqueId)==null)
                pass("committed break invalidates lease and pending old callback ownership")
                observe()
                // Cancel the externally-held task: watchdog must release instead of retaining DELAY forever.
                delays.remove(frameX.location.block.location)?.cancel();advance(tick+20)
                val placed=mock(FurniturePlaceSuccessEvent::class.java);`when`(placed.bukkitEntity).thenReturn(frameX);`when`(placed.namespacedID).thenReturn("test:new")
                `when`(frameX.isValid).thenReturn(false);comp.onFurniturePlaceSuccess(placed)
                check(!comp.isRemoving(frameX.location.block.location));`when`(frameX.isValid).thenReturn(true)
                pass("V4.1 invalid-alive placement cleanup remains intact after break")
                comp.onInteractFurniture(input(frameX));delays.remove(frameX.location.block.location)?.cancel();advance(tick+6)
                check(leases.current(frameX.uniqueId)==null);pass("external task cancellation released by configured-duration watchdog")
                `when`(validation.validate()).thenReturn(false)
                comp.onInteractFurniture(input(frameX));advance(tick+1)
                check(leases.current(frameX.uniqueId)==null&&delays.isEmpty())
                pass("real DelayTask validation failure cancels and releases lease")
                `when`(validation.validate()).thenReturn(true)
                comp.onInteractFurniture(input(frameX));`when`(players[0].isOnline).thenReturn(false);advance(tick+8)
                check(leases.current(frameX.uniqueId)==null);`when`(players[0].isOnline).thenReturn(true)
                pass("disconnect before scheduled application aborts and releases")
                comp.onInteractFurniture(input(frameX));advance(tick+5)
                `when`(frameX.isDead).thenReturn(true);advance(tick+6)
                check(leases.current(frameX.uniqueId)==null);`when`(frameX.isDead).thenReturn(false)
                pass("dead entity during settling fails validation and releases without rescue")
                comp.onInteractFurniture(input(frameX));advance(tick+5)
                ids[frameX.uniqueId]="test:unexpected";advance(tick+6)
                check(leases.current(frameX.uniqueId)==null);ids[frameX.uniqueId]="test:new"
                pass("settling target ID mismatch releases without replacing another entity")
                `when`(frameX.isValid).thenReturn(false);val oldAccepted=accepted;comp.onInteractFurniture(input(frameX))
                check(accepted==oldAccepted&&leases.current(frameX.uniqueId)==null);`when`(frameX.isValid).thenReturn(true)
                pass("invalid entity aborts before StageManager without removal or spawn")

                // Runtime race: a valid manual delay for A is superseded by the sequence A -> B.
                advance(tick+1);configuredDelay=2L;val raceBase=frameX.location.block.location
                fun furnitureRemovalCalls()=wrappers.values.sumOf{f->mockingDetails(f).invocations.count{it.method.name=="remove"}}
                ids[frameX.uniqueId]="glozz:extractor_llenar_1_1";val dispatchesBeforeRace=oldStageDispatches;val removalsBeforeRace=furnitureRemovalCalls()
                val manualRace=input(frameX);comp.onInteractFurniture(manualRace);val raceStart=tick;advance(raceStart)
                check(leases.current(frameX.uniqueId)?.expectedSourceId=="glozz:extractor_llenar_1_1")
                pass("manual delayed interaction captures UUID and exact source furniture ID")
                activeSequences.add(raceBase)
                // Mirrors sequence PRE-LOCK one tick before the replacement.
                comp.onFurnitureSequenceClaim(frameX.uniqueId,"glozz:extractor_llenar_1_1")
                val sequenceUuid=comp.placeNewFurnitureThenRemoveOld(raceBase,"glozz:extractor_llenar_1_1","glozz:extractor_llenar_1_2",frameX.uniqueId)
                activeSequences.remove(raceBase);advance(raceStart+6)
                check(sequenceUuid==frameX.uniqueId&&ids[frameX.uniqueId]=="glozz:extractor_llenar_1_2"&&frameX.isValid&&!frameX.isDead)
                pass("sequence replacement wins race and preserves B with the same UUID")
                check(oldStageDispatches==dispatchesBeforeRace&&furnitureRemovalCalls()==removalsBeforeRace&&leases.current(frameX.uniqueId)==null&&delays.isEmpty())
                pass("stale manual DelayTask aborts before old handleStage and releases its lease")
                verify(frameX,never()).remove()
                pass("stale manual DelayTask never removes the sequence result or creates a null-UUID sequence")

                // Exact production order and IA 4.0.18 identity shape:
                // N manual starts, N+1 manual tick runs then sequence pre-locks,
                // N+2 sequence replaces before the manual callback wakes.
                advance(tick+15);ids[frameX.uniqueId]="glozz:extractor_llenar_1_1"
                exposedIds[frameX.uniqueId]="glozz"
                val realisticBefore=oldStageDispatches
                val realisticRemovals=furnitureRemovalCalls()
                val realisticEvent=input(frameX)
                comp.onInteractFurniture(realisticEvent)
                val n=tick
                val realisticLease=leases.current(frameX.uniqueId)!!
                check(realisticLease.expectedSourceId=="glozz:extractor_llenar_1_1")
                schedule(1) {
                    activeSequences.add(raceBase)
                    comp.onFurnitureSequenceClaim(frameX.uniqueId,"glozz","sequence_prelock")
                }
                schedule(2) {
                    ids[frameX.uniqueId]="glozz:extractor_llenar_1_2"
                    activeSequences.remove(raceBase)
                }
                advance(n+2)
                check(realisticLease.stale&&realisticLease.generation>0&&leases.current(frameX.uniqueId)==null)
                check(ids[frameX.uniqueId]=="glozz:extractor_llenar_1_2"&&frameX.isValid&&!frameX.isDead)
                check(oldStageDispatches==realisticBefore&&furnitureRemovalCalls()==realisticRemovals&&delays.isEmpty())
                check(leases.recordForEvent(realisticEvent)==null)
                pass("N/N+1/N+2 scheduler race aborts collapsed-ID manual callback before process or apply")
                exposedIds.remove(frameX.uniqueId)

                // Exercise the real timer and deferred handleStage ordering around the boundary.
                configuredDelay=4L
                for(offset in -3..3){
                    advance(tick+15);ids[frameX.uniqueId]="glozz:extractor_llenar_1_1"
                    val before=oldStageDispatches;comp.onInteractFurniture(input(frameX));val started=tick
                    val sequenceAt=started+3+offset;advance(sequenceAt)
                    activeSequences.add(raceBase);comp.onFurnitureSequenceClaim(frameX.uniqueId,"glozz:extractor_llenar_1_1")
                    val result=comp.placeNewFurnitureThenRemoveOld(raceBase,"glozz:extractor_llenar_1_1","glozz:extractor_llenar_1_2",frameX.uniqueId)
                    activeSequences.remove(raceBase);advance(started+15)
                    check(frameX.isValid&&!frameX.isDead&&frameX.uniqueId in wrappers&&ids[frameX.uniqueId] in setOf("glozz:extractor_llenar_1_2","test:new"))
                    check((result==frameX.uniqueId&&ids[frameX.uniqueId]=="glozz:extractor_llenar_1_2")||(result==null&&oldStageDispatches>before))
                    check(leases.current(frameX.uniqueId)==null&&delays.isEmpty())
                }
                pass("all real timer orders from -3 through +3 ticks preserve one valid furniture and deterministic ownership")

                configuredDelay=2L;val spamDispatches=oldStageDispatches
                repeat(100){
                    ids[frameX.uniqueId]="glozz:extractor_llenar_1_1";val started=tick
                    comp.onInteractFurniture(input(frameX));repeat(9){comp.onInteractFurniture(input(frameX))};advance(started)
                    activeSequences.add(raceBase);comp.onFurnitureSequenceClaim(frameX.uniqueId,"glozz:extractor_llenar_1_1")
                    check(comp.placeNewFurnitureThenRemoveOld(raceBase,"glozz:extractor_llenar_1_1","glozz:extractor_llenar_1_2",frameX.uniqueId)==frameX.uniqueId)
                    activeSequences.remove(raceBase);advance(started+7)
                    check(ids[frameX.uniqueId]=="glozz:extractor_llenar_1_2"&&leases.current(frameX.uniqueId)==null&&delays.isEmpty())
                }
                check(oldStageDispatches==spamDispatches&&furnitureRemovalCalls()==removalsBeforeRace)
                pass("100 sustained ten-click races keep B and never dispatch the stale manual stage")

                configuredDelay=4L;ids[frameX.uniqueId]="test:new"
                verify(world,never()).getNearbyEntities(arg<Location>(),anyDouble(),anyDouble(),anyDouble())
                verify(frameX,never()).remove();verify(frameX,never()).setRotation(anyFloat(),anyFloat())
                pass("gate never selects nearby entities or changes frame orientation")
            }}
        }
    } finally {replaceStatic(FoliaUtils::class.java,"INSTANCE",oldFolia);replaceStatic(StageData::class.java,"Companion",oldData)}
    println("$passed V4.2 transition tests passed (real handlers/DelayTask; mocked StageManager application and IA scheduler)")
}
