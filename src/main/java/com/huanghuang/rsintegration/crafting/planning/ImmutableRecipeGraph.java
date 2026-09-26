package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import net.minecraft.resources.ResourceLocation;
import java.util.stream.Collectors;

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
        recipesByOutput = recipesByOutput.entrySet().stream().collect(Collectors.toUnmodifiableMap(
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

    /** Runtime NBT is unknown, not a promise that the produced stack is tagless. */
    public record MaterialRef(ResourceLocation itemId, String nbt, boolean runtimeNbt) {
        public MaterialRef(ResourceLocation itemId, String nbt) {
            this(itemId, nbt, false);
        }

        public MaterialRef {
            if (itemId == null) throw new IllegalArgumentException("itemId");
            nbt = nbt == null ? "" : nbt;
            if (runtimeNbt && !nbt.isEmpty()) throw new IllegalArgumentException("unknown output NBT");
        }
    }

    public enum NbtMatchMode {
        ANY,
        EXACT,
        PARTIAL
    }

    public record IngredientRef(List<MaterialRef> alternatives, int count,
                                NbtMatchMode nbtMatchMode, DemandRole role) {
        public IngredientRef(List<MaterialRef> alternatives, int count) {
            this(alternatives, count, NbtMatchMode.EXACT, DemandRole.CONSUMED);
        }

        public IngredientRef(List<MaterialRef> alternatives, int count,
                             NbtMatchMode nbtMatchMode) {
            this(alternatives, count, nbtMatchMode, DemandRole.CONSUMED);
        }

        public IngredientRef {
            alternatives = List.copyOf(alternatives);
            nbtMatchMode = nbtMatchMode == null ? NbtMatchMode.EXACT : nbtMatchMode;
            role = role == null ? DemandRole.CONSUMED : role;
            if (alternatives.isEmpty() || count <= 0) throw new IllegalArgumentException("empty ingredient");
        }

        public IngredientRef withCount(int newCount) {
            return new IngredientRef(alternatives, newCount, nbtMatchMode, role);
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
