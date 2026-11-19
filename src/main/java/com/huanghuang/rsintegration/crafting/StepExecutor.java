package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.mods.crockpot.CrockPotRecipeHandler;
import java.util.stream.Collectors;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.Items;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;
import com.huanghuang.rsintegration.util.PerformanceMonitor;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotCategoryPlanning;
import com.huanghuang.rsintegration.mods.goety.GoetyDynamicRitualRecipe;
import com.huanghuang.rsintegration.mods.farmersdelight.MinersDelightCopperPotSupport;
import com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladeRecipeHandler;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladePlanning;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.RecipeOutputSpec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 规划配方槽位、登记产出，并在等待子材料时保存当前步骤。
 */
final class StepExecutor {

    private StepExecutor() {}

    static int maxDepth() { return RSIntegrationConfig.CRAFTING_MAX_DEPTH.get(); }
    static int maxSteps() { return RSIntegrationConfig.CRAFTING_MAX_STEPS.get(); }

    static int mulCount(int count, int batches) {
        long result = (long) count * (long) batches;
        if (result > Integer.MAX_VALUE) {
            RSIntegrationMod.LOGGER.warn("[RSI-Step] count overflow: {} * {} = {}, clamping", count, batches, result);
            return Integer.MAX_VALUE;
        }
        return (int) result;
    }

    private static void craftBatched(CraftingRecipe recipe, ResolutionContext ctx, int depth,
                                List<ResourceLocation> altIds, List<String> altModTypes,
                                CraftingResolver.EdgeTracker edges, int batches,
                                PlanningStack stack, Consumer<Boolean> resultCallback) {
        ctx.beginUndo();
        edges.beginUndo();
        NodeId graphNodeId = ctx.allocateNodeId();

        List<IngredientSpec> specs = narrowMaterialLocks(
                recipe.getId(), CraftPacketUtils.extractCraftingIngredientSpecs(recipe), ctx);
        planRecipeSpecsForGraph(recipe, specs, graphNodeId, ctx, depth, edges, batches, stack,
                graphInputs -> finishCraftingStep(recipe, ctx, altIds, altModTypes, edges, batches,
                        graphNodeId, specs, graphInputs, stack, resultCallback));
    }

    private static void finishCraftingStep(CraftingRecipe recipe, ResolutionContext ctx,
                                           List<ResourceLocation> altIds, List<String> altModTypes,
                                           CraftingResolver.EdgeTracker edges, int batches, NodeId graphNodeId,
                                           List<IngredientSpec> specs, List<InputDemand> graphInputs,
                                           PlanningStack stack, Consumer<Boolean> done) {
        if (!canFinishStep(graphInputs, ctx, edges, stack, done)) return;

        List<OutputDeclaration> graphOutputs = new ArrayList<>();
        for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(recipe)) {
            registerGraphOutput(remainder, remainderBatches(remainder, specs, batches), OutputKind.REMAINDER,
                    graphNodeId, graphOutputs, ctx);
        }

        ItemStack result = ModRecipeHandlers.tryGetResultItem(recipe, ctx.level.registryAccess());
        result = SlashBladeRecipeHandler.planningOutput(recipe, result);
        registerGraphOutput(result, batches, OutputKind.PRIMARY, graphNodeId, graphOutputs, ctx);

        for (ItemStack secondary : ModRecipeHandlers.tryGetSecondaryOutputs(recipe, ctx.level.registryAccess())) {
            registerGraphOutput(secondary, batches, OutputKind.SECONDARY,
                    graphNodeId, graphOutputs, ctx);
        }

        CraftingResolver.ResolutionStep step = new CraftingResolver.ResolutionStep(recipe.getId(), ModType.GENERIC,
                new ResourceLocation("minecraft:crafting"), altIds, altModTypes, false, batches);
        ctx.steps.add(step);
        ctx.addGraphNode(toGraphNode(graphNodeId, step, graphInputs, graphOutputs));

        ctx.commitUndo();
        edges.commitUndo();
        stack.complete(done, true);
    }

    static boolean craftBatched(RecipeIndex.Entry entry, ResolutionContext ctx, int depth,
                                List<ResourceLocation> altIds, List<String> altModTypes,
                                CraftingResolver.EdgeTracker edges, int batches,
                                ItemStack demandedOutput) {
        var stack = new PlanningStack();
        return stack.resolve(done -> craftBatched(entry, ctx, depth, altIds, altModTypes,
                edges, batches, demandedOutput, stack, done));
    }

    static void craftBatched(RecipeIndex.Entry entry, ResolutionContext ctx, int depth,
                             List<ResourceLocation> altIds, List<String> altModTypes,
                             CraftingResolver.EdgeTracker edges, int batches, ItemStack demandedOutput,
                             PlanningStack stack, Consumer<Boolean> done) {
        if (ctx.timedOut()) {
            PerformanceMonitor.recordResolveTimeout();
            stack.complete(done, false);
            return;
        }
        if (depth > maxDepth()) {
            ctx.recordDepthGuard(depth, maxDepth());
            stack.complete(done, false);
            return;
        }
        if (ctx.steps.size() + 1 > maxSteps()) {
            ctx.recordStepGuard(ctx.steps.size(), maxSteps());
            stack.complete(done, false);
            return;
        }

        Runnable compatible = () -> craftCompatible(entry, ctx, depth, altIds, altModTypes, edges,
                batches, demandedOutput, stack, done);
        if (ctx.hybrid != null && ctx.hybrid.start(entry, ctx, depth, batches, altIds, altModTypes,
                demandedOutput, stack, compatible, done)) return;
        compatible.run();
    }

    private static void craftCompatible(RecipeIndex.Entry entry, ResolutionContext ctx, int depth,
                             List<ResourceLocation> altIds, List<String> altModTypes,
                             CraftingResolver.EdgeTracker edges, int batches, ItemStack demandedOutput,
                             PlanningStack stack, Consumer<Boolean> done) {
        if (entry.modType() == ModType.GENERIC && entry.recipe() instanceof CraftingRecipe cr) {
            craftBatched(cr, ctx, depth, altIds, altModTypes, edges, batches, stack, done);
            return;
        }

        ctx.beginUndo();
        edges.beginUndo();
        NodeId graphNodeId = ctx.allocateNodeId();

        boolean goetyDynamic = GoetyDynamicRitualRecipe.isSupported(entry.recipe())
                && demandedOutput != null && !demandedOutput.isEmpty();
        List<IngredientSpec> specs = goetyDynamic
                ? GoetyDynamicRitualRecipe.buildMaterials(entry.recipe(), demandedOutput)
                : ctx.readRecipe(entry.recipe(), () -> CraftPacketUtils.extractIngredientSpecs(entry.recipe()));
        specs = MinersDelightCopperPotSupport.adaptIngredientSpecs(
                entry.modType(), specs, entry.recipe(), ctx.level.registryAccess());
        if (entry.modType() == ModType.byId("crockpot")
                && CrockPotRecipeHandler.hasCategoryConstraints(entry.recipe())) {
            specs = CrockPotCategoryPlanning.ingredients(CrockPotCategoryPlanning.capture(
                    entry.recipe(), demandedOutput, ctx.counts, ctx.level, CrockPotRecipeHandler.INPUT_SLOT_COUNT));
            if (specs == null) {
                ctx.rollback();
                edges.rollback();
                stack.complete(done, false);
                return;
            }
        }

        // If the standard ingredient path returned garbage (AIR, bare items
        // without NBT from TACZ/Applied Armorer), repair via reflection.
        boolean broken = specs == null || specs.isEmpty();
        if (!broken) {
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                for (ItemStack input : spec.ingredient().getItems()) {
                    if (input.isEmpty()) continue;
                    if (input.getItem() == Items.AIR) { broken = true; break; }
                    ResourceLocation rl = ForgeRegistries.ITEMS.getKey(input.getItem());
                    if (rl != null && !input.hasTag()) {
                        String ns = rl.getNamespace();
                        if ("tacz".equals(ns) || "applied_armorer".equals(ns)) { broken = true; break; }
                    }
                }
                if (broken) break;
            }
        }
        if (broken) {
            List<ItemStack> repaired = CraftingResolver.getRepairedInputStacks(
                    entry.recipe(), ctx.level.registryAccess());
            if (!repaired.isEmpty()) {
                specs = new ArrayList<>();
                for (ItemStack input : repaired) {
                    specs.add(new IngredientSpec(
                            CraftingResolver.ingredientOf(input, input.hasTag()), input.getCount()));
                }
            }
        }

        if (demandedOutput != null
                && entry.recipe() instanceof SmithingTransformRecipe smithing) {
            specs = SmithingRecipeHandler.requireDemandedOutputTag(
                    smithing, specs, demandedOutput);
        }

        ItemStack plannedBladeOutput = null;
        if (SlashBladeRecipeHandler.isShapedBladeRecipe(entry.recipe()) && specs != null) {
            ItemStack declared = ModRecipeHandlers.tryGetResultItem(entry.recipe(), ctx.level.registryAccess());
            specs = SlashBladePlanning.requireOutput(specs, declared, demandedOutput);
            plannedBladeOutput = SlashBladePlanning.minimumOutput(declared, specs);
        }

        specs = narrowMaterialLocks(entry.recipe().getId(), specs, ctx);

        if (specs == null || specs.isEmpty()) {
            ctx.rollback();
            edges.rollback();
            stack.complete(done, false);
            return;
        }

        List<IngredientSpec> plannedSpecs = specs;
        ItemStack bladeOutput = plannedBladeOutput;
        planRecipeSpecsForGraph(entry.recipe(), specs, graphNodeId, ctx, depth, edges, batches, stack,
                graphInputs -> finishTypedStep(entry, ctx, altIds, altModTypes, edges, batches, demandedOutput,
                        graphNodeId, goetyDynamic, plannedSpecs, bladeOutput, graphInputs, stack, done));
    }

    private static void finishTypedStep(RecipeIndex.Entry entry, ResolutionContext ctx,
                                        List<ResourceLocation> altIds, List<String> altModTypes,
                                        CraftingResolver.EdgeTracker edges, int batches, ItemStack demandedOutput,
                                        NodeId graphNodeId, boolean goetyDynamic, List<IngredientSpec> specs,
                                        ItemStack plannedBladeOutput, List<InputDemand> graphInputs,
                                        PlanningStack stack, Consumer<Boolean> done) {
        if (!canFinishStep(graphInputs, ctx, edges, stack, done)) return;

        List<OutputDeclaration> graphOutputs = new ArrayList<>();
        if (entry.recipe() instanceof CraftingRecipe cr) {
            for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(cr)) {
                registerGraphOutput(remainder, remainderBatches(remainder, specs, batches), OutputKind.REMAINDER,
                        graphNodeId, graphOutputs, ctx);
            }
        }

        var handler = ModRecipeHandlers.claimFor(entry.recipe()).winner();
        ItemStack result = goetyDynamic
                ? GoetyDynamicRitualRecipe.validatedOutput(entry.recipe(), demandedOutput)
                : ItemStack.EMPTY;
        if (result.isEmpty() && handler != null) {
            result = ctx.readRecipe(entry.recipe(), () -> handler.getResultItem(entry.recipe(), ctx.level.registryAccess()));
        }
        if (result.isEmpty()) {
            result = ModRecipeHandlers.tryGetResultItem(entry.recipe(), ctx.level.registryAccess());
        }
        if (result.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI] craftBatched: empty result for recipe {} (handler={})",
                    entry.recipe().getId(), handler != null ? handler.getClass().getSimpleName() : "null");
        }
        // Preserve exact hidden outputs, but leave runtime-derived NBT open.
        result = CraftingResolver.resolveDeclaredOutput(entry.recipe(), result);
        if (plannedBladeOutput != null) result = plannedBladeOutput;
        // Smithing recipes copy the base item's NBT at assemble-time. The
        // static result is tagless, so restore the exact demanded variant for
        // graph accounting and for strict-NBT downstream inputs.
        if (demandedOutput != null && !demandedOutput.isEmpty()) {
            result = CandidateEngine.inheritSmithingBaseTag(
                    entry.recipe(), result,
                    CraftingResolver.ingredientOf(demandedOutput, demandedOutput.hasTag()));
        }
        result = MinersDelightCopperPotSupport.adaptResult(entry.modType(), result);
        boolean targeted = handler != null && demandedOutput != null && !demandedOutput.isEmpty()
                && handler.requiresTargetedProduction(entry.recipe(), demandedOutput);
        ItemStack plannedPrimary = targeted ? demandedOutput : result;
        if (targeted) {
            RecipeOutputSpec declaration = handler.getDeclaredOutputs(
                            entry.recipe(), ctx.level.registryAccess()).stream()
                    .filter(output -> output.matchesTarget(
                            ProductionTarget.of(plannedPrimary, 1L).resource()))
                    .findFirst().orElse(null);
            OutputProbability probability = declaration == null
                    ? OutputProbability.dynamic(null, 16, "target:" + entry.recipe().getId())
                    : declaration.probability();
            registerTargetGraphOutput(plannedPrimary, batches, probability,
                    graphNodeId, graphOutputs, ctx);
        } else {
            registerGraphOutput(plannedPrimary, batches, OutputKind.PRIMARY, graphNodeId, graphOutputs, ctx);
        }

        if (handler != null && !targeted) {
            for (ItemStack secondary : handler.getSecondaryOutputs(entry.recipe(), ctx.level.registryAccess())) {
                registerGraphOutput(secondary, batches, OutputKind.SECONDARY,
                        graphNodeId, graphOutputs, ctx);
            }
        } else if (handler == null) {
            for (ItemStack secondary : ModRecipeHandlers.tryGetSecondaryOutputs(entry.recipe(), ctx.level.registryAccess())) {
                registerGraphOutput(secondary, batches, OutputKind.SECONDARY,
                        graphNodeId, graphOutputs, ctx);
            }
        }

        // batches already represents the physical executions needed for the
        // complete resolver demand; callers must not apply repeatCount again.
        CraftingResolver.ResolutionStep step = new CraftingResolver.ResolutionStep(entry.recipe().getId(), entry.modType(),
                entry.recipeTypeId(), altIds, altModTypes, false, batches, null, plannedBladeOutput,
                targeted ? ProductionTarget.of(plannedPrimary, mulCount(plannedPrimary.getCount(), batches)) : null);
        ctx.steps.add(step);
        ctx.addGraphNode(toGraphNode(graphNodeId, step, graphInputs, graphOutputs));

        ctx.commitUndo();
        edges.commitUndo();
        stack.complete(done, true);
    }

    private static boolean canFinishStep(List<InputDemand> inputs, ResolutionContext ctx,
                                         CraftingResolver.EdgeTracker edges,
                                         PlanningStack stack, Consumer<Boolean> done) {
        // 子步骤已经提交到当前候选，回到父层时需要再次检查总步骤预算。
        if (inputs != null && ctx.steps.size() < maxSteps()) return true;
        if (inputs != null) ctx.recordStepGuard(ctx.steps.size(), maxSteps());
        ctx.rollback();
        edges.rollback();
        stack.complete(done, false);
        return false;
    }

    private static void planRecipeSpecsForGraph(
            Recipe<?> recipe, List<IngredientSpec> specs, NodeId nodeId,
            ResolutionContext ctx, int depth,
            CraftingResolver.EdgeTracker edges, int batches,
            PlanningStack stack, Consumer<List<InputDemand>> done) {
        // Machine specs describe physical slots. Keep equal predicates as separate
        // ports: two wool slots may legally choose different colours, while each
        // individual slot must still be backed by one concrete stack variant.
        List<IngredientSpec> physicalSpecs = machineSpecsForGraph(specs);
        // 解析顺序可以打乱，但 inputs 列表必须按端口索引（槽位）排列：
        // 机器委托按槽位逐个校验和摆放材料，列表顺序错位会让工具与原料互换。
        InputDemand[] slotInputs = new InputDemand[physicalSpecs.size()];
        // Resolve consumed inputs before reusable catalysts. A CraftTweaker
        // recipe can use the same catalyst in both a terminal recipe and an
        // intermediate recipe; resolving the catalyst first temporarily
        // removes it from the planning pool and makes the intermediate look
        // unavailable even though its recipe returns the catalyst.
        List<Integer> resolutionOrder = new ArrayList<>(physicalSpecs.size());
        for (int i = 0; i < physicalSpecs.size(); i++) resolutionOrder.add(i);
        resolutionOrder.sort(Comparator
                .comparingInt((Integer i) -> physicalSpecs.get(i).role() == DemandRole.CATALYST ? 1 : 0)
                .thenComparingInt(i -> ingredientBreadthClass(physicalSpecs.get(i).ingredient())));
        var handler = ModRecipeHandlers.claimFor(recipe).winner();
        boolean bladeCrafting = SlashBladeRecipeHandler.isShapedBladeRecipe(recipe);
        List<ScaledIngredientSpec> pendingInputs = new ArrayList<>();
        for (int originalIndex : resolutionOrder) {
            IngredientSpec spec = physicalSpecs.get(originalIndex);
            long quantityLong = spec.consumption().kind()
                    == InputConsumption.Kind.DURABILITY
                    ? 0L
                    : handler != null
                    ? handler.requiredIngredientCount(recipe, spec, originalIndex, batches)
                    : CraftPacketUtils.requiredCount(spec, batches);
            int quantity = SaturatingLongMath.clampToInt(quantityLong);
            pendingInputs.add(ScaledIngredientSpec.of(spec, quantity));
        }
        ctx.pendingInputs.push(pendingInputs);
        stack.enter(ctx.pendingInputs::pop);
        class Slots implements Runnable {
            private int next;

            @Override
            public void run() {
                if (next == resolutionOrder.size()) {
                    stack.leave();
                    stack.complete(done, List.of(slotInputs));
                    return;
                }
                int originalIndex = resolutionOrder.get(next++);
                IngredientSpec spec = physicalSpecs.get(originalIndex);
                long pendingCount = pendingInputs.remove(0).count();
                int quantity = SaturatingLongMath.clampToInt(spec.consumption().kind()
                        == InputConsumption.Kind.DURABILITY
                        ? ctx.requiredDurabilityItems(spec, batches) : pendingCount);
                InputPortId port = new InputPortId(nodeId, originalIndex);
                // 刀的展示栈只代表条件下限，不能把工作台原料锁成整份 NBT 相等。
                Consumer<Ingredient> resolvedSlot = plannedIngredient -> {
                    if (plannedIngredient == null) {
                        stack.leave();
                        stack.complete(done, null);
                        return;
                    }
                    slotInputs[originalIndex] = new InputDemand(port, plannedIngredient, quantity,
                            effectiveConsumption(spec), firstDisplay(plannedIngredient));
                    stack.resume(this);
                };
                if (bladeCrafting) {
                    CraftingResolver.ensureIngredient(spec.ingredient(), quantity, ctx,
                            depth + 1, edges, port, null, stack,
                            resolved -> resolvedSlot.accept(resolved ? spec.ingredient() : null));
                } else {
                    ensureSingleVariantMachineInput(spec.ingredient(), quantity, ctx, depth + 1,
                            edges, port, null, stack, resolvedSlot);
                }
            }
        }
        stack.resume(new Slots());
    }

    /**
     * A non-crafting machine spec represents one physical input slot.  The
     * resolver may satisfy a tag ingredient from several compatible variants,
     * but those variants cannot coexist in one ItemStack.  Lock the complete
     * slot demand to one concrete variant while leaving crafting-grid slots
     * independent (and therefore still mixable across slots).
     */
    @Nullable
    static Ingredient ensureSingleVariantMachineInput(
            Ingredient ingredient, long quantity, ResolutionContext ctx, int depth,
            CraftingResolver.EdgeTracker edges, @Nullable InputPortId port,
            @Nullable List<ResolutionContext.SupplySlice> consumedOut) {
        var stack = new PlanningStack();
        return stack.resolve(done -> ensureSingleVariantMachineInput(
                ingredient, quantity, ctx, depth, edges, port, consumedOut, stack, done));
    }

    static void ensureSingleVariantMachineInput(
            Ingredient ingredient, long quantity, ResolutionContext ctx, int depth,
            CraftingResolver.EdgeTracker edges, @Nullable InputPortId port,
            @Nullable List<ResolutionContext.SupplySlice> consumedOut,
            PlanningStack stack, Consumer<Ingredient> done) {
        // 单件材料不可能混用变体，保留完整谓词，让解析器直接选择可用材料。
        if (quantity <= 1L) {
            CraftingResolver.ensureIngredient(ingredient, quantity, ctx, depth, edges,
                    port, consumedOut, stack, resolved -> stack.complete(done, resolved ? ingredient : null));
            return;
        }
        List<ItemStack> variants = Arrays.stream(ingredient.getItems())
                .filter(item -> item != null && !item.isEmpty())
                .map(item -> item.copyWithCount(1))
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(
                                item -> CraftingResolver.StackKey.of(item, item.hasTag()),
                                item -> item,
                                (first, ignored) -> first,
                                LinkedHashMap::new),
                        map -> new ArrayList<>(map.values())));
        boolean strictNbt = IngredientMatcher.requiresNbt(ingredient);

        // Custom ingredients may not expose concrete item stacks. Preserve the
        // existing behavior because there is no safe variant to lock onto.
        if (variants.size() <= 1) {
            Ingredient selected = variants.isEmpty()
                    ? ingredient
                    : CraftingResolver.ingredientOf(variants.get(0), strictNbt);
            CraftingResolver.ensureIngredient(selected, quantity, ctx, depth, edges, port, consumedOut,
                    stack, resolved -> stack.complete(done, resolved ? selected : null));
            return;
        }

        MaterialVariantPreferences.Snapshot preferences =
                MaterialVariantPreferences.snapshot();
        Map<ItemStack, Long> availableByVariant = new LinkedHashMap<>();
        for (ItemStack variant : variants) {
            availableByVariant.put(variant, ctx.countMatching(
                    CraftingResolver.ingredientOf(variant, strictNbt)));
        }
        // 库存多的变体优先；不足整槽时也先补已有变体的缺口，软偏好仅用于同库存量。
        variants.sort(Comparator.comparingLong((ItemStack item) -> availableByVariant.get(item))
                .reversed()
                .thenComparingInt(item -> preferences.rank(item.getItem()))
                .thenComparing(item -> {
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(item.getItem());
                    return id != null ? id.toString() : "";
                }));

        List<String> variantFailures = new ArrayList<>();
        var candidates = variants.iterator();
        class TryVariant implements Runnable {
            @Override
            public void run() {
                if (!candidates.hasNext()) {
                    recordVariantFailure(ingredient, quantity, variantFailures, ctx);
                    stack.complete(done, null);
                    return;
                }
                tryVariant(candidates.next());
            }

            private void tryVariant(ItemStack variant) {
            // A tagged display candidate does not make a vanilla Ingredient strict.
            // Preserve the source predicate when locking one physical machine slot.
            Ingredient selected = CraftingResolver.ingredientOf(variant, strictNbt);
            List<ResolutionContext.SupplySlice> consumed = new ArrayList<>();
            int variantMissingStart = ctx.missingCount();
            ctx.beginUndo();
            edges.beginUndo();
            CraftingResolver.ensureIngredient(selected, quantity, ctx, depth, edges, port, consumed, stack, resolved -> {
            if (resolved) {
                ctx.commitUndo();
                edges.commitUndo();
                if (consumedOut != null) consumedOut.addAll(consumed);
                stack.complete(done, selected);
                return;
            }
            List<String> details = ctx.missingSince(variantMissingStart);
            ctx.truncateMissing(variantMissingStart);
            ctx.rollback();
            edges.rollback();
            String variantName = variant.getHoverName().getString();
            long available = ctx.countMatching(selected);
            variantFailures.add(variantName + "(" + available + "/" + quantity + ")");
            for (int i = 0; i < details.size() && i < 6; i++) {
                ctx.diag("machine variant " + variantName + " missing: " + details.get(i));
            }
            stack.resume(this);
            });
            }
        }
        stack.resume(new TryVariant());
    }

    private static void recordVariantFailure(Ingredient ingredient, long quantity, List<String> variantFailures,
                                             ResolutionContext ctx) {
        // 机器槽位要求同一个 concrete variant 填满整槽。把每个变体的失败明细汇总成
        // 一条 tag 级缺料，避免把多个变体的深层缺料并集误当成顶层配方材料。
        String tagName = CraftPacketUtils.describeIngredient(ingredient).getString();
        int shown = Math.min(8, variantFailures.size());
        String candidates = String.join("、", variantFailures.subList(0, shown))
                + (variantFailures.size() > shown ? "…" : "");
        String summary = "需要同一种「" + tagName + "」× " + quantity
                + "（候选：" + candidates + "）";
        ctx.addMissing(summary);
    }

    /**
     * Graph input ports represent material demand, not crafting-grid positions.
     * Coalescing equivalent slots lets the resolver batch one producer node instead
     * of creating one identical node per shaped-recipe slot.
     */
    static List<IngredientSpec> coalesceSpecsForGraph(List<IngredientSpec> specs) {
        Map<GraphSpecKey, IngredientSpec> merged = new LinkedHashMap<>();
        int fallbackIndex = 0;
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            GraphSpecKey key;
            try {
                key = new GraphSpecKey(spec.role(), spec.ingredient().getClass().getName(),
                        spec.ingredient().toJson().toString(), -1);
            } catch (Exception ignored) {
                // A broken custom serializer must not cause unrelated predicates to merge.
                key = new GraphSpecKey(spec.role(), "", "", fallbackIndex++);
            }
            merged.compute(key, (ignored, existing) -> {
                if (existing == null) return spec;
                long total = (long) existing.count() + spec.count();
                return new IngredientSpec(existing.ingredient(),
                        total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total,
                        existing.consumption());
            });
        }
        return List.copyOf(merged.values());
    }

    static List<IngredientSpec> machineSpecsForGraph(List<IngredientSpec> specs) {
        return specs.stream().filter(spec -> !spec.isEmpty()).toList();
    }

    static Comparator<IngredientSpec> resolutionComparator() {
        return Comparator
                .comparingInt((IngredientSpec spec) ->
                        spec.role() == DemandRole.CATALYST ? 1 : 0)
                .thenComparingInt(spec -> ingredientBreadthClass(spec.ingredient()));
    }

    private static int ingredientBreadthClass(Ingredient ingredient) {
        try {
            return ingredient.getItems().length == 1 ? 0 : 1;
        } catch (RuntimeException | LinkageError ignored) {
            return 1;
        }
    }

    static List<IngredientSpec> narrowMaterialLocks(ResourceLocation recipeId,
                                                    List<IngredientSpec> specs,
                                                    ResolutionContext ctx) {
        return ctx.lockedSpecs(recipeId, specs);
    }

    private record GraphSpecKey(DemandRole role, String ingredientClass,
                                String serializedIngredient, int fallbackIndex) {}

    private static DemandRole demandRole(Ingredient ingredient) {
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            try {
                if (!stack.getCraftingRemainingItem().isEmpty()) return DemandRole.CONTAINER_RETURNING;
            } catch (Exception ignored) {
                // Broken remainder implementations are treated as consumed.
            }
        }
        return DemandRole.CONSUMED;
    }

    private static DemandRole effectiveDemandRole(IngredientSpec spec) {
        return spec.role() == DemandRole.CONSUMED
                ? demandRole(spec.ingredient()) : spec.role();
    }

    private static InputConsumption effectiveConsumption(IngredientSpec spec) {
        DemandRole role = effectiveDemandRole(spec);
        return role == spec.role()
                ? spec.consumption() : InputConsumption.fromDemandRole(role);
    }

    private static int remainderBatches(ItemStack remainder, List<IngredientSpec> specs, int batches) {
        return CraftPacketUtils.remainderExecutions(remainder, specs, batches);
    }

    private static ItemStack firstDisplay(Ingredient ingredient) {
        ItemStack[] items = ingredient.getItems();
        return items.length == 0 ? ItemStack.EMPTY : items[0].copyWithCount(1);
    }

    private static void registerGraphOutput(ItemStack stack, int batches, OutputKind kind,
                                            NodeId nodeId, List<OutputDeclaration> outputs,
                                            ResolutionContext ctx) {
        if (stack == null || stack.isEmpty() || stack.getCount() <= 0) return;
        ItemStack produced = stack.copyWithCount(mulCount(stack.getCount(), batches));
        OutputPortId port = new OutputPortId(nodeId, outputs.size());
        outputs.add(OutputDeclaration.item(
                port, MaterialKey.of(produced), produced.getCount(), kind));
        ctx.addProduced(produced, new MaterialSource.ProducerOutput(port));
    }

    /** 数量是递归供料目标，不是单次必出承诺；执行时只发布真实产物。 */
    private static void registerTargetGraphOutput(
            ItemStack stack, int batches, OutputProbability probability,
            NodeId nodeId, List<OutputDeclaration> outputs, ResolutionContext ctx) {
        if (stack.isEmpty()) return;
        ItemStack planned = stack.copyWithCount(mulCount(stack.getCount(), batches));
        OutputPortId port = new OutputPortId(nodeId, outputs.size());
        outputs.add(OutputDeclaration.item(port, MaterialKey.of(planned), planned.getCount(),
                OutputKind.PRIMARY, 0L, planned.getCount(), probability, false, null)
                .withTargetProducing(true));
        ctx.addProduced(planned, new MaterialSource.ProducerOutput(port));
    }

    static CraftNode toGraphNode(NodeId nodeId, CraftingResolver.ResolutionStep step,
                                         List<InputDemand> inputs, List<OutputDeclaration> outputs) {
        return new CraftNode(nodeId, step.recipeId(), step.modType().id(), step.recipeTypeId(),
                step.executions(), step.alternativeIds(), step.alternativeModTypes(), step.inferMode(),
                step.syntheticInput(), step.syntheticOutput(), inputs, outputs, step.magicCosts());
    }

}
