package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.SelfAmplifyingRecipePolicy;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;

import javax.annotation.Nullable;
import java.util.List;

/** 配方执行方式和物理绑定需求判断，不访问库存、缓存或网络状态。 */
public final class CraftExecutionPolicy {
    private CraftExecutionPolicy() {}

    static boolean isPhysicalMachineRecipe(Recipe<?> recipe, @Nullable ModType modType) {
        return modType != null
                && modType != ModType.GENERIC
                && !modType.isVirtual()
                && modType != ModType.byId("smithing");
    }

    static boolean usesPhysicalMachineInputSlots(Recipe<?> recipe) {
        ModType type = ModType.classifyRecipe(recipe);
        if (recipe instanceof CraftingRecipe && type == ModType.CUSTOM_GUI) return false;
        return isPhysicalMachineRecipe(recipe, type) && recipe.getType() != null;
    }

    static boolean requiresTypedTerminalExecution(Recipe<?> recipe,
                                                  @Nullable ModType modType) {
        return modType != null
                && (modType.isVirtual() || isPhysicalMachineRecipe(recipe, modType));
    }

    static boolean requiresBoundMachine(@Nullable ModType modType) {
        return modType != null && modType != ModType.GENERIC && !modType.isVirtual();
    }

    static boolean requiresBoundMachine(Recipe<?> recipe, @Nullable ModType modType) {
        // 配置为 CUSTOM_GUI 的普通 CraftingRecipe 仍按逻辑配方执行；
        // 只有真实机器配方需要使用绑定上下文。
        if (recipe instanceof CraftingRecipe && modType == ModType.CUSTOM_GUI) return false;
        if (recipe instanceof CraftingRecipe && !isPhysicalMachineRecipe(recipe, modType)) {
            return false;
        }
        return isSmithingRecipe(recipe) || requiresBoundMachine(modType);
    }

    static boolean isSmithingRecipe(Recipe<?> recipe) {
        return recipe instanceof SmithingTransformRecipe || recipe instanceof SmithingTrimRecipe;
    }

    static boolean isSelfAmplifyingRecipe(Recipe<?> recipe, RegistryAccess access) {
        ItemStack output = RecipeIndex.tryGetResultItem(recipe, access);
        if (output.isEmpty()) return false;
        List<IngredientSpec> specs = recipe instanceof CraftingRecipe crafting
                ? CraftPacketUtils.extractCraftingIngredientSpecs(crafting)
                : CraftPacketUtils.extractIngredientSpecs(recipe);
        return specs != null && SelfAmplifyingRecipePolicy.isSelfAmplifying(specs, output);
    }

    static boolean canUseDirectCraftingReservation(Recipe<?> recipe,
                                                   RegistryAccess registryAccess) {
        return !isSelfAmplifyingRecipe(recipe, registryAccess);
    }

    /**
     * 奥术迭代器每次启动只完成一次物理迭代。直接终端批量订单必须把
     * 重复次数留在链外，否则执行链会把一次启动误认为已完成整批。
     */
    static boolean requiresExternalTerminalRepeat(@Nullable ResolutionStep step) {
        return step != null && step.executions() > 1
                && isArcaneIteratorRecipe(step.recipeId());
    }

    /** 单终端图只描述一次物理启动，剩余执行次数由链完成回调续跑。 */
    static ResolutionStep graphTerminalStep(CraftPlanGraph base, ResolutionStep terminalStep) {
        if (base == null || base.nodes().isEmpty()) {
            return requiresExternalTerminalRepeat(terminalStep)
                    || terminalStep.productionTarget() != null
                    ? singleExecution(terminalStep) : terminalStep;
        }
        return terminalStep;
    }

    static int graphRemainingRepeats(CraftPlanGraph graph, ResolutionStep terminalStep) {
        if (terminalStep.productionTarget() != null
                || !requiresExternalTerminalRepeat(terminalStep)
                || graph == null || graph.nodes().size() != 1) return 1;
        var node = graph.nodes().get(0);
        if (!node.recipeId().equals(terminalStep.recipeId()) || node.executions() != 1) return 1;
        return terminalStep.executions();
    }

    public static int probabilityAttemptLimit(ResolutionStep step) {
        int multiplier = 16;
        int maximum = 4096;
        try {
            multiplier = RSIntegrationConfig.CRAFTING_PROBABILISTIC_ATTEMPT_MULTIPLIER.get();
            maximum = RSIntegrationConfig.CRAFTING_PROBABILISTIC_MAX_ATTEMPTS.get();
        } catch (RuntimeException ignored) {
        }
        return Math.max(1, (int) Math.min(maximum,
                (long) Math.max(1, step.executions()) * Math.max(1, multiplier)));
    }

    /** 将奥术迭代器终端步骤收窄为一次物理启动，其余执行语义保持不变。 */
    static ResolutionStep singleExecution(ResolutionStep step) {
        return new ResolutionStep(
                step.recipeId(), step.modType(), step.recipeTypeId(),
                step.alternativeIds(), step.alternativeModTypes(), step.inferMode(),
                1, step.syntheticInput(), step.syntheticOutput(),
                step.productionTarget(), step.magicCosts());
    }

    private static boolean isArcaneIteratorRecipe(@Nullable ResourceLocation recipeId) {
        return recipeId != null
                && "wizards_reborn".equals(recipeId.getNamespace())
                && ("arcane_iterator".equals(recipeId.getPath())
                || recipeId.getPath().startsWith("arcane_iterator/"));
    }
}
