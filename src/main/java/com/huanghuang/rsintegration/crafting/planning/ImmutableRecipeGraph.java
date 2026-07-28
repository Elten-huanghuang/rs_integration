package com.huanghuang.rsintegration.crafting.planning;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;

/** Value-only recipe projection safe to traverse outside the server thread. */
public record ImmutableRecipeGraph(Map<MaterialRef, List<RecipeNode>> recipesByOutput) {
    public ImmutableRecipeGraph {
        recipesByOutput = recipesByOutput.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    public record MaterialRef(ResourceLocation itemId, String nbt) {
        public MaterialRef {
            if (itemId == null) throw new IllegalArgumentException("itemId");
            nbt = nbt == null ? "" : nbt;
        }
    }

    public record IngredientRef(List<MaterialRef> alternatives, int count) {
        public IngredientRef {
            alternatives = List.copyOf(alternatives);
            if (alternatives.isEmpty() || count <= 0) throw new IllegalArgumentException("empty ingredient");
        }
    }

    public record RecipeNode(ResourceLocation recipeId, MaterialRef output, int outputCount,
                             List<IngredientRef> inputs) {
        public RecipeNode {
            inputs = List.copyOf(inputs);
            if (recipeId == null || output == null || outputCount <= 0) {
                throw new IllegalArgumentException("invalid recipe node");
            }
        }
    }
}
