package com.huanghuang.rsintegration.mods.botania;
import com.huanghuang.rsintegration.crafting.IngredientSpec;import com.refinedmods.refinedstorage.api.network.INetwork;import com.refinedmods.refinedstorage.api.util.Action;import net.minecraft.world.entity.item.ItemEntity;import net.minecraft.world.item.ItemStack;import net.minecraft.world.phys.AABB;import net.minecraft.server.level.ServerLevel;import java.util.*;
final class BotaniaDelegateSupport {private BotaniaDelegateSupport(){}
 static List<ItemStack> extractAtomically(INetwork n,List<IngredientSpec> specs){List<ItemStack> out=new ArrayList<>();for(IngredientSpec s:specs){ItemStack[] a=s.ingredient().getItems();if(a.length==0){refund(n,out);return List.of();}ItemStack x=n.extractItem(a[0].copyWithCount(s.count()),s.count(),Action.PERFORM);if(x.getCount()!=s.count()){if(!x.isEmpty())out.add(x);refund(n,out);return List.of();}out.add(x);}return out;}
 static void refund(INetwork n,List<ItemStack> stacks){for(ItemStack s:stacks)if(!s.isEmpty())n.insertItem(s,s.getCount(),Action.PERFORM);}
 static Set<UUID> snapshot(ServerLevel l,AABB b){Set<UUID>s=new HashSet<>();for(ItemEntity e:l.getEntitiesOfClass(ItemEntity.class,b))s.add(e.getUUID());return s;}
 static boolean isNew(ItemEntity e,Set<UUID> before){return e.isAlive()&&!before.contains(e.getUUID());}
 static void protectOperationInput(ItemEntity entity){entity.setPickUpDelay(Integer.MAX_VALUE);}
}