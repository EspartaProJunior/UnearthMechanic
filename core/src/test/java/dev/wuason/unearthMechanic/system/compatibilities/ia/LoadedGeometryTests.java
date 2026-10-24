package dev.wuason.unearthMechanic.system.compatibilities.ia;

import dev.lone.itemsadder.api.*;
import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent;
import dev.wuason.unearthMechanic.system.StageManager;
import dev.wuason.unearthMechanic.utils.DebugSettings;
import dev.wuason.unearthMechanic.utils.FoliaUtils;
import itemsadder.m.*;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.bukkit.event.world.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.*;
import org.bukkit.plugin.*;
import org.bukkit.util.BoundingBox;
import org.objenesis.ObjenesisStd;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import sun.misc.Unsafe;
import java.util.*;
import java.util.logging.*;
import static org.mockito.Mockito.*;

public class LoadedGeometryTests {
 static int checks;static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
 static void field(Object owner,String name,Object value)throws Exception{var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
 public static void runCases()throws Exception{
  checks=0;YamlConfiguration productionDebug=new YamlConfiguration();productionDebug.set("debug.enabled",false);productionDebug.set("debug.itemsadder",false);DebugSettings.configure(productionDebug);
  World world=mock(World.class);when(world.getName()).thenReturn("world");when(world.getUID()).thenReturn(UUID.randomUUID());when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
  ItemFrame entity=mock(ItemFrame.class);UUID uuid=UUID.randomUUID();Location p=new Location(world,594.5,23.03125,2500.5,0,-90);
  when(entity.getUniqueId()).thenReturn(uuid);when(entity.getWorld()).thenReturn(world);when(entity.getLocation()).thenReturn(p);when(entity.getType()).thenReturn(EntityType.ITEM_FRAME);when(entity.isValid()).thenReturn(true);when(entity.getFacing()).thenReturn(BlockFace.UP);when(entity.getRotation()).thenReturn(Rotation.NONE);
  ItemStack item=mock(ItemStack.class);when(item.clone()).thenReturn(item);when(entity.getItem()).thenReturn(item);
  PersistentDataContainer pdc=mock(PersistentDataContainer.class);when(entity.getPersistentDataContainer()).thenReturn(pdc);
  BoundingBox nativeBox=new BoundingBox(594.125,23,2500.125,594.875,23.0625,2500.875);BoundingBox[] box={nativeBox};when(entity.getBoundingBox()).thenAnswer(a->box[0]);
  YamlConfiguration cfg=new YamlConfiguration();cfg.set("items.cocina.behaviours.furniture.solid",true);
  CustomFurniture furniture=mock(CustomFurniture.class);when(furniture.getEntity()).thenReturn(entity);when(furniture.getNamespacedID()).thenReturn("glozz:cocina");when(furniture.getId()).thenReturn("cocina");when(furniture.getConfig()).thenReturn(cfg);
  js behaviour=new ObjenesisStd().newInstance(js.class);behaviour.xv=1;behaviour.xw=1;behaviour.xx=1;
  List<String> logs=new ArrayList<>();Logger logger=Logger.getLogger("v44-tests");logger.setUseParentHandlers(false);logger.addHandler(new Handler(){public void publish(LogRecord r){logs.add(r.getMessage());}public void flush(){}public void close(){}});
  PluginManager pm=mock(PluginManager.class);Plugin iaPlugin=mock(Plugin.class);when(iaPlugin.isEnabled()).thenReturn(true);when(iaPlugin.getDescription()).thenReturn(new PluginDescriptionFile("ItemsAdder","4.0.18","dev.lone.itemsadder.Main"));when(pm.getPlugin("ItemsAdder")).thenReturn(iaPlugin);
  Plugin owner=mock(Plugin.class);when(owner.isEnabled()).thenReturn(true);when(owner.getName()).thenReturn("UnearthMechanic");when(owner.getLogger()).thenReturn(logger);
  List<Function0<Unit>> ticks=new ArrayList<>();FoliaUtils folia=mock(FoliaUtils.class);doAnswer(a->{ticks.add(a.getArgument(0));return null;}).when(folia).runNextTick(any());doAnswer(a->{((Function0<Unit>)a.getArgument(1)).invoke();return null;}).when(folia).runAtLocation(any(),any());
  var uf=Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);Unsafe unsafe=(Unsafe)uf.get(null);var sf=FoliaUtils.class.getDeclaredField("INSTANCE");Object sb=unsafe.staticFieldBase(sf);long so=unsafe.staticFieldOffset(sf);Object old=unsafe.getObject(sb,so);unsafe.putObject(sb,so,folia);
  try(var b=mockStatic(Bukkit.class);var cf=mockStatic(CustomFurniture.class);var api=mockStatic(ItemsAdder.class);var resolver=mockStatic(jl.class);var nms=mockStatic(afz.class)){
   b.when(Bukkit::getLogger).thenReturn(logger);b.when(Bukkit::getPluginManager).thenReturn(pm);b.when(Bukkit::getWorlds).thenReturn(List.of(world));
   cf.when(()->CustomFurniture.byAlreadySpawned(entity)).thenReturn(furniture);resolver.when(()->jl.c(entity,"furniture")).thenReturn(behaviour);when(pdc.get(jl.xp,PersistentDataType.STRING)).thenReturn("glozz:cocina");when(pdc.get(jl.xq,PersistentDataType.STRING)).thenReturn("furniture");
   nms.when(()->afz.a(eq(entity),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble())).thenAnswer(a->{box[0]=new BoundingBox(a.getArgument(1),a.getArgument(3),a.getArgument(5),a.getArgument(2),a.getArgument(4),a.getArgument(6));return box[0];});nms.when(()->afz.a(eq(entity),any(BoundingBox.class))).thenAnswer(a->{box[0]=a.getArgument(1);return null;});
   check(LoadedFurnitureGeometry.repair(entity,"chunk_load"),"preexisting native ItemFrame repaired without replacement");
   check(box[0].equals(new BoundingBox(594,23.03125,2500,595,24.03125,2501)),"runtime height-one bbox exactly reproduced by genuine IA operation");
   check(entity.getUniqueId().equals(uuid)&&entity.getItem()==item&&entity.getLocation().equals(p)&&entity.getFacing()==BlockFace.UP&&entity.getRotation()==Rotation.NONE,"UUID item facing rotation location preserved");
   verify(furniture,never()).replaceFurniture(anyString());verify(entity,times(2)).getBoundingBox();b.verify(Bukkit::getLogger,never());check(logs.isEmpty(),"production debug=false performs only two functional bbox reads and no logger access; stage and item untouched");
   nms.clearInvocations();check(!LoadedFurnitureGeometry.repair(entity,"chunk_load"),"already correct furniture is no-op");nms.verifyNoInteractions();
   behaviour.xx=2;box[0]=nativeBox;check(LoadedFurnitureGeometry.repair(entity,"chunk_load")&&box[0].getMaxY()==25.03125,"multi-cell height-two repaired");
   int comparisons=0;float[][] shapes={{1,1,1,0,0,0},{2,3,4,.125f,.25f,-.375f},{1.5f,2.25f,1,.2f,-.1f,.3f}};
   for(float[] s:shapes){behaviour.xv=s[0];behaviour.xw=s[1];behaviour.xx=s[2];behaviour.xz=s[3];behaviour.xA=s[4];behaviour.xy=s[5];
    for(float pitch:new float[]{-90,90,0})for(float yaw:new float[]{0,90,180,270})for(Rotation rotation:Rotation.values()){
     p.setPitch(pitch);p.setYaw(yaw);when(entity.getRotation()).thenReturn(rotation);
     BoundingBox expected=LoadedFurnitureGeometry.expected(entity,ItemsAdderHitboxCompat.loadedGeometryOperation());js.x(entity);
     if(!LoadedFurnitureGeometry.sameBox(expected,box[0]))throw new AssertionError("IA geometry differs pitch="+pitch+" yaw="+yaw+" rotation="+rotation+" expected="+expected+" actual="+box[0]);comparisons++;
    }
   }
   check(comparisons==288,"288 pure expected-box comparisons match real IA z across floor ceiling walls rotations and offsets");
   behaviour.xv=1;behaviour.xw=1;behaviour.xx=2;behaviour.xz=0;behaviour.xA=0;behaviour.xy=0;p.setPitch(-90);p.setYaw(0);when(entity.getRotation()).thenReturn(Rotation.NONE);box[0]=nativeBox;
   cfg.set("items.cocina.behaviours.furniture.solid",false);nms.clearInvocations();check(!LoadedFurnitureGeometry.repair(entity,"chunk_load"),"non-solid skipped");nms.verifyNoInteractions();cfg.set("items.cocina.behaviours.furniture.solid",true);
   cf.when(()->CustomFurniture.byAlreadySpawned(entity)).thenReturn(null);check(!LoadedFurnitureGeometry.repair(entity,"chunk_load"),"non-IA frame skipped");cf.when(()->CustomFurniture.byAlreadySpawned(entity)).thenReturn(furniture);
   when(pdc.get(jl.xq,PersistentDataType.STRING)).thenReturn(null);check(!LoadedFurnitureGeometry.repair(entity,"chunk_load"),"missing IA furniture marker skipped");when(pdc.get(jl.xq,PersistentDataType.STRING)).thenReturn("furniture");
   when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(false);check(!LoadedFurnitureGeometry.repair(entity,"chunk_load"),"unloaded chunk skipped without force-load");when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
   when(pdc.get(jl.xp,PersistentDataType.STRING)).thenReturn("other:id");check(!LoadedFurnitureGeometry.repair(entity,"chunk_load"),"item/PDC mismatch fails safely without changing ID");when(pdc.get(jl.xp,PersistentDataType.STRING)).thenReturn("glozz:cocina");logs.clear();
   ItemsAdderImpl comp=new ObjenesisStd().newInstance(ItemsAdderImpl.class);FurnitureTransitionGate gate=new FurnitureTransitionGate();StageManager sm=mock(StageManager.class);when(sm.getActiveSequences()).thenReturn(new HashSet<>());field(comp,"furnitureTransitions",gate);field(comp,"stageManager",sm);field(comp,"removedLocations",new HashSet<Location>());
   var lease=gate.acquire(uuid,mock(Event.class),p,0);nms.clearInvocations();check(!comp.rehydrateLoadedFurniture(entity,"chunk_load"),"active transition lock skips loaded repair");nms.verifyNoInteractions();gate.release(lease);
   check(comp.rehydrateLoadedFurniture(entity,"chunk_load"),"loaded repair allowed after transition releases");box[0]=nativeBox;
   Chunk chunk=mock(Chunk.class);when(chunk.getWorld()).thenReturn(world);when(chunk.getX()).thenReturn(37);when(chunk.getZ()).thenReturn(156);when(chunk.isLoaded()).thenReturn(true);when(chunk.isEntitiesLoaded()).thenReturn(true);when(chunk.getEntities()).thenReturn(new Entity[]{entity});when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk});
   List<String> sources=new ArrayList<>();LoadedFurnitureGeometryListener listener=new LoadedFurnitureGeometryListener(owner,(e,source)->{sources.add(source);LoadedFurnitureGeometry.repair(e,source);return Unit.INSTANCE;});
   api.when(ItemsAdder::areItemsLoaded).thenReturn(false);listener.start();check(ticks.isEmpty(),"registry not ready schedules no repair");listener.entitiesLoaded(new EntitiesLoadEvent(chunk,List.of(entity)));check(ticks.isEmpty(),"pre-data load does not retain work; startup catch-up will cover it");
   listener.dataLoaded(new ItemsAdderLoadDataEvent(false));check(ticks.size()==1,"data event starts one finite catch-up batch");while(!ticks.isEmpty())ticks.remove(0).invoke();check(sources.equals(List.of("startup_loaded_chunk")),"already-loaded startup entity repaired after data lifecycle");
   verify(world,times(1)).getLoadedChunks();listener.dataLoaded(new ItemsAdderLoadDataEvent(true));verify(world,times(1)).getLoadedChunks();check(ticks.isEmpty(),"data reload does not repeat startup scan or poll");
   sources.clear();box[0]=nativeBox;listener.entitiesLoaded(new EntitiesLoadEvent(chunk,List.of(entity)));listener.entitiesLoaded(new EntitiesLoadEvent(chunk,List.of(entity)));while(!ticks.isEmpty())ticks.remove(0).invoke();check(sources.equals(List.of("chunk_load")),"duplicate event UUID repaired once while pending");
   listener.entitiesLoaded(new EntitiesLoadEvent(chunk,List.of(entity)));listener.entitiesUnloaded(new EntitiesUnloadEvent(chunk,List.of(entity)));while(!ticks.isEmpty())ticks.remove(0).invoke();check(sources.size()==1,"unload removes pending UUID before repair");
   for(int i=0;i<1000;i++){listener.entitiesLoaded(new EntitiesLoadEvent(chunk,List.of(entity)));while(!ticks.isEmpty())ticks.remove(0).invoke();}
   var count=LoadedFurnitureGeometryListener.class.getDeclaredMethods();int pending=-1;for(var method:count)if(method.getName().startsWith("pendingCount"))pending=(int)method.invoke(listener);check(pending==0&&ticks.isEmpty(),"1000 load cycles drain all UUID references and tasks");
   listener.entitiesLoaded(new EntitiesLoadEvent(chunk,List.of(entity)));listener.pluginDisabled(new PluginDisableEvent(owner));while(!ticks.isEmpty())ticks.remove(0).invoke();check(ticks.isEmpty(),"disable clears finite queue and stops scheduling");
   List<ItemFrame> bulk=new ArrayList<>();for(int i=0;i<70;i++){ItemFrame frame=mock(ItemFrame.class);when(frame.getUniqueId()).thenReturn(UUID.randomUUID());when(frame.getWorld()).thenReturn(world);when(frame.getLocation()).thenReturn(p);when(frame.isValid()).thenReturn(true);bulk.add(frame);}when(chunk.getEntities()).thenReturn(bulk.toArray(Entity[]::new));
   int[] operated={0};LoadedFurnitureGeometryListener limited=new LoadedFurnitureGeometryListener(owner,(e,source)->{operated[0]++;return Unit.INSTANCE;});api.when(ItemsAdder::areItemsLoaded).thenReturn(true);limited.start();ticks.remove(0).invoke();check(operated[0]==32&&ticks.size()==1,"startup repairs at most 32 entity candidates per tick");while(!ticks.isEmpty())ticks.remove(0).invoke();check(operated[0]==70,"finite batches eventually drain every candidate");
   clearInvocations(chunk);when(chunk.isLoaded()).thenReturn(false);LoadedFurnitureGeometryListener unloaded=new LoadedFurnitureGeometryListener(owner,(e,source)->{throw new AssertionError("unloaded repaired");});unloaded.start();while(!ticks.isEmpty())ticks.remove(0).invoke();verify(chunk,never()).getEntities();check(true,"startup chunk unloaded before batch never accesses entities");
   clearInvocations(chunk);when(chunk.isLoaded()).thenReturn(true);when(chunk.isEntitiesLoaded()).thenReturn(false);LoadedFurnitureGeometryListener noNbt=new LoadedFurnitureGeometryListener(owner,(e,source)->{throw new AssertionError("NBT fetched");});noNbt.start();while(!ticks.isEmpty())ticks.remove(0).invoke();verify(chunk,never()).getEntities();check(true,"startup absent entity data never fetched from disk");
   List<Chunk> chunks=new ArrayList<>();int[] inspected={0};for(int i=0;i<20;i++){Chunk c=mock(Chunk.class);when(c.getWorld()).thenReturn(world);when(c.isLoaded()).thenReturn(true);when(c.isEntitiesLoaded()).thenReturn(true);when(c.getEntities()).thenAnswer(a->{inspected[0]++;return new Entity[0];});chunks.add(c);}when(world.getLoadedChunks()).thenReturn(chunks.toArray(Chunk[]::new));LoadedFurnitureGeometryListener chunkLimit=new LoadedFurnitureGeometryListener(owner,(e,source)->Unit.INSTANCE);chunkLimit.start();ticks.remove(0).invoke();check(inspected[0]==4&&ticks.size()==1,"startup inspects at most four chunks per tick");while(!ticks.isEmpty())ticks.remove(0).invoke();check(inspected[0]==20&&ticks.isEmpty(),"chunk batches complete without polling");
   verify(world,never()).getChunkAt(anyInt(),anyInt());verify(world,never()).loadChunk(anyInt(),anyInt());verify(world,never()).getNearbyEntities(any(Location.class),anyDouble(),anyDouble(),anyDouble());check(true,"no chunk loading global proximity search or barrier writes");
   for(var call:mockingDetails(entity).getInvocations())if(call.getMethod().getName().startsWith("set")||call.getMethod().getName().equals("remove")||call.getMethod().getName().startsWith("teleport"))throw new AssertionError("entity mutation "+call);
   check(true,"load path invokes no item/facing/rotation setters removal or teleport");
  }finally{unsafe.putObject(sb,so,old);DebugSettings.configure(null);}
  System.out.println(checks+" V4.4 loaded geometry checks passed (genuine IA geometry; mocked Bukkit lifecycle)");
 }
}
