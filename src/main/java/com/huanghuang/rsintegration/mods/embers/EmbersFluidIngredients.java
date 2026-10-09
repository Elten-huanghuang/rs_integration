package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.rekindled.embers.recipe.FluidIngredient;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.CompoundIngredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

final class EmbersFluidIngredients {
    private EmbersFluidIngredients() {}

    /** 所有备选液体必须消耗同一数量，才能安全进入物品数量账本。 */
    @Nullable
    static IngredientSpec spec(FluidIngredient source) {
        List<FluidStack> fluids = source.getFluids();
        if (fluids.isEmpty()) return null;
        int amount = fluids.get(0).getAmount();
        if (amount <= 0 || fluids.stream().anyMatch(fluid -> fluid.isEmpty()
                || fluid.getAmount() != amount)) return null;
        List<Ingredient> choices = new ArrayList<>();
        for (FluidStack fluid : fluids) {
            ItemStack token = InkFluidSupport.token(fluid);
            if (!token.isEmpty()) choices.add(StrictNBTIngredient.of(token.copyWithCount(1)));
        }
        if (choices.isEmpty()) return null;
        Ingredient ingredient = choices.size() == 1 ? choices.get(0)
                : CompoundIngredient.of(choices.toArray(Ingredient[]::new));
        return new IngredientSpec(ingredient, amount);
    }
}
