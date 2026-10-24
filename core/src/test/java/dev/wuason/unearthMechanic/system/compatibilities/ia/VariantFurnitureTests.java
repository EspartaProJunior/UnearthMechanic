package dev.wuason.unearthMechanic.system.compatibilities.ia;
import dev.lone.itemsadder.api.*;
import dev.wuason.unearthMechanic.system.StageManager;
import dev.wuason.unearthMechanic.utils.DebugSettings;
import itemsadder.m.*;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.*;
import org.bukkit.plugin.*;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import static org.mockito.Mockito.*;

public class VariantFurnitureTests {
 static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);System.out.println("PASS "+label);}
 static YamlConfiguration load(String file)throws Exception{YamlConfiguration y=new YamlConfiguration();try(var r=new InputStreamReader(VariantFurnitureTests.class.getResourceAsStream("/variant-furniture/"+file),StandardCharsets.UTF_8)){y.load(r);}return y;}
 static void field(Object o,String name,Object value)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
 @Test void resolvedOverridesStayAuthoritative()throws Exception{
  YamlConfiguration foods=load("itemsadder-recetas.yml"),raw=new YamlConfiguration();ConfigurationSection item=raw.createSection("items.override");item.set("variant_of","glozz:cocina_sarten_comida");item.set("behaviours.furniture.solid",false);item.set("behaviours.furniture.hitbox.height",2);
  ConfigurationSection resolved=sv.a(null,new bbg(item),Map.of("glozz:cocina_sarten_comida",new bbg(foods.getConfigurationSection("items.cocina_sarten_comida"))),"glozz","override").azU;
  CustomStack stack=mock(CustomStack.class);when(stack.getConfig()).thenReturn(raw);when(stack.getId()).thenReturn("override");when(stack.getConfigSectionCopy(true)).thenReturn(resolved);
  check(!ItemsAdderFurnitureSettings.get(stack).getBoolean("solid"),"IA-resolved explicit non-solid override is preserved");
  check(ItemsAdderFurnitureSettings.get(stack).getDouble("hitbox.height")==2,"IA-resolved multi-cell height override is preserved");
  when(stack.getConfigSectionCopy(true)).thenReturn(null);check(ItemsAdderFurnitureSettings.get(stack)==null,"unresolved inherited config is skipped without raw fallback classification");
  item.set("variant_of",null);clearInvocations(stack);check(ItemsAdderFurnitureSettings.get(stack)==item.getConfigurationSection("behaviours.furniture"),"plain furniture keeps exact raw section fast path");verify(stack,never()).getConfigSectionCopy(anyBoolean());
 }
 @Test void realRecipesRestoreInheritedFurniture()throws Exception{
  DebugSettings.configure(null);YamlConfiguration stove=load("cocina.yml"),foods=load("itemsadder-recetas.yml"),recipes=load("unearth-recetas.yml");
  Map<String,ConfigurationSection> effective=new HashMap<>();Map<String,bbg> templates=Map.of("glozz:cocina_sarten_comida",new bbg(foods.getConfigurationSection("items.cocina_sarten_comida")));
  for(String name:foods.getConfigurationSection("items").getKeys(false))if(!name.equals("cocina_sarten_comida"))effective.put("glozz:"+name,sv.a(null,new bbg(foods.getConfigurationSection("items."+name)),templates,"glozz",name).azU);
  String oil="glozz:cocina_prendida_sarten_aceite";effective.put(oil,stove.getConfigurationSection("items.cocina_prendida_sarten_aceite"));
  check(effective.get("glozz:cocina_sarten_huevo_crudo_1").getBoolean("behaviours.furniture.solid")&&!foods.contains("items.cocina_sarten_huevo_crudo_1.behaviours.furniture"),"real IA sv.a resolves solid furniture absent from raw egg configuration");
  World world=mock(World.class);when(world.getName()).thenReturn("world");when(world.getUID()).thenReturn(UUID.randomUUID());when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
  ItemFrame entity=mock(ItemFrame.class);UUID uuid=UUID.randomUUID();Location location=new Location(world,594.5,23.03125,2500.5,90,-90),base=location.clone();base.setX(594);base.setY(23);base.setZ(2500);base.setYaw(0);base.setPitch(0);
  org.bukkit.block.Block block=mock(org.bukkit.block.Block.class);when(block.getLocation()).thenReturn(base);when(world.getBlockAt(any(Location.class))).thenReturn(block);
  when(world.getEntity(uuid)).thenReturn(entity);when(entity.getUniqueId()).thenReturn(uuid);when(entity.getWorld()).thenReturn(world);when(entity.getLocation()).thenReturn(location);when(entity.getType()).thenReturn(EntityType.ITEM_FRAME);when(entity.isValid()).thenReturn(true);when(entity.getFacing()).thenReturn(BlockFace.UP);when(entity.getRotation()).thenReturn(Rotation.CLOCKWISE);
  ItemStack item=mock(ItemStack.class);when(item.clone()).thenReturn(item);when(entity.getItem()).thenReturn(item);
  BoundingBox nativeBox=new BoundingBox(594.125,23,2500.125,594.875,23.0625,2500.875);BoundingBox[] box={nativeBox};when(entity.getBoundingBox()).thenAnswer(a->box[0]);
  String[] current={oil},target={oil};CustomFurniture furniture=mock(CustomFurniture.class);when(furniture.getEntity()).thenReturn(entity);when(furniture.getNamespacedID()).thenAnswer(a->current[0]);when(furniture.getId()).thenAnswer(a->current[0].substring(6));when(furniture.getConfig()).thenAnswer(a->current[0].equals(oil)?stove:foods);when(furniture.getConfigSectionCopy(true)).thenAnswer(a->effective.get(current[0]));
  CustomStack stack=mock(CustomStack.class);when(stack.getId()).thenAnswer(a->target[0].substring(6));when(stack.getConfig()).thenAnswer(a->target[0].equals(oil)?stove:foods);when(stack.getConfigSectionCopy(true)).thenAnswer(a->effective.get(target[0]));
  js behaviour=new ObjenesisStd().newInstance(js.class);behaviour.xv=1;behaviour.xw=1;behaviour.xx=1;
  PersistentDataContainer pdc=mock(PersistentDataContainer.class);when(entity.getPersistentDataContainer()).thenReturn(pdc);
  Plugin plugin=mock(Plugin.class);when(plugin.isEnabled()).thenReturn(true);when(plugin.getDescription()).thenReturn(new PluginDescriptionFile("ItemsAdder","4.0.18","dev.lone.itemsadder.Main"));PluginManager pm=mock(PluginManager.class);when(pm.getPlugin("ItemsAdder")).thenReturn(plugin);
  StageManager sm=mock(StageManager.class);Set<Location> sequences=new HashSet<>();when(sm.getActiveSequences()).thenReturn(sequences);ItemsAdderImpl comp=new ObjenesisStd().newInstance(ItemsAdderImpl.class);FurnitureTransitionGate gate=new FurnitureTransitionGate();field(comp,"furnitureTransitions",gate);field(comp,"stageManager",sm);field(comp,"removedLocations",new HashSet<Location>());
  var replace=ItemsAdderImpl.class.getDeclaredMethod("tryReplaceFurniture",Entity.class,String.class,Location.class,boolean.class);replace.setAccessible(true);
  doAnswer(a->{current[0]=a.getArgument(0);box[0]=nativeBox;return null;}).when(furniture).replaceFurniture(anyString());
  try(var b=mockStatic(Bukkit.class);var cf=mockStatic(CustomFurniture.class);var cs=mockStatic(CustomStack.class);var resolver=mockStatic(jl.class);var adapter=mockStatic(afz.class)){
   b.when(Bukkit::getPluginManager).thenReturn(pm);b.when(Bukkit::getLogger).thenReturn(Logger.getAnonymousLogger());cf.when(()->CustomFurniture.byAlreadySpawned(entity)).thenReturn(furniture);cs.when(()->CustomStack.getInstance(anyString())).thenAnswer(a->{target[0]=a.getArgument(0);return stack;});resolver.when(()->jl.c(entity,"furniture")).thenReturn(behaviour);
   when(pdc.get(jl.xp,PersistentDataType.STRING)).thenAnswer(a->current[0]);when(pdc.get(jl.xq,PersistentDataType.STRING)).thenReturn("furniture");
   adapter.when(()->afz.a(eq(entity),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble())).thenAnswer(a->{box[0]=new BoundingBox(a.getArgument(1),a.getArgument(3),a.getArgument(5),a.getArgument(2),a.getArgument(4),a.getArgument(6));return box[0];});adapter.when(()->afz.a(eq(entity),any(BoundingBox.class))).thenAnswer(a->{box[0]=a.getArgument(1);return null;});
   BoundingBox expected=new BoundingBox(594,23.03125,2500,595,24.03125,2501);int chains=0;
   for(String name:recipes.getConfigurationSection("unearth.furniture").getKeys(false)){
    if(!name.endsWith("crudo_1")||!name.matches(".*(huevo|papas|filete|pescado|muslo|milanesa).*"))continue;
    ConfigurationSection recipe=recipes.getConfigurationSection("unearth.furniture."+name),stage=recipe.getConfigurationSection("transformation.stages.1");if(stage==null||!stage.contains("sequence.100.furniture_id"))continue;
    current[0]=oil;box[0]=nativeBox;String raw=stage.getString("furniture_id").substring(3),cooked=stage.getString("sequence.100.furniture_id").substring(3);
    check((Boolean)replace.invoke(comp,entity,raw,base,true)&&box[0].equals(expected),name+" interaction restores inherited raw stage before sequence");
    sequences.add(base);check(uuid.equals(comp.placeNewFurnitureThenRemoveOld(base,raw,cooked,uuid))&&box[0].equals(expected),name+" actual sequence hook restores inherited cooked stage");sequences.clear();
    BoundingBox sequenceBox=box[0].clone();current[0]=cooked;box[0]=nativeBox;js.x(entity);check(sequenceBox.equals(box[0]),name+" sequence geometry equals directly placed target geometry operation");
    if(name.equals("cocina_sarten_huevo_crudo_1")){String salted=recipes.getString("unearth.furniture.cocina_sarten_huevo_frito_1.transformation.stages.1.furniture_id").substring(3);check((Boolean)replace.invoke(comp,entity,salted,base,true)&&box[0].equals(expected),"egg salt interaction restores inherited frito stage");}
    box[0]=nativeBox;check(comp.rehydrateLoadedFurniture(entity,"chunk_load")&&box[0].equals(expected),name+" V4.4 rehydration also resolves same inherited solid classification");
    adapter.clearInvocations();check(!comp.rehydrateLoadedFurniture(entity,"chunk_load"),name+" correct inherited geometry remains no-op");adapter.verifyNoInteractions();
    var lease=gate.acquire(uuid,mock(Event.class),base,0);box[0]=nativeBox;check(!comp.rehydrateLoadedFurniture(entity,"chunk_load"),name+" active transition still skips inherited rehydration");gate.release(lease);chains++;
   }
   check(chains==7,"all seven real sequence recipes exercised including both milanesas");
   for(var call:mockingDetails(entity).getInvocations())if(call.getMethod().getName().startsWith("set")||call.getMethod().getName().equals("remove")||call.getMethod().getName().startsWith("teleport"))throw new AssertionError(call.toString());
   check(entity.getUniqueId().equals(uuid)&&entity.getLocation().equals(location)&&entity.getFacing()==BlockFace.UP&&entity.getRotation()==Rotation.CLOCKWISE&&entity.getItem()==item,"all real recipe chains preserve UUID location facing rotation item reference without setters or removal");
  }finally{DebugSettings.configure(null);}
 }
}
