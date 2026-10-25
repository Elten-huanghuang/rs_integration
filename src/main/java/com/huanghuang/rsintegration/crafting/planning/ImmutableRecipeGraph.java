package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CanonicalNbt;
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

    public enum ResourceKind {
        ITEM,
        FLUID
    }

    /**
     * 与具体槽位实现无关的配方资源身份。
     *
     * <p>runtimeNbt 表示未知；constrainedNbt 仅保证已建模的状态下限，不能用作精确 NBT。</p>
     */
    public record MaterialRef(ResourceKind kind, ResourceLocation resourceId, String nbt,
                              boolean runtimeNbt, boolean constrainedNbt) {
        public MaterialRef(ResourceLocation itemId, String nbt) {
            this(ResourceKind.ITEM, itemId, nbt, false, false);
        }

        public MaterialRef(ResourceLocation itemId, String nbt, boolean runtimeNbt) {
            this(ResourceKind.ITEM, itemId, nbt, runtimeNbt, false);
        }

        public MaterialRef(ResourceLocation itemId, String nbt, boolean runtimeNbt,
                           boolean constrainedNbt) {
            this(ResourceKind.ITEM, itemId, nbt, runtimeNbt, constrainedNbt);
        }

        public static MaterialRef fluid(ResourceLocation fluidId, String nbt) {
            return new MaterialRef(ResourceKind.FLUID, fluidId, nbt, false, false);
        }

        public static MaterialRef fluid(ResourceLocation fluidId) {
            return fluid(fluidId, "");
        }

        public MaterialRef {
            if (kind == null || resourceId == null) {
                throw new IllegalArgumentException("resource kind and id are required");
            }
            String normalized = CanonicalNbt.normalizeSnbt(nbt);
            nbt = normalized == null ? "" : normalized;
            if (runtimeNbt && !nbt.isEmpty()) throw new IllegalArgumentException("unknown output NBT");
            if (constrainedNbt && (runtimeNbt || nbt.isEmpty())) {
                throw new IllegalArgumentException("missing constrained output NBT");
            }
        }

        /** 兼容旧的仅物品调用点；新代码应按 kind 分支后使用 resourceId。 */
        public ResourceLocation itemId() {
            return resourceId;
        }

        public boolean isItem() {
            return kind == ResourceKind.ITEM;
        }

        public boolean isFluid() {
            return kind == ResourceKind.FLUID;
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
                                NbtMatchMode nbtMatchMode, DemandRole role,
                                boolean recursive) {
        public IngredientRef(List<MaterialRef> alternatives, long count) {
            this(alternatives, count, NbtMatchMode.EXACT, DemandRole.CONSUMED, true);
        }

        public IngredientRef(List<MaterialRef> alternatives, long count,
                             NbtMatchMode nbtMatchMode) {
            this(alternatives, count, nbtMatchMode, DemandRole.CONSUMED, true);
        }

        public IngredientRef(List<MaterialRef> alternatives, long count,
                             NbtMatchMode nbtMatchMode, DemandRole role) {
            this(alternatives, count, nbtMatchMode, role, true);
        }

        public IngredientRef {
            alternatives = List.copyOf(alternatives);
            nbtMatchMode = nbtMatchMode == null ? NbtMatchMode.EXACT : nbtMatchMode;
            role = role == null ? DemandRole.CONSUMED : role;
            if (alternatives.isEmpty() || count <= 0) throw new IllegalArgumentException("empty ingredient");
            ResourceKind kind = alternatives.get(0).kind();
            if (alternatives.stream().anyMatch(alternative -> alternative.kind() != kind)) {
                throw new IllegalArgumentException("ingredient alternatives must share a resource kind");
            }
        }

        public IngredientRef withCount(long newCount) {
            return new IngredientRef(alternatives, newCount, nbtMatchMode, role, recursive);
        }
    }

    public record RecipeNode(ResourceLocation recipeId, MaterialRef output, long outputCount,
                             List<IngredientRef> inputs, String modTypeId,
                             ResourceLocation recipeTypeId,
                             List<MagicResourceCost> magicCosts,
                             boolean hasFluidOutputs,
                             boolean dynamicIdentityTargetable) {
        public RecipeNode(ResourceLocation recipeId, MaterialRef output, long outputCount,
                          List<IngredientRef> inputs) {
            this(recipeId, output, outputCount, inputs, "generic",
                    new ResourceLocation("minecraft", "crafting"), List.of(),
                    false, false);
        }

        public RecipeNode(ResourceLocation recipeId, MaterialRef output, long outputCount,
                          List<IngredientRef> inputs, String modTypeId,
                          ResourceLocation recipeTypeId) {
            this(recipeId, output, outputCount, inputs, modTypeId, recipeTypeId,
                    List.of(), false, false);
        }

        public RecipeNode(ResourceLocation recipeId, MaterialRef output, long outputCount,
                          List<IngredientRef> inputs, String modTypeId,
                          ResourceLocation recipeTypeId,
                          List<MagicResourceCost> magicCosts) {
            this(recipeId, output, outputCount, inputs, modTypeId,
                    recipeTypeId, magicCosts, false, false);
        }

        public RecipeNode(ResourceLocation recipeId, MaterialRef output, long outputCount,
                          List<IngredientRef> inputs, String modTypeId,
                          ResourceLocation recipeTypeId,
                          List<MagicResourceCost> magicCosts,
                          boolean hasFluidOutputs) {
            this(recipeId, output, outputCount, inputs, modTypeId,
                    recipeTypeId, magicCosts, hasFluidOutputs, false);
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
