package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.recipe.CrockPotRecipeHandler;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
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
import com.huanghuang.rsintegration.command.PerformanceMonitor;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotBatchDelegate;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes recipe steps: material planning and result registration.
 * Delegates sub-ingredient resolution back to {@link CraftingResolver#ensureIngredient}.
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

    static boolean craftBatched(CraftingRecipe recipe, ResolutionContext ctx, int depth,
                                List<ResourceLocation> altIds, List<String> altModTypes,
                                CraftingResolver.EdgeTracker edges, int batches) {
        if (ctx.timedOut()) {
            PerformanceMonitor.recordResolveTimeout();
            return false;
        }
        if (depth > maxDepth()) {
            RSIntegrationMod.LOGGER.debug("[RSI-Step] resolution guard DEPTH depth={} maxDepth={}", depth, maxDepth());
            return false;
        }
        if (ctx.steps.size() + 1 > maxSteps()) {
            RSIntegrationMod.LOGGER.debug("[RSI-Step] resolution guard STEPS steps={} maxSteps={}", ctx.steps.size(), maxSteps());
            return false;
        }

        ctx.beginUndo();
        edges.beginUndo();
        NodeId graphNodeId = ctx.allocateNodeId();

        List<IngredientSpec> specs = CraftPacketUtils.extractCraftingIngredientSpecs(recipe);
        List<InputDemand> graphInputs = planRecipeSpecsForGraph(
                specs, graphNodeId, ctx, depth, edges, batches);
        if (graphInputs == null) {
            ctx.rollback();
            edges.rollback();
            return false;
        }

        List<OutputDeclaration> graphOutputs = new ArrayList<>();
        for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(recipe)) {
            registerGraphOutput(remainder, remainderBatches(remainder, specs, batches), OutputKind.REMAINDER,
                    graphNodeId, graphOutputs, ctx);
        }

        ItemStack result = ModRecipeHandlers.tryGetResultItem(recipe, ctx.level.registryAccess());
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
        return true;
    }

    static boolean craftBatched(RecipeIndex.Entry entry, ResolutionContext ctx, int depth,
                                List<ResourceLocation> altIds, List<String> altModTypes,
                                CraftingResolver.EdgeTracker edges, int batches) {
        if (ctx.timedOut()) {
            PerformanceMonitor.recordResolveTimeout();
            return false;
        }
        if (depth > maxDepth()) {
            RSIntegrationMod.LOGGER.debug("[RSI-Step] resolution guard DEPTH depth={} maxDepth={}", depth, maxDepth());
            return false;
        }
        if (ctx.steps.size() + 1 > maxSteps()) {
            RSIntegrationMod.LOGGER.debug("[RSI-Step] resolution guard STEPS steps={} maxSteps={}", ctx.steps.size(), maxSteps());
            return false;
        }

        if (entry.modType() == ModType.GENERIC && entry.recipe() instanceof CraftingRecipe cr) {
            return craftBatched(cr, ctx, depth, altIds, altModTypes, edges, batches);
        }

        ctx.beginUndo();
        edges.beginUndo();
        NodeId graphNodeId = ctx.allocateNodeId();

        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());
        if (entry.modType() == ModType.byId("crockpot")
                && CrockPotRecipeHandler.hasCategoryConstraints(entry.recipe())) {
            List<IngredientSpec> categorySpecs = CrockPotBatchDelegate.buildCategoryPlanIngredients(
                    entry.recipe(), ctx.network, ctx.level, null);
            if (categorySpecs != null && !categorySpecs.isEmpty()) specs = categorySpecs;
        }

        // If the standard ingredient path returned garbage (AIR, bare items
        // without NBT from TACZ/Applied Armorer), repair via reflection.
        boolean broken = specs == null || specs.isEmpty();
        if (!broken) {
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                for (ItemStack stack : spec.ingredient().getItems()) {
                    if (stack.isEmpty()) continue;
                    if (stack.getItem() == net.minecraft.world.item.Items.AIR) { broken = true; break; }
                    ResourceLocation rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
                    if (rl != null && !stack.hasTag()) {
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
                for (ItemStack stack : repaired) {
                    specs.add(new IngredientSpec(
                            CraftingResolver.ingredientOf(stack, stack.hasTag()), stack.getCount()));
                }
            }
        }

        if (specs == null || specs.isEmpty()) {
            ctx.rollback();
            edges.rollback();
            return false;
        }

        List<InputDemand> graphInputs = planRecipeSpecsForGraph(
                specs, graphNodeId, ctx, depth, edges, batches);
        if (graphInputs == null) {
            ctx.rollback();
            edges.rollback();
            return false;
        }

        List<OutputDeclaration> graphOutputs = new ArrayList<>();
        if (entry.recipe() instanceof CraftingRecipe cr) {
            for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(cr)) {
                registerGraphOutput(remainder, remainderBatches(remainder, specs, batches), OutputKind.REMAINDER,
                        graphNodeId, graphOutputs, ctx);
            }
        }

        var handler = ModRecipeHandlers.handlerFor(entry.recipe());
        ItemStack result = ItemStack.EMPTY;
        if (handler != null) {
            result = handler.getResultItem(entry.recipe(), ctx.level.registryAccess());
        }
        if (result.isEmpty()) {
            result = ModRecipeHandlers.tryGetResultItem(entry.recipe(), ctx.level.registryAccess());
        }
        if (result.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI] craftBatched: empty result for recipe {} (handler={})",
                    entry.recipe().getId(), handler != null ? handler.getClass().getSimpleName() : "null");
        }
        // If the result is bare (no NBT), scan fields for the real
        // NBT-carrying output hidden by the mod author.
        if (!result.isEmpty() && !result.hasTag()) {
            ItemStack hidden = CraftingResolver.extractHiddenOutput(entry.recipe());
            if (!hidden.isEmpty()) {
                result = hidden;
            }
        }
        registerGraphOutput(result, batches, OutputKind.PRIMARY, graphNodeId, graphOutputs, ctx);

        if (handler != null) {
            for (ItemStack secondary : handler.getSecondaryOutputs(entry.recipe(), ctx.level.registryAccess())) {
                registerGraphOutput(secondary, batches, OutputKind.SECONDARY,
                        graphNodeId, graphOutputs, ctx);
            }
        } else {
            for (ItemStack secondary : ModRecipeHandlers.tryGetSecondaryOutputs(entry.recipe(), ctx.level.registryAccess())) {
                registerGraphOutput(secondary, batches, OutputKind.SECONDARY,
                        graphNodeId, graphOutputs, ctx);
            }
        }

        // batches already represents the physical executions needed for the
        // complete resolver demand; callers must not apply repeatCount again.
        CraftingResolver.ResolutionStep step = new CraftingResolver.ResolutionStep(entry.recipe().getId(), entry.modType(),
                entry.recipeTypeId(), altIds, altModTypes, false, batches);
        ctx.steps.add(step);
        ctx.addGraphNode(toGraphNode(graphNodeId, step, graphInputs, graphOutputs));

        ctx.commitUndo();
        edges.commitUndo();
        return true;
    }

    private static List<InputDemand> planRecipeIngredientsForGraph(
            List<Ingredient> ingredients, NodeId nodeId, ResolutionContext ctx, int depth,
            CraftingResolver.EdgeTracker edges, int batches) {
        if (depth > maxDepth() || ctx.steps.size() + 1 > maxSteps()) return null;
        List<InputDemand> inputs = new ArrayList<>();
        int index = 0;
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            int quantity = mulCount(1, batches);
            InputPortId port = new InputPortId(nodeId, index++);
            if (!CraftingResolver.ensureIngredient(ingredient, quantity, ctx, depth + 1, edges, port, null)) {
                return null;
            }
            inputs.add(new InputDemand(port, ingredient, quantity, demandRole(ingredient), firstDisplay(ingredient)));
        }
        return inputs;
    }

    private static List<InputDemand> planRecipeSpecsForGraph(
            List<IngredientSpec> specs, NodeId nodeId, ResolutionContext ctx, int depth,
            CraftingResolver.EdgeTracker edges, int batches) {
        if (depth > maxDepth() || ctx.steps.size() + 1 > maxSteps()) return null;
        List<InputDemand> inputs = new ArrayList<>();
        int index = 0;
        for (IngredientSpec spec : coalesceSpecsForGraph(specs)) {
            if (spec.isEmpty()) continue;
            int quantity = CraftPacketUtils.requiredCount(spec, batches);
            InputPortId port = new InputPortId(nodeId, index++);
            if (!CraftingResolver.ensureIngredient(spec.ingredient(), quantity,
                    ctx, depth + 1, edges, port, null)) {
                return null;
            }
            inputs.add(new InputDemand(port, spec.ingredient(), quantity,
                    effectiveDemandRole(spec), firstDisplay(spec.ingredient())));
        }
        return inputs;
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
                        existing.role());
            });
        }
        return List.copyOf(merged.values());
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
        outputs.add(new OutputDeclaration(port, MaterialKey.of(produced), produced.getCount(), kind));
        ctx.addProduced(produced, new MaterialSource.ProducerOutput(port));
    }

    private static CraftNode toGraphNode(NodeId nodeId, CraftingResolver.ResolutionStep step,
                                         List<InputDemand> inputs, List<OutputDeclaration> outputs) {
        return new CraftNode(nodeId, step.recipeId(), step.modType().id(), step.recipeTypeId(),
                step.executions(), step.alternativeIds(), step.alternativeModTypes(), step.inferMode(),
                step.syntheticInput(), step.syntheticOutput(), inputs, outputs);
    }

}
