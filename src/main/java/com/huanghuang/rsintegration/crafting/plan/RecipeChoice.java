package com.huanghuang.rsintegration.crafting.plan;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Objects;

/** 一个输出材料对应的类型化配方选择。 */
public sealed interface RecipeChoice permits RecipeChoice.Normal, RecipeChoice.NoRecipe {
    ResourceLocation NO_RECIPE_ID = new ResourceLocation("rs_integration", "no_recipe");

    String serialize();

    @Nullable
    static RecipeChoice parse(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) return null;
        if (NO_RECIPE_ID.equals(id)) return NoRecipe.INSTANCE;
        return new Normal(id);
    }

    record Normal(ResourceLocation recipeId) implements RecipeChoice {
        public Normal {
            Objects.requireNonNull(recipeId, "recipeId");
            if (NO_RECIPE_ID.equals(recipeId)) {
                throw new IllegalArgumentException("no_recipe must use NoRecipe");
            }
        }

        @Override
        public String serialize() {
            return recipeId.toString();
        }
    }

    enum NoRecipe implements RecipeChoice {
        INSTANCE;

        @Override
        public String serialize() {
            return NO_RECIPE_ID.toString();
        }
    }
}
