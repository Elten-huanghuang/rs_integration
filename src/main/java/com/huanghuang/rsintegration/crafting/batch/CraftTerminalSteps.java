package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.ExecutionEquivalence;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraphCompactor;
import com.huanghuang.rsintegration.crafting.graph.CraftNodeMergePolicy;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;
import com.huanghuang.rsintegration.crafting.graph.TerminalGraphComposer;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** 终端步骤构造、等价格组合和点击输出选择。 */
final class CraftTerminalSteps {
    private CraftTerminalSteps() {}

    static int totalExecutions(List<ResolutionStep> steps) {
        long total = 0L;
        for (ResolutionStep step : steps) {
            total += Math.max(1, step.executions());
            if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    static ResolutionStep genericTerminalStep(ResourceLocation recipeId, int repeatCount) {
        return new ResolutionStep(recipeId, ModType.GENERIC,
                new ResourceLocation("minecraft:crafting"), List.of(), List.of(), false,
                Math.max(1, repeatCount));
    }

    static List<ResolutionStep> genericExecutionSteps(
            List<ResolutionStep> intermediateSteps, ResourceLocation recipeId, int repeatCount) {
        List<ResolutionStep> executionSteps = new ArrayList<>(intermediateSteps);
        executionSteps.add(genericTerminalStep(recipeId, repeatCount));
        return List.copyOf(executionSteps);
    }

    static ResolutionStep smithingTerminalStep(ResourceLocation recipeId, int repeatCount) {
        return new ResolutionStep(recipeId, ModType.GENERIC,
                new ResourceLocation("minecraft:smithing"), List.of(), List.of(), false,
                Math.max(1, repeatCount));
    }

    static ResolutionStep backgroundPhysicalTerminalStep(
            ResourceLocation recipeId, ModType terminalType,
            boolean inferMode, int repeatCount) {
        return terminalType == ModType.byId("smithing")
                ? smithingTerminalStep(recipeId, repeatCount)
                : new ResolutionStep(recipeId, terminalType, recipeId,
                        List.of(), List.of(), inferMode, Math.max(1, repeatCount));
    }

    static CraftPlanGraph composeEquivalentTerminalGraph(
            CraftPlanGraph inputGraph, ResolutionStep terminalStep, ItemStack output,
            Level level) {
        ResolutionStep graphTerminal =
                CraftExecutionPolicy.graphTerminalStep(inputGraph, terminalStep);
        CraftPlanGraph completeGraph = TerminalGraphComposer.compose(
                inputGraph, graphTerminal, terminalOutputs(graphTerminal, output, level));
        List<ResolutionStep> expectedFlat = new ArrayList<>(
                ExecutionEquivalence.projectFlatSteps(inputGraph));
        expectedFlat.add(graphTerminal);
        ExecutionEquivalence.Report equivalence =
                ExecutionEquivalence.compare(completeGraph, expectedFlat);
        if (!equivalence.equivalent()) {
            throw new IllegalArgumentException(
                    "terminal graph execution mismatch: " + equivalence.mismatches());
        }
        return CraftPlanGraphCompactor.compact(completeGraph,
                CraftNodeMergePolicy.forLevel(level));
    }

    static ItemStack selectTerminalGraphOutput(
            Recipe<?> recipe, ItemStack recipeOutput, @Nullable ItemStack clickedOutput) {
        return selectTerminalGraphOutput(
                recipe, recipeOutput, clickedOutput,
                ModRecipeHandlers.claimFor(recipe).winner());
    }

    private static List<TerminalGraphComposer.TerminalOutput> terminalOutputs(
            ResolutionStep terminalStep, ItemStack primary, Level level) {
        List<TerminalGraphComposer.TerminalOutput> outputs = new ArrayList<>();
        Recipe<?> recipe = level.getRecipeManager()
                .byKey(terminalStep.recipeId()).orElse(null);
        ModRecipeHandler handler = recipe == null
                ? null : ModRecipeHandlers.claimFor(recipe).winner();
        ItemStack primaryDeclaration = terminalStep.productionTarget() == null
                ? primary : terminalStep.productionTarget().material().toStack(
                (int) Math.min(Integer.MAX_VALUE,
                        Math.max(1L, terminalStep.productionTarget().quantity())));
        boolean deterministic = handler == null || handler.hasDeterministicPrimaryOutput(recipe);
        OutputProbability probability = deterministic
                ? OutputProbability.guaranteed()
                : OutputProbability.dynamic(null,
                CraftExecutionPolicy.probabilityAttemptLimit(terminalStep),
                "terminal:" + terminalStep.recipeId());
        boolean dynamicIdentity = handler != null
                && recipe != null && handler.hasRuntimeDependentPrimaryNbt(recipe);
        outputs.add(new TerminalGraphComposer.TerminalOutput(
                MaterialKey.of(primaryDeclaration), primaryDeclaration.getCount(),
                OutputKind.PRIMARY, probability, dynamicIdentity));
        if (recipe == null) return List.copyOf(outputs);
        List<ItemStack> secondaries = handler == null
                ? ModRecipeHandlers.tryGetSecondaryOutputs(recipe, level.registryAccess())
                : handler.getSecondaryOutputs(recipe, level.registryAccess());
        for (ItemStack secondary : secondaries) {
            if (secondary == null || secondary.isEmpty()) continue;
            outputs.add(new TerminalGraphComposer.TerminalOutput(
                    MaterialKey.of(secondary), secondary.getCount(), OutputKind.SECONDARY));
        }
        return List.copyOf(outputs);
    }

    static ItemStack selectTerminalGraphOutput(
            Recipe<?> recipe, ItemStack recipeOutput, @Nullable ItemStack clickedOutput,
            @Nullable ModRecipeHandler handler) {
        boolean runtimeNbt = handler != null && handler.hasRuntimeDependentPrimaryNbt(recipe);
        ItemStack declared = runtimeNbt
                ? itemOnlyDeclaration(recipeOutput)
                : CraftingResolver.resolveDeclaredOutput(recipe, recipeOutput);
        if (declared.isEmpty() || clickedOutput == null || clickedOutput.isEmpty()
                || clickedOutput.getItem() != declared.getItem() || runtimeNbt) {
            logTerminalOutputSelection(recipe, handler, runtimeNbt, recipeOutput,
                    clickedOutput, declared, false);
            return declared;
        }
        boolean useClicked = handler != null
                ? handler.useClickedPrimaryOutput(recipe, declared, clickedOutput)
                : declared.hasTag() && clickedOutput.hasTag();
        ItemStack selected = useClicked
                ? clickedOutput.copyWithCount(declared.getCount())
                : declared;
        logTerminalOutputSelection(recipe, handler, runtimeNbt, recipeOutput,
                clickedOutput, selected, useClicked);
        return selected;
    }

    private static ItemStack itemOnlyDeclaration(ItemStack output) {
        return output == null || output.isEmpty()
                ? ItemStack.EMPTY
                : new ItemStack(output.getItem(), output.getCount());
    }

    private static void logTerminalOutputSelection(
            Recipe<?> recipe, @Nullable ModRecipeHandler handler, boolean runtimeNbt,
            ItemStack recipeOutput, @Nullable ItemStack clickedOutput,
            ItemStack selected, boolean useClicked) {
        if (!"goety".equals(recipe.getId().getNamespace())) return;
        RSIntegrationMod.LOGGER.debug(
                "[RSI-GraphOutput] recipe={} handler={} runtimeNbt={} useClicked={} "
                        + "recipeTag={} clickedTag={} selectedTag={}",
                recipe.getId(), handler == null ? "none" : handler.getClass().getSimpleName(),
                runtimeNbt, useClicked, recipeOutput.getTag(),
                clickedOutput == null ? null : clickedOutput.getTag(), selected.getTag());
    }
}
