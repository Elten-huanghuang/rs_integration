package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.util.ModIds;
import com.rekindled.embers.blockentity.StampBaseBlockEntity;
import com.rekindled.embers.blockentity.StamperBlockEntity;
import com.rekindled.embers.recipe.IStampingRecipe;
import com.rekindled.embers.recipe.StampingRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;

public final class EmbersStampingRecipeHandler implements ModRecipeHandler {
    @Override public ModType modType() { return ModType.byId(ModIds.ID_EMBERS_STAMPER); }
    @Override public boolean canHandle(Recipe<?> recipe) { return recipe instanceof IStampingRecipe; }
    @Override public boolean preferHandlerIngredients() { return true; }
    @Override public boolean isCompatibleBinding(Recipe<?> recipe, String blockKey) {
        return blockKey != null && blockKey.contains(ModIds.ID_EMBERS_STAMPER);
    }
    @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        ItemStack output = ((IStampingRecipe) recipe).getResultItem();
        return output == null ? ItemStack.EMPTY : output.copy();
    }
    @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        IStampingRecipe stamping = (IStampingRecipe) recipe;
        List<IngredientSpec> specs = new ArrayList<>();
        Ingredient input = stamping.getDisplayInput();
        if (input != null && !input.isEmpty()) specs.add(new IngredientSpec(input, 1));
        if (!stamping.getDisplayInputFluid().getFluids().isEmpty()) {
            IngredientSpec fluid = EmbersFluidIngredients.spec(stamping.getDisplayInputFluid());
            if (fluid == null) return null;
            specs.add(fluid);
        }
        Ingredient stamp = stamping.getDisplayStamp();
        if (stamp != null && !stamp.isEmpty()) {
            specs.add(new IngredientSpec(stamp, 1, DemandRole.CATALYST));
        }
        return specs.isEmpty() ? null : List.copyOf(specs);
    }

    @Override public List<IngredientSpec> getPlanningIngredients(
            Recipe<?> recipe, ServerPlayer player, List<IngredientSpec> ingredients) {
        if (ingredients == null || ingredients.isEmpty()
                || !(recipe instanceof IStampingRecipe stamping)
                || !hasMatchingInstalledStamp(player, stamping)) {
            return ingredients == null ? List.of() : ingredients;
        }
        int last = ingredients.size() - 1;
        return ingredients.get(last).role() == DemandRole.CATALYST
                ? List.copyOf(ingredients.subList(0, last)) : ingredients;
    }

    /** 已安装的正确印模视为机器自带的可复用材料，规划时无需再合成一枚。 */
    public static boolean hasMatchingInstalledStamp(ServerPlayer player, IStampingRecipe recipe) {
        if (player == null || recipe == null) return false;
        Ingredient stamp = recipe.getDisplayStamp();
        if (stamp == null || stamp.isEmpty()) return false;
        for (AltarBindingRegistry.BoundMachine bound : AltarBindingRegistry.getBoundMachinesForRecipe(
                player, ModType.byId(ModIds.ID_EMBERS_STAMPER), recipe.getId())) {
            ServerLevel level = player.server.getLevel(ResourceKey.create(Registries.DIMENSION, bound.dim()));
            BlockPos pos = bound.pos();
            if (level == null || !level.hasChunkAt(pos) || !level.hasChunkAt(pos.below(2))) continue;
            if (level.getBlockEntity(pos) instanceof StamperBlockEntity stamper
                    && level.getBlockEntity(pos.below(2)) instanceof StampBaseBlockEntity
                    && stamp.test(stamper.stamp.getStackInSlot(0))) return true;
        }
        return false;
    }
    @Override public boolean supportsBackgroundPlanning(Recipe<?> recipe) {
        return recipe instanceof StampingRecipe nativeRecipe
                && recipe.getClass() == StampingRecipe.class
                && nativeRecipe.output.left().isPresent()
                && ModRecipeHandler.super.supportsBackgroundPlanning(recipe);
    }
    @Override public boolean supportsIntermediateProjection(Recipe<?> recipe) {
        return supportsBackgroundPlanning(recipe);
    }
}
