package com.huanghuang.rsintegration.recipe;
import com.huanghuang.rsintegration.recipe.InstantRecipePolicy;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import java.util.ArrayList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.core.RegistryAccess;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * Per-mod recipe handler — encapsulates ingredient extraction, result retrieval,
 * and secondary output discovery for a specific mod's recipe patterns.
 *
 * <p>Replaces the scattered probe methods in {@code CraftPacketUtils} and
 * {@code ModRecipeIndex} with a single extension point per mod.</p>
 */
public interface ModRecipeHandler {

    /**
     * claim 是配方语义的归属判定，不按注册顺序决定。
     * 数字越大越优先，层级从通用回退到具体机器族依次升高。
     */
    int CLAIM_PRIORITY_GENERIC = -1_000;
    int CLAIM_PRIORITY_VANILLA_FAMILY = -500;
    int CLAIM_PRIORITY_MOD_FAMILY = -100;
    int CLAIM_PRIORITY_SPECIALIZED = 0;

    @Nonnull
    ModType modType();

    /** Quick check: does this handler claim responsibility for the given recipe? */
    boolean canHandle(@Nonnull Recipe<?> recipe);

    /** 数值越大越优先；同优先级且同时命中时视为配方 claim 冲突。 */
    default int claimPriority() {
        return CLAIM_PRIORITY_SPECIALIZED;
    }

    /** Whether {@link #canHandle} depends only on the recipe class. */
    default boolean cacheByRecipeClass() {
        return true;
    }

    /** Whether this handler's ingredient semantics override generic CraftTweaker probes. */
    default boolean preferHandlerIngredients() {
        return false;
    }

    /** Runtime requirements that are intentionally not consumed as ingredients. */
    default boolean isAvailableForPlanning(@Nonnull Recipe<?> recipe,
                                           @Nullable ServerPlayer player) {
        return true;
    }

    /**
     * Whether one concrete bound machine can execute this recipe. This is used
     * by both recursive candidate pruning and final machine enumeration, so a
     * recipe accepted during planning cannot later be routed to an incompatible
     * machine family or tier.
     */
    default boolean isCompatibleBinding(@Nonnull Recipe<?> recipe,
                                        @Nullable String blockKey) {
        return true;
    }

    /** Extract the primary result item for display/indexing purposes. */
    @Nonnull
    ItemStack getResultItem(@Nonnull Recipe<?> recipe, @Nonnull RegistryAccess access);

    /** Extract ingredients with their required counts. Returns null if this handler cannot parse the recipe. */
    @Nullable
    List<IngredientSpec> getIngredients(Recipe<?> recipe);

    /**
     * 返回允许进入递归合成图的整批材料。运行时专用资源可以在这里移除，同时继续
     * 保留在 {@link #getIngredients(Recipe)} 中，供实际预留和机器执行使用。
     */
    @Nonnull
    default List<ScaledIngredientSpec> getRecursiveScaledIngredients(
            @Nonnull Recipe<?> recipe,
            @Nullable List<ScaledIngredientSpec> ingredients) {
        return ingredients == null ? List.of() : ingredients;
    }

    /** 根据已绑定机器的实际状态调整递归材料；默认保持配方声明。 */
    @Nonnull
    default List<IngredientSpec> getPlanningIngredients(
            @Nonnull Recipe<?> recipe, @Nullable ServerPlayer player,
            @Nullable List<IngredientSpec> ingredients) {
        return ingredients == null ? List.of() : ingredients;
    }

    /**
     * Total quantity required for one input when planning a multi-execution order.
     * Most inputs use their demand role directly; machine handlers may override
     * this when one physical cycle covers several logical executions.
     */
    default int requiredIngredientCount(@Nonnull Recipe<?> recipe,
                                        @Nonnull IngredientSpec spec,
                                        int inputIndex, int executions) {
        return CraftPacketUtils.requiredCount(spec, executions);
    }

    default long requiredIngredientCountLong(@Nonnull Recipe<?> recipe,
                                             @Nonnull IngredientSpec spec,
                                             int inputIndex, long executions) {
        if (executions <= Integer.MAX_VALUE) {
            return requiredIngredientCount(recipe, spec, inputIndex, (int) executions);
        }
        return CraftPacketUtils.requiredCount(spec, executions);
    }

    /** Secondary/byproduct outputs. Default empty — most mods don't have them. */
    @Nonnull
    default List<ItemStack> getSecondaryOutputs(@Nonnull Recipe<?> recipe, @Nonnull RegistryAccess access) {
        return List.of();
    }

    /**
     * 声明单次操作的完整输出合同。默认实现把主产物和副产物投影为确定输出，
     * 新处理器可以覆盖该方法保留概率、数量范围和动态身份。
     */
    @Nonnull
    default List<RecipeOutputSpec> getDeclaredOutputs(
            @Nonnull Recipe<?> recipe,
            @Nonnull RegistryAccess access
    ) {
        List<RecipeOutputSpec> outputs = new ArrayList<>();
        ItemStack primary = getResultItem(recipe, access);
        if (!primary.isEmpty()) {
            outputs.add(new RecipeOutputSpec(
                    MaterialKey.of(primary),
                    primary.getCount(),
                    OutputKind.PRIMARY
            ));
        }
        for (ItemStack secondary : getSecondaryOutputs(recipe, access)) {
            if (secondary == null || secondary.isEmpty()) continue;
            outputs.add(new RecipeOutputSpec(
                    MaterialKey.of(secondary),
                    secondary.getCount(),
                    OutputKind.SECONDARY
            ));
        }
        return List.copyOf(outputs);
    }

    /**
     * 资源感知处理器可一次性提交完整的不可变配方节点。
     *
     * <p>返回 null 表示沿用旧物品投影；返回空列表表示该处理器负责资源投影，
     * 但当前配方没有可递归的确定性输出。</p>
     */
    @Nullable
    default List<RecipeNode> projectImmutableRecipes(@Nonnull Recipe<?> recipe) {
        return null;
    }

    /** 资源感知处理器可声明无显式 target 时的权威根资源。 */
    @Nullable
    default MaterialRef preferredImmutableOutput(@Nonnull Recipe<?> recipe) {
        return null;
    }

    /** 配方是否声明了必须由流体存储接收的标准输出。 */
    default boolean hasFluidOutputs(@Nonnull Recipe<?> recipe) {
        return false;
    }

    /** 是否由处理器提供完整声明输出，而不是仅依赖主产物/副产物兼容投影。 */
    default boolean hasStructuredOutputContract(@Nonnull Recipe<?> recipe) {
        return false;
    }

    /** Whether one execution is guaranteed to produce the item returned by {@link #getResultItem}. */
    default boolean hasDeterministicPrimaryOutput(@Nonnull Recipe<?> recipe) {
        return true;
    }

    /** 显式声明允许按实际产物补做；手动仪式等非确定行为不能自动加入。 */
    default boolean supportsTargetedProduction(@Nonnull Recipe<?> recipe) {
        return false;
    }

    /** 指定目标是否需要实际产量门槛，包括概率副产物。 */
    default boolean requiresTargetedProduction(@Nonnull Recipe<?> recipe, @Nonnull ItemStack output) {
        return supportsTargetedProduction(recipe) && !hasDeterministicPrimaryOutput(recipe);
    }

    /** Whether the primary output copies or otherwise derives NBT from runtime inputs. */
    default boolean hasRuntimeDependentPrimaryNbt(@Nonnull Recipe<?> recipe) {
        return false;
    }

    /**
     * Whether this recipe exposes a complete value-only input/output contract.
     * Such recipes may be projected once during catalog construction and
     * planned off-thread; machine interaction still remains on the server thread.
     * Runtime-derived NBT is represented explicitly in the graph and can only
     * satisfy item-only demands. It does not make the item or its count random.
     */
    default boolean supportsBackgroundPlanning(@Nonnull Recipe<?> recipe) {
        if (!hasDeterministicPrimaryOutput(recipe)) {
            return false;
        }
        List<IngredientSpec> ingredients = getIngredients(recipe);
        List<ScaledIngredientSpec> specs = getRecursiveScaledIngredients(recipe,
                ingredients == null ? List.of() : ingredients.stream()
                        .map(spec -> ScaledIngredientSpec.of(spec, spec.count()))
                        .toList());
        return !specs.isEmpty();
    }

    /**
     * Allows a value-only recipe into the recursive dependency graph even when
     * its mod type still requires the legacy flat executor at runtime.
     */
    default boolean supportsIntermediateProjection(@Nonnull Recipe<?> recipe) {
        return false;
    }

    /**
     * 显式声明即时配方的安全批量语义。
     *
     * <p>默认逐次执行。直接账本结算要求处理器已完整描述成本、产物、
     * 副产物和材料角色；需要真实装配副作用的配方应使用有限模拟批。</p>
     */
    @Nonnull
    default InstantRecipePolicy instantRecipePolicy(@Nonnull Recipe<?> recipe) {
        return InstantRecipePolicy.none();
    }

    /**
     * Whether the JEI stack clicked by the player selects a concrete output
     * variant that cannot be recovered from the recipe's declared result alone.
     */
    default boolean useClickedPrimaryOutput(@Nonnull Recipe<?> recipe,
                                            @Nonnull ItemStack declared,
                                            @Nonnull ItemStack clicked) {
        return declared.hasTag() && clicked.hasTag();
    }

    /**
     * Whether the primary result should be exposed as a recursive item producer.
     * Some recipes use a JEI-only placeholder for an entity/fluid/world action;
     * those recipes may still be previewable, but the placeholder must not enter
     * the item recipe index.
     */
    default boolean indexPrimaryOutput(@Nonnull Recipe<?> recipe) {
        return true;
    }
}
