package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.ExecutionEquivalence;
import com.huanghuang.rsintegration.crafting.ProductionTarget;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraphCompactor;
import com.huanghuang.rsintegration.crafting.graph.CraftNodeMergePolicy;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;
import com.huanghuang.rsintegration.crafting.graph.TerminalGraphComposer;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.RecipeOutputSpec;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** 终端步骤构造、等价格组合和点击输出选择。 */
public final class CraftTerminalSteps {
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

    /**
     * 材料已全部在库存中时，输入图只有根需求而没有中间生产节点。
     * 这种零步骤图同样可以追加终端节点并执行。
     */
    static boolean canComposeEquivalentTerminalGraph(
            CraftPlanGraph inputGraph,
            @Nullable List<ResolutionStep> flatSteps,
            List<String> missing) {
        return inputGraph != null
                && missing != null && missing.isEmpty()
                && ((flatSteps != null && !flatSteps.isEmpty())
                || !inputGraph.rootDemands().isEmpty());
    }

    public static CraftPlanGraph composeEquivalentTerminalGraph(
            CraftPlanGraph inputGraph, ResolutionStep terminalStep, ItemStack output,
            Level level) {
        // 召唤祭坛只负责备料，生物及世界掉落均不属于执行图的产物。
        boolean preparationOnly = ModIds.ID_SUMMONING_RITUALS.equals(terminalStep.modType().id());
        List<TerminalGraphComposer.TerminalOutput> terminalOutputs = preparationOnly
                ? List.of() : terminalOutputs(terminalStep, output, level);
        CraftPlanGraph completeGraph = preparationOnly
                ? TerminalGraphComposer.composePreparation(inputGraph, terminalStep)
                : TerminalGraphComposer.compose(inputGraph, terminalStep, terminalOutputs);
        List<ResolutionStep> expectedFlat = new ArrayList<>(
                ExecutionEquivalence.projectFlatSteps(inputGraph));
        expectedFlat.add(preparationOnly ? terminalStep : expectedTerminalStep(terminalStep, terminalOutputs));
        ExecutionEquivalence.Report equivalence =
                ExecutionEquivalence.compare(completeGraph, expectedFlat);
        if (!equivalence.equivalent()) {
            throw new IllegalArgumentException(
                    "terminal graph execution mismatch: " + equivalence.mismatches());
        }
        return CraftPlanGraphCompactor.compact(completeGraph,
                CraftNodeMergePolicy.forLevel(level));
    }

    static ResolutionStep expectedTerminalStep(
            ResolutionStep terminalStep,
            List<TerminalGraphComposer.TerminalOutput> outputs
    ) {
        TerminalGraphComposer.TerminalOutput primary = outputs.stream()
                .filter(output -> output.kind() == OutputKind.PRIMARY)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "terminal output must include a primary output"));
        ProductionTarget target = terminalStep.productionTarget();
        if (target == null && primary.probability().isGuaranteed()
                && primary.minimumPerOperation() > 0) {
            long perOperation = Math.max(1L, primary.perOperation());
            long quantity = Math.multiplyExact(
                    perOperation, Math.max(1, terminalStep.executions()));
            target = ProductionTarget.of(primary.resource(), quantity);
        }
        return new ResolutionStep(
                terminalStep.recipeId(),
                terminalStep.modType(),
                terminalStep.recipeTypeId(),
                terminalStep.alternativeIds(),
                terminalStep.alternativeModTypes(),
                terminalStep.inferMode(),
                terminalStep.executions(),
                terminalStep.syntheticInput(),
                terminalStep.syntheticOutput(),
                target,
                terminalStep.magicCosts()
        );
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
        if (recipe != null && handler != null) {
            List<RecipeOutputSpec> declared = handler.getDeclaredOutputs(
                    recipe, level.registryAccess());
            List<TerminalGraphComposer.TerminalOutput> declaredOutputs =
                    new ArrayList<>(declared.size());
            boolean primaryHandled = false;
            for (RecipeOutputSpec output : declared) {
                MaterialKey material = output.material();
                IntegrationResourceKey resource = output.resource();
                if (material == null
                        && resource.kind()
                        == IntegrationResourceKey.ResourceKind.FLUID) {
                    material = MaterialKey.of(
                            InkFluidSupport.tokenFor(resource, 1));
                }
                int expected = output.expectedPerOperation();
                int minimum = output.minimumPerOperation();
                int maximum = output.maximumPerOperation();
                // 名义数量用于显示和按次数下单，不把零必出下限误当成空配方。
                if (output.kind() == OutputKind.PRIMARY && expected == 0) expected = maximum;
                OutputProbability probability = output.probability();
                boolean dynamicIdentity = output.dynamicIdentity();
                if (!primaryHandled
                        && output.kind() == OutputKind.PRIMARY
                        && terminalStep.productionTarget() != null) {
                    ItemStack target =
                            terminalStep.productionTarget().displayStack().copyWithCount(
                                    (int) Math.min(
                                            Integer.MAX_VALUE,
                                            Math.max(
                                                    1L,
                                                    terminalStep
                                                            .productionTarget()
                                                            .quantity()
                                            )
                                    )
                            );
                    material = MaterialKey.of(target);
                    expected = Math.max(1, (int) Math.min(Integer.MAX_VALUE,
                            (terminalStep.productionTarget().quantity() - 1L)
                                    / Math.max(1, terminalStep.executions()) + 1L));
                    minimum = 0;
                    maximum = Math.max(maximum, expected);
                    resource = terminalStep.productionTarget().resource();
                    primaryHandled = true;
                }
                if (output.kind() == OutputKind.PRIMARY
                        && !handler.hasStructuredOutputContract(recipe)) {
                    probability =
                            handler.hasDeterministicPrimaryOutput(recipe)
                                    ? OutputProbability.guaranteed()
                                    : OutputProbability.dynamic(
                                    null,
                                    CraftExecutionPolicy
                                            .probabilityAttemptLimit(
                                                    terminalStep),
                                    "terminal:" + terminalStep.recipeId()
                            );
                    dynamicIdentity =
                            handler.hasRuntimeDependentPrimaryNbt(recipe);
                }
                declaredOutputs.add(new TerminalGraphComposer.TerminalOutput(
                        material,
                        expected,
                        minimum,
                        maximum,
                        output.kind(),
                        probability,
                        dynamicIdentity,
                        output.sourceInputIndex(),
                        resource));
            }
            if (declaredOutputs.stream().anyMatch(output ->
                    output.kind() == OutputKind.PRIMARY)) {
                return List.copyOf(declaredOutputs);
            }
        }
        ItemStack primaryDeclaration = terminalStep.productionTarget() == null
                ? primary : terminalStep.productionTarget().displayStack().copyWithCount(
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
        IntegrationResourceKey targetResource =
                terminalStep.productionTarget() == null
                        ? null
                        : terminalStep.productionTarget().resource();
        outputs.add(targetResource == null
                ? TerminalGraphComposer.TerminalOutput.item(
                MaterialKey.of(primaryDeclaration),
                primaryDeclaration.getCount(),
                OutputKind.PRIMARY,
                probability,
                dynamicIdentity)
                : new TerminalGraphComposer.TerminalOutput(
                MaterialKey.of(primaryDeclaration),
                primaryDeclaration.getCount(),
                probability.isGuaranteed()
                        ? primaryDeclaration.getCount() : 0,
                primaryDeclaration.getCount(),
                OutputKind.PRIMARY,
                probability,
                dynamicIdentity,
                null,
                targetResource));
        if (recipe == null) return List.copyOf(outputs);
        List<ItemStack> secondaries = handler == null
                ? ModRecipeHandlers.tryGetSecondaryOutputs(recipe, level.registryAccess())
                : handler.getSecondaryOutputs(recipe, level.registryAccess());
        for (ItemStack secondary : secondaries) {
            if (secondary == null || secondary.isEmpty()) continue;
            outputs.add(TerminalGraphComposer.TerminalOutput.item(
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
