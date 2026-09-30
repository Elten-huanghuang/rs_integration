package com.huanghuang.rsintegration.mods.biomancy;

import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

final class BiomancyBioLabInputLayout {
    static final int SIDE_SLOTS = 4;
    static final int CENTER_SLOT = 4;
    static final int INPUT_SLOTS = 5;

    private BiomancyBioLabInputLayout() {}

    @Nullable
    static List<SlotInput> plan(List<IngredientSpec> specs, Ingredient reactant, List<ItemStack> materials) {
        boolean hasReactant = !reactant.isEmpty();
        int sideCount = specs.size() - (hasReactant ? 1 : 0);
        if (sideCount < 0 || sideCount > SIDE_SLOTS || materials.size() != specs.size()) return null;
        if (hasReactant && specs.get(sideCount).count() != 1) return null;
        List<SlotInput> inputs = new ArrayList<>();
        for (int index = 0; index < specs.size(); index++) {
            IngredientSpec spec = specs.get(index);
            ItemStack material = materials.get(index);
            if (spec.isEmpty() || material == null || material.isEmpty()
                    || material.getCount() != spec.count()
                    || !IngredientMatcher.test(spec.ingredient(), material)) return null;
            boolean center = hasReactant && index == sideCount;
            if (center && !IngredientMatcher.test(reactant, material)) return null;
            inputs.add(new SlotInput(center ? CENTER_SLOT : index, material.copy()));
        }
        return List.copyOf(inputs);
    }

    record SlotInput(int slot, ItemStack stack) {}
}
