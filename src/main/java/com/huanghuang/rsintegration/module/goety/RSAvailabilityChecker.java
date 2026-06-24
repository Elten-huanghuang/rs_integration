package com.huanghuang.rsintegration.module.goety;

import com.Polarice3.Goety.common.blocks.entities.PedestalBlockEntity;
import com.Polarice3.Goety.common.crafting.RitualRecipe;
import com.huanghuang.rsintegration.integration.AltarBinding;
import com.huanghuang.rsintegration.integration.AltarBindingRegistry;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.integration.RSIntegration;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class RSAvailabilityChecker {

    private RSAvailabilityChecker() {}

    public static boolean[] check(ServerPlayer player, ResourceLocation recipeId,
                                  @Nullable ResourceLocation altarDim, BlockPos pos) {
        Recipe<?> recipe = player.level().getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe == null) return null;

        INetwork network = resolveNetwork(player, altarDim, pos);
        if (network == null) return null;

        List<Ingredient> ingredients = new ArrayList<>();
        if (recipe instanceof RitualRecipe ritualRecipe) {
            ingredients.add(ritualRecipe.getActivationItem());
            ingredients.addAll(ritualRecipe.getIngredients());
        } else {
            List<Ingredient> extracted = CraftPacketUtils.extractIngredients(recipe);
            if (extracted == null || extracted.isEmpty()) return null;
            for (Ingredient ing : extracted) {
                if (ing.getItems().length > 0) {
                    ingredients.add(ing);
                }
            }
            if (ingredients.isEmpty()) return null;
        }

        List<ItemStack> pedestalItems = new ArrayList<>();
        if (recipe instanceof RitualRecipe) {
            pedestalItems.addAll(rsi$collectGoetyPedestalItems(player, pos));
        } else if (recipe.getClass().getName().equals("com.sammy.malum.common.recipe.SpiritInfusionRecipe")) {
            pedestalItems.addAll(rsi$collectMalumPedestalItems(player, pos));
        }

        boolean[] results = new boolean[ingredients.size()];
        for (int i = 0; i < ingredients.size(); i++) {
            results[i] = RSIntegration.hasItemInNetwork(network, ingredients.get(i));
            if (!results[i]) {
                results[i] = rsi$matchesPedestalItem(pedestalItems, ingredients.get(i));
            }
            if (!results[i]) {
                results[i] = rsi$hasInPlayerInv(player, ingredients.get(i));
            }
        }
        return results;
    }

    private static List<ItemStack> rsi$collectGoetyPedestalItems(ServerPlayer player, BlockPos altarPos) {
        List<ItemStack> items = new ArrayList<>();
        var level = player.level();
        int range = 6;
        for (BlockPos p : BlockPos.betweenClosed(
                altarPos.offset(-range, -range, -range),
                altarPos.offset(range, range, range))) {
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof PedestalBlockEntity pedestal) {
                pedestal.itemStackHandler.ifPresent(handler -> {
                    ItemStack stack = handler.getStackInSlot(0);
                    if (!stack.isEmpty()) {
                        items.add(stack.copy());
                    }
                });
            }
        }
        return items;
    }

    private static List<ItemStack> rsi$collectMalumPedestalItems(ServerPlayer player, BlockPos altarPos) {
        List<ItemStack> items = new ArrayList<>();
        try {
            Class<?> helperClass = Class.forName(
                    "com.sammy.malum.common.block.curiosities.spirit_altar.AltarCraftingHelper");
            List<?> pedestals = (List<?>) helperClass
                    .getMethod("capturePedestals", Level.class, BlockPos.class)
                    .invoke(null, player.level(), altarPos);
            for (Object ap : pedestals) {
                try {
                    Object inv = ap.getClass().getMethod("getSuppliedInventory").invoke(ap);
                    ItemStack stack = (ItemStack) inv.getClass()
                            .getMethod("getStackInSlot", int.class).invoke(inv, 0);
                    if (!stack.isEmpty()) items.add(stack.copy());
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] Malum pedestal capture failed: {}", e.getMessage());
        }
        return items;
    }

    private static boolean rsi$matchesPedestalItem(List<ItemStack> pedestalItems, Ingredient ingredient) {
        for (ItemStack stack : pedestalItems) {
            if (ingredient.test(stack)) return true;
        }
        return false;
    }

    private static boolean rsi$hasInPlayerInv(ServerPlayer player, Ingredient ingredient) {
        for (ItemStack stack : player.getInventory().items) {
            if (ingredient.test(stack) && !stack.isEmpty()) return true;
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (ingredient.test(stack) && !stack.isEmpty()) return true;
        }
        return false;
    }


    private static INetwork resolveNetwork(ServerPlayer player, @Nullable ResourceLocation altarDimId, BlockPos pos) {
        ResourceKey<Level> lookupDim = altarDimId != null
                ? ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, altarDimId)
                : player.level().dimension();
        var bindingOpt = AltarBindingRegistry.getBinding(lookupDim, pos, AltarBinding.RS_NETWORK);
        if (bindingOpt.isPresent()) {
            var binding = bindingOpt.get();
            var data = binding.data();
            ResourceLocation dimId = ResourceLocation.tryParse(data.getString("dim"));
            if (dimId != null) {
                ResourceKey<Level> dim = ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, dimId);
                BlockPos controllerPos = new BlockPos(
                        data.getInt("x"), data.getInt("y"), data.getInt("z"));
                INetwork net = RSIntegration.resolveNetwork(player.server, dim, controllerPos);
                if (net != null) return net;
            }
        }

        return RSIntegration.resolveNetworkFromPlayer(player);
    }
}
