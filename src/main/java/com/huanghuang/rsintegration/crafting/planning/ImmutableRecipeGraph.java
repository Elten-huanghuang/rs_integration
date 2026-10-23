package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.magic.MagicResourceCost;
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

    /** runtimeNbt 表示未知；constrainedNbt 仅保证已建模的状态下限，不能用作精确 NBT。 */
    public record MaterialRef(ResourceLocation itemId, String nbt, boolean runtimeNbt,
                              boolean constrainedNbt) {
        public MaterialRef(ResourceLocation itemId, String nbt) {
            this(itemId, nbt, false, false);
        }

        public MaterialRef(ResourceLocation itemId, String nbt, boolean runtimeNbt) {
            this(itemId, nbt, runtimeNbt, false);
        }

        public MaterialRef {
            if (itemId == null) throw new IllegalArgumentException("itemId");
            nbt = nbt == null ? "" : nbt;
            if (runtimeNbt && !nbt.isEmpty()) throw new IllegalArgumentException("unknown output NBT");
            if (constrainedNbt && (runtimeNbt || nbt.isEmpty())) {
                throw new IllegalArgumentException("missing constrained output NBT");
            }
        }
    }

    public enum NbtMatchMode {
        ANY,
        EXACT,
        PARTIAL,
        /** 刀名相同且杀敌数、魂、精炼达到下限。 */
        SLASHBLADE
    }

    public record IngredientRef(List<MaterialRef> alternatives, long count,
                                NbtMatchMode nbtMatchMode, DemandRole role) {
        public IngredientRef(List<MaterialRef> alternatives, long count) {
            this(alternatives, count, NbtMatchMode.EXACT, DemandRole.CONSUMED);
        }

        public IngredientRef(List<MaterialRef> alternatives, long count,
                             NbtMatchMode nbtMatchMode) {
            this(alternatives, count, nbtMatchMode, DemandRole.CONSUMED);
        }

        public IngredientRef {
            alternatives = List.copyOf(alternatives);
            nbtMatchMode = nbtMatchMode == null ? NbtMatchMode.EXACT : nbtMatchMode;
            role = role == null ? DemandRole.CONSUMED : role;
            if (alternatives.isEmpty() || count <= 0) throw new IllegalArgumentException("empty ingredient");
        }

        public IngredientRef withCount(long newCount) {
            return new IngredientRef(alternatives, newCount, nbtMatchMode, role);
        }
    }

    public record RecipeNode(ResourceLocation recipeId, MaterialRef output, int outputCount,
                             List<IngredientRef> inputs, String modTypeId,
                             ResourceLocation recipeTypeId,
                             List<MagicResourceCost> magicCosts) {
        public RecipeNode(ResourceLocation recipeId, MaterialRef output, int outputCount,
                          List<IngredientRef> inputs) {
            this(recipeId, output, outputCount, inputs, "generic",
                    new ResourceLocation("minecraft", "crafting"), List.of());
        }

        public RecipeNode(ResourceLocation recipeId, MaterialRef output, int outputCount,
                          List<IngredientRef> inputs, String modTypeId,
                          ResourceLocation recipeTypeId) {
            this(recipeId, output, outputCount, inputs, modTypeId, recipeTypeId, List.of());
        }

        public RecipeNode {
            inputs = List.copyOf(inputs);
            magicCosts = MagicResourceCost.merge(magicCosts);
            modTypeId = modTypeId == null || modTypeId.isBlank() ? "generic" : modTypeId;
            recipeTypeId = recipeTypeId == null
                    ? new ResourceLocation("minecraft", "crafting") : recipeTypeId;
            if (recipeId == null || output == null || outputCount <= 0) {
                throw new IllegalArgumentException("invalid recipe node");
            }
        }
    }
}
