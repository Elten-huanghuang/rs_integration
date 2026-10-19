package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.*;

final class BotaniaDelegateSupport {
 private BotaniaDelegateSupport(){}
 static List<ItemStack> extractAtomically(CraftStorageEndpoint endpoint, ServerPlayer player,
                                          List<IngredientSpec> specs) {
  List<ItemStack> out = new ArrayList<>();
  for (IngredientSpec spec : specs) {
   var result = endpoint.extractMatching(player, spec.ingredient(), spec.count(), false);
   ItemStack combined = ItemStack.EMPTY;
   for (ItemStack stack : result.extractedStacks()) {
    if (stack == null || stack.isEmpty()) continue;
    if (combined.isEmpty()) combined = stack.copy();
    else if (MaterialMatcher.equivalentRuntimeFragment(combined, stack)) combined.grow(stack.getCount());
   }
   if (combined.isEmpty() || combined.getCount() < spec.count()) {
    if (!combined.isEmpty()) endpoint.insert(player, combined, false);
    for (ItemStack previous : out) if (!previous.isEmpty()) endpoint.insert(player, previous, false);
    return List.of();
   }
   out.add(combined);
  }
  return out;
 }
 static void refund(CraftStorageEndpoint endpoint, ServerPlayer player, List<ItemStack> stacks){for(ItemStack s:stacks)if(!s.isEmpty())endpoint.insert(player,s,false);}
 static Set<UUID> snapshot(ServerLevel l,AABB b){Set<UUID>s=new HashSet<>();for(ItemEntity e:l.getEntitiesOfClass(ItemEntity.class,b))s.add(e.getUUID());return s;}
 static boolean isNew(ItemEntity e,Set<UUID> before){return e.isAlive()&&!before.contains(e.getUUID());}
 static void protectOperationInput(ItemEntity entity){entity.setPickUpDelay(Integer.MAX_VALUE);entity.setThrower(entity.getUUID());}
}
