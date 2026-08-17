package com.huanghuang.rsintegration.mods.arsnouveau;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Preserves the ordered multiset of consumed inputs declared by a glyph recipe. */
public final class ArsGlyphMaterials {
    private ArsGlyphMaterials() {}

    public static List<IngredientSpec> build(@Nullable List<Ingredient> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient input : inputs) {
            if (input != null && !input.isEmpty()) specs.add(new IngredientSpec(input, 1));
        }
        return List.copyOf(specs);
    }
}
