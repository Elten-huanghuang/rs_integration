package com.huanghuang.rsintegration.mods.arsnouveau;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Material roles used by both planning and physical Imbuement execution. */
public final class ArsImbuementMaterials {
    private ArsImbuementMaterials() {}

    public static List<IngredientSpec> build(@Nullable Ingredient input,
                                             @Nullable List<Ingredient> pedestalItems) {
        List<IngredientSpec> specs = new ArrayList<>();
        if (input != null && !input.isEmpty()) {
            specs.add(new IngredientSpec(input, 1, DemandRole.CONSUMED));
        }
        if (pedestalItems != null) {
            for (Ingredient ingredient : pedestalItems) {
                if (ingredient != null && !ingredient.isEmpty()) {
                    specs.add(new IngredientSpec(ingredient, 1, DemandRole.CATALYST));
                }
            }
        }
        return List.copyOf(specs);
    }

    static int pedestalItemCount(@Nullable List<Ingredient> pedestalItems) {
        if (pedestalItems == null) return 0;
        int count = 0;
        for (Ingredient ingredient : pedestalItems) {
            if (ingredient != null && !ingredient.isEmpty()) count++;
        }
        return count;
    }

    static boolean hasPedestalCapacity(@Nullable List<Ingredient> pedestalItems,
                                       int availablePedestals) {
        return Math.max(0, availablePedestals) >= pedestalItemCount(pedestalItems);
    }
}
