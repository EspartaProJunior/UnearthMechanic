package dev.wuason.unearthMechanic.system.compatibilities.ia;
import dev.lone.itemsadder.api.*;
import dev.wuason.unearthMechanic.utils.DebugSettings;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.logging.*;
import static org.mockito.Mockito.*;

public class HitboxPrepareDiagnosticTests {
 private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);System.out.println("PASS "+label);}
 @Test void reasonsAreObservableWithoutChangingClassification(){
  YamlConfiguration flags=new YamlConfiguration();flags.set("debug.enabled",true);flags.set("debug.itemsadder",true);DebugSettings.configure(flags);
  String id="glozz:cocina_sarten_huevo_crudo_1",path="items.cocina_sarten_huevo_crudo_1.behaviours.furniture.";
  YamlConfiguration raw=new YamlConfiguration(),resolved=new YamlConfiguration(),source=new YamlConfiguration();
  raw.set("items.cocina_sarten_huevo_crudo_1.display_name","example");resolved.set("behaviours.furniture.solid",true);resolved.set("behaviours.furniture.entity","item_frame");source.set("items.cocina_prendida_sarten_aceite.behaviours.furniture.solid",true);
  CustomStack target=mock(CustomStack.class);when(target.getId()).thenReturn("cocina_sarten_huevo_crudo_1");when(target.getNamespacedID()).thenReturn(id);when(target.getConfig()).thenReturn(raw);when(target.getConfigSectionCopy(true)).thenReturn(resolved);
  CustomFurniture furniture=mock(CustomFurniture.class);when(furniture.getId()).thenReturn("cocina_prendida_sarten_aceite");when(furniture.getNamespacedID()).thenReturn("glozz:cocina_prendida_sarten_aceite");when(furniture.getConfig()).thenReturn(source);when(furniture.getConfigSectionCopy(true)).thenReturn(resolved);
  ItemFrame frame=mock(ItemFrame.class);when(frame.getUniqueId()).thenReturn(UUID.randomUUID());when(frame.getBoundingBox()).thenReturn(new BoundingBox(594.125,23,2500.125,594.875,23.0625,2500.875));
  List<String> logs=new ArrayList<>();Logger logger=Logger.getAnonymousLogger();logger.setUseParentHandlers(false);logger.addHandler(new Handler(){public void publish(LogRecord record){logs.add(record.getMessage());}public void flush(){}public void close(){}});
  try(var b=mockStatic(Bukkit.class);var cs=mockStatic(CustomStack.class);var cf=mockStatic(CustomFurniture.class)){
   b.when(Bukkit::getLogger).thenReturn(logger);cs.when(()->CustomStack.getInstance(id)).thenReturn(target);cf.when(()->CustomFurniture.byAlreadySpawned(frame)).thenReturn(furniture);
   ItemsAdderHitboxCompat.before(frame,id,true);ItemsAdderHitboxCompat.after(frame,id,true);
   check(logs.stream().anyMatch(s->s.contains("reason=target_settings_missing")&&s.contains("raw=NULL")&&s.contains("resolved={")&&s.contains("solid=true")&&s.contains("route=interaction")),"diagnostic distinguishes missing raw settings from valid resolved template (synthetic config)");
   check(logs.stream().anyMatch(s->s.contains("HITBOX_AFTER_DIAG reason=state_missing")&&s.contains("targetId="+id)),"excluded target leaves after without prepared state");
   logs.clear();raw.set(path+"entity","item_frame");ItemsAdderHitboxCompat.before(frame,id,true);
   check(logs.stream().anyMatch(s->s.contains("reason=target_not_solid")&&s.contains("solidPresent=false")),"diagnostic distinguishes absent solid flag");
   logs.clear();raw.set(path+"solid",true);source.set("items.cocina_prendida_sarten_aceite.behaviours.furniture.solid",false);ItemsAdderHitboxCompat.before(frame,id,false);
   check(logs.stream().anyMatch(s->s.contains("reason=source_not_solid")&&s.contains("route=sequence_hook")),"diagnostic names source exclusion on sequence route");
   logs.clear();Entity other=mock(Entity.class);when(other.getUniqueId()).thenReturn(UUID.randomUUID());ItemsAdderHitboxCompat.before(other,id,true);
   check(logs.stream().anyMatch(s->s.contains("reason=not_item_frame")),"diagnostic names non-ItemFrame exclusion");
   logs.clear();flags.set("debug.enabled",false);flags.set("debug.itemsadder",false);DebugSettings.configure(flags);raw.set(path+"solid",false);clearInvocations(target,frame);cf.clearInvocations();b.clearInvocations();
   ItemsAdderHitboxCompat.before(frame,id,true);ItemsAdderHitboxCompat.after(frame,id,true);
   verify(target,never()).getConfigSectionCopy(anyBoolean());verify(frame,never()).getBoundingBox();cf.verifyNoInteractions();b.verify(Bukkit::getLogger,never());
   check(logs.isEmpty(),"skip diagnostics disabled perform no resolved copy bbox or source lookup work");
  }finally{DebugSettings.configure(null);}
 }
}
