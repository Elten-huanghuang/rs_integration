package com.huanghuang.rsintegration.crafting.planning;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Value-only recipe projection safe to traverse outside the server thread. */
public record ImmutableRecipeGraph(Map<MaterialRef, List<RecipeNode>> recipesByOutput,
                                   Map<ResourceLocation, RecipeNode> recipesById) {
    public ImmutableRecipeGraph(Map<MaterialRef, List<RecipeNode>> recipesByOutput) {
        this(recipesByOutput, indexById(recipesByOutput));
    }

    public ImmutableRecipeGraph {
        recipesByOutput = recipesByOutput.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        recipesById = Map.copyOf(recipesById);
    }

    private static Map<ResourceLocation, RecipeNode> indexById(
            Map<MaterialRef, List<RecipeNode>> recipesByOutput) {
        Map<ResourceLocation, RecipeNode> indexed = new LinkedHashMap<>();
        for (List<RecipeNode> candidates : recipesByOutput.values()) {
            for (RecipeNode candidate : candidates) indexed.putIfAbsent(candidate.recipeId(), candidate);
        }
        return indexed;
    }

    public record MaterialRef(ResourceLocation itemId, String nbt) {
        public MaterialRef {
            if (itemId == null) throw new IllegalArgumentException("itemId");
            nbt = nbt == null ? "" : nbt;
        }
    }

    public enum NbtMatchMode {
        ANY,
        EXACT,
        PARTIAL
    }

    public record IngredientRef(List<MaterialRef> alternatives, int count,
                                NbtMatchMode nbtMatchMode) {
        public IngredientRef(List<MaterialRef> alternatives, int count) {
            this(alternatives, count, NbtMatchMode.EXACT);
        }

        public IngredientRef {
            alternatives = List.copyOf(alternatives);
            nbtMatchMode = nbtMatchMode == null ? NbtMatchMode.EXACT : nbtMatchMode;
            if (alternatives.isEmpty() || count <= 0) throw new IllegalArgumentException("empty ingredient");
        }

        public IngredientRef withCount(int newCount) {
            return new IngredientRef(alternatives, newCount, nbtMatchMode);
        }
    }

    public record RecipeNode(ResourceLocation recipeId, MaterialRef output, int outputCount,
                             List<IngredientRef> inputs, String modTypeId,
                             ResourceLocation recipeTypeId) {
        public RecipeNode(ResourceLocation recipeId, MaterialRef output, int outputCount,
                          List<IngredientRef> inputs) {
            this(recipeId, output, outputCount, inputs, "generic",
                    new ResourceLocation("minecraft", "crafting"));
        }

        public RecipeNode {
            inputs = List.copyOf(inputs);
            modTypeId = modTypeId == null || modTypeId.isBlank() ? "generic" : modTypeId;
            recipeTypeId = recipeTypeId == null
                    ? new ResourceLocation("minecraft", "crafting") : recipeTypeId;
            if (recipeId == null || output == null || outputCount <= 0) {
                throw new IllegalArgumentException("invalid recipe node");
            }
        }
    }
}
