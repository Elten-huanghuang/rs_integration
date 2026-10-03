package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.common.MachineWaterSupply;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.wizardsreborn.WRAlchemyAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.crafting.CompoundIngredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** 炼金机的物品和流体共用材料账本；蒸汽和秘蕴不计入材料。 */
public final class WRAlchemyRecipeHandler implements ModRecipeHandler {
    public static final String TYPE = "wizards_reborn_alchemy";
    public static final String RECIPE_CLASS =
            "mod.maxbogomol.wizards_reborn.common.recipe.AlchemyMachineRecipe";
    private final Function<FluidStack, ItemStack> fluidToken;

    public WRAlchemyRecipeHandler() { this(InkFluidSupport::token); }

    public WRAlchemyRecipeHandler(Function<FluidStack, ItemStack> fluidToken) {
        this.fluidToken = fluidToken;
    }

    @Override public ModType modType() { return ModType.byId(TYPE); }
    @Override public boolean preferHandlerIngredients() { return true; }
    // 通用 WR 处理器按配方动态分发，炼金处理器也进入同一优先级队列。
    @Override public boolean cacheByRecipeClass() { return false; }

    @Override public boolean canHandle(Recipe<?> recipe) {
        for (Class<?> type = recipe.getClass(); type != null; type = type.getSuperclass()) {
            if (RECIPE_CLASS.equals(type.getName())) return true;
        }
        return false;
    }

    @Override public boolean isCompatibleBinding(Recipe<?> recipe, String blockKey) {
        return blockKey != null && blockKey.contains("alchemy_machine");
    }

    @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        ItemStack item = WRAlchemyAccess.itemOutput(recipe, access);
        if (!item.isEmpty()) return item;
        return fluidToken.apply(WRAlchemyAccess.fluidOutput(recipe));
    }

    @Override public List<ItemStack> getSecondaryOutputs(Recipe<?> recipe, RegistryAccess access) {
        FluidStack fluid = WRAlchemyAccess.fluidOutput(recipe);
        return !WRAlchemyAccess.itemOutput(recipe, access).isEmpty() && !fluid.isEmpty()
                ? List.of(fluidToken.apply(fluid)) : List.of();
    }

    @Override public boolean useClickedPrimaryOutput(Recipe<?> recipe, ItemStack declared, ItemStack clicked) {
        return WRAlchemyAccess.hasPotionOutput(recipe);
    }

    @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        return getMachineInputs(recipe, MachineWaterSupply.isFree(TYPE)).stream()
                .filter(input -> !input.freeWater()).map(MachineInput::material).toList();
    }

    public record MachineInput(IngredientSpec material, boolean freeWater) {}

    /** 免费水仍占用原配方的液槽，只从材料预留中排除。 */
    public List<MachineInput> getMachineInputs(Recipe<?> recipe, boolean freeWater) {
        List<MachineInput> result = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) {
                result.add(new MachineInput(new IngredientSpec(
                        WRAlchemyAccess.potionIngredient(recipe, ingredient), 1), false));
            }
        }
        for (List<FluidStack> alternatives : WRAlchemyAccess.fluidInputs(recipe)) {
            if (alternatives.isEmpty()) throw new IllegalArgumentException("炼金流体材料没有可用候选");
            int amount = alternatives.get(0).getAmount();
            if (amount <= 0 || alternatives.stream().anyMatch(fluid -> fluid.getAmount() != amount)) {
                throw new IllegalArgumentException("炼金流体材料数量无效或候选数量不一致");
            }
            List<Ingredient> ingredients = alternatives.stream()
                    .map(fluidToken).map(stack -> (Ingredient) StrictNBTIngredient.of(stack.copyWithCount(1)))
                    .toList();
            boolean supplyWater = freeWater && alternatives.stream().allMatch(fluid ->
                    fluid.getFluid() == Fluids.WATER && (fluid.getTag() == null || fluid.getTag().isEmpty()));
            result.add(new MachineInput(new IngredientSpec(
                    CompoundIngredient.of(ingredients.toArray(Ingredient[]::new)), amount), supplyWater));
        }
        return List.copyOf(result);
    }
}
