package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.compat.ftbquests.ExternalItemProgressBridge;
import com.huanghuang.rsintegration.compat.historystages.HistoryStagesCompat;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.CraftLogContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Executes bounded vanilla/flat recipe slices against a chain-owned inventory. */
final class FlatCraftExecutor {
    interface Host {
        ItemStack reserve(ExtractionLedger ledger, Ingredient ingredient, int amount,
                          ServerPlayer player);

        void onMissing(Ingredient ingredient, ResourceLocation stepId,
                       ExtractionLedger ledger, boolean allowPhysicalFallback);

        void logVirtualInventory(String context);

        void logLedgerState(ExtractionLedger ledger);
    }

    record Context(ServerLevel level, CraftLogContext logContext,
                   List<CraftingResolver.ResolutionStep> chainSteps,
                   @Nullable ItemStack targetOutput, Host host) {}

    private FlatCraftExecutor() {}

    static boolean execute(List<CraftingResolver.ResolutionStep> vanillaSteps,
                           ServerPlayer online, List<ItemStack> workingInventory,
                           ExtractionLedger executionLedger, boolean allowPhysicalFallback,
                           Context context) {
        ServerLevel level = context.level();
        RecipeManager recipeManager = level.getRecipeManager();
        RegistryAccess registryAccess = level.registryAccess();
        CraftLogContext log = context.logContext();
        RSIntegrationMod.LOGGER.debug(log.format(
                "executeVanillaStepsInline: {} vanilla steps"), vanillaSteps.size());
        context.host().logVirtualInventory("before batch");

        for (CraftingResolver.ResolutionStep step : vanillaSteps) {
            ResourceLocation stepId = step.recipeId();
            int executions = step.executions();
            Recipe<?> recipe = recipeManager.byKey(stepId).orElse(null);
            if (recipe == null) {
                RSIntegrationMod.LOGGER.debug(log.format("  step {} not found in recipe manager"), stepId);
                continue;
            }
            if (HistoryStagesCompat.isRecipeLocked(recipe, online)) {
                online.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_stage_missing"));
                RSIntegrationMod.LOGGER.debug(log.format("  step {} blocked by History Stages"), stepId);
                return false;
            }
            RSIntegrationMod.LOGGER.debug(log.format("  processing step: {} x{}"), stepId, executions);

            if (recipe instanceof CraftingRecipe craftingRecipe) {
                List<IngredientSpec> specs = CraftPacketUtils.extractCraftingIngredientSpecs(craftingRecipe);
                ItemStack terminalOutput = terminalOutputFor(stepId, craftingRecipe, registryAccess, context);
                if (!terminalOutput.isEmpty()
                        && !SelfAmplifyingRecipePolicy.isSelfAmplifying(specs, terminalOutput)) {
                    specs = specs.stream().map(spec -> SelfAmplifyingRecipePolicy
                            .excludeNonProductiveSelfCandidate(spec, terminalOutput)).toList();
                }
                for (int execution = 0; execution < executions; execution++) {
                    if (!executeCraftingOnce(craftingRecipe, specs, stepId, online,
                            workingInventory, executionLedger, allowPhysicalFallback,
                            registryAccess, context)) return false;
                }
                continue;
            }

            List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
            if (recipe instanceof net.minecraft.world.item.crafting.SmithingTransformRecipe smithing) {
                specs = SmithingRecipeHandler.requireDemandedOutputTag(
                        smithing, specs, step.syntheticOutput());
            }
            if (specs == null || specs.isEmpty()) continue;

            List<ItemStack> consumedInputs = new ArrayList<>();
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                int stillNeeded = CraftPacketUtils.requiredCount(spec, executions);
                boolean captured = false;
                var iterator = workingInventory.iterator();
                while (iterator.hasNext() && stillNeeded > 0) {
                    ItemStack available = iterator.next();
                    if (!IngredientMatcher.test(spec.ingredient(), available)) continue;
                    int take = Math.min(stillNeeded, available.getCount());
                    if (!captured) consumedInputs.add(available.copyWithCount(1));
                    captured = true;
                    available.shrink(take);
                    stillNeeded -= take;
                    if (available.isEmpty()) iterator.remove();
                }
                if (stillNeeded <= 0) continue;

                ItemStack reserved = ItemStack.EMPTY;
                if (allowPhysicalFallback) {
                    reserved = context.host().reserve(executionLedger,
                            spec.ingredient(), stillNeeded, online);
                    if (reserved.isEmpty()) {
                        reserved = executionLedger.reserveFromInventory(
                                spec.ingredient(), stillNeeded, online);
                    }
                }
                if (reserved.isEmpty()) {
                    context.host().onMissing(spec.ingredient(), stepId,
                            executionLedger, allowPhysicalFallback);
                    return false;
                }
                if (!captured) consumedInputs.add(reserved.copyWithCount(1));
            }

            ItemStack result = recipe instanceof net.minecraft.world.item.crafting.SmithingTransformRecipe smithing
                    ? SmithingRecipeHandler.assembleTransform(smithing, consumedInputs, registryAccess)
                    : ModRecipeHandlers.tryGetResultItem(recipe, registryAccess);
            if (!result.isEmpty()) {
                ItemStack produced = result.copyWithCount(
                        StepExecutor.mulCount(result.getCount(), executions));
                addToInventory(workingInventory, produced);
                ExternalItemProgressBridge.enqueueCrafted(online, produced);
            }
            for (ItemStack secondary : ModRecipeHandlers.tryGetSecondaryOutputs(recipe, registryAccess)) {
                addToInventory(workingInventory,
                        secondary.copyWithCount(StepExecutor.mulCount(
                                secondary.getCount(), executions)));
            }
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                for (ItemStack stack : spec.ingredient().getItems()) {
                    if (stack.isEmpty()) continue;
                    try {
                        ItemStack remainder = stack.getCraftingRemainingItem();
                        if (!remainder.isEmpty()) {
                            addToInventory(workingInventory, remainder.copyWithCount(
                                    CraftPacketUtils.requiredCount(spec, executions)));
                            break;
                        }
                    } catch (Exception exception) {
                        RSIntegrationMod.LOGGER.debug(log.format(
                                "getCraftingRemainingItem failed"), exception);
                    }
                }
            }
        }
        return true;
    }

    private static ItemStack terminalOutputFor(ResourceLocation stepId,
                                               CraftingRecipe recipe,
                                               RegistryAccess registryAccess,
                                               Context context) {
        List<CraftingResolver.ResolutionStep> chainSteps = context.chainSteps();
        if (chainSteps.isEmpty()
                || !chainSteps.get(chainSteps.size() - 1).recipeId().equals(stepId)) {
            return ItemStack.EMPTY;
        }
        ItemStack target = context.targetOutput();
        if (target != null && !target.isEmpty()) return target.copyWithCount(1);
        ItemStack declared = ModRecipeHandlers.tryGetResultItem(recipe, registryAccess);
        return declared.isEmpty() ? ItemStack.EMPTY : declared.copyWithCount(1);
    }

    private static boolean executeCraftingOnce(CraftingRecipe recipe,
                                               List<IngredientSpec> specs,
                                               ResourceLocation stepId,
                                               ServerPlayer online,
                                               List<ItemStack> workingInventory,
                                               ExtractionLedger executionLedger,
                                               boolean allowPhysicalFallback,
                                               RegistryAccess registryAccess,
                                               Context context) {
        Map<Integer, ItemStack> modifiedSlots = new HashMap<>();
        ItemStack[] consumed = new ItemStack[Math.min(specs.size(), 9)];
        for (int ingIdx = 0; ingIdx < specs.size(); ingIdx++) {
            IngredientSpec spec = specs.get(ingIdx);
            if (spec.isEmpty()) continue;
            Ingredient ingredient = spec.ingredient();
            int stillNeeded = CraftPacketUtils.requiredCount(spec, 1);
            boolean captured = false;
            for (int i = 0; i < workingInventory.size() && stillNeeded > 0; i++) {
                ItemStack available = workingInventory.get(i);
                if (available.isEmpty() || !IngredientMatcher.test(ingredient, available)) continue;
                modifiedSlots.putIfAbsent(i, available.copy());
                if (!captured && ingIdx < consumed.length) {
                    consumed[ingIdx] = available.copyWithCount(1);
                    captured = true;
                }
                int take = Math.min(stillNeeded, available.getCount());
                available.shrink(take);
                stillNeeded -= take;
            }
            if (stillNeeded <= 0) continue;

            ItemStack reserved = ItemStack.EMPTY;
            if (allowPhysicalFallback) {
                reserved = context.host().reserve(executionLedger, ingredient, stillNeeded, online);
                if (reserved.isEmpty()) {
                    reserved = executionLedger.reserveFromInventory(ingredient, stillNeeded, online);
                }
            }
            if (reserved.isEmpty()) {
                modifiedSlots.forEach((index, original) -> {
                    if (index < workingInventory.size()) workingInventory.set(index, original);
                    else workingInventory.add(original);
                });
                context.host().onMissing(ingredient, stepId, executionLedger, allowPhysicalFallback);
                return false;
            }
            if (!captured && ingIdx < consumed.length) {
                consumed[ingIdx] = reserved.copyWithCount(1);
            }
        }

        ItemStack result = CraftPacketUtils.assembleCraftingOutput(recipe, consumed, online);
        if (result.isEmpty()) result = ModRecipeHandlers.tryGetResultItem(recipe, registryAccess);
        if (!result.isEmpty()) {
            addToInventory(workingInventory, result);
            ExternalItemProgressBridge.enqueueCrafted(online, result);
        }
        for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(recipe, consumed)) {
            int remainderExecutions = CraftPacketUtils.remainderExecutions(remainder, specs, 1);
            addToInventory(workingInventory, remainder.copyWithCount(
                    StepExecutor.mulCount(remainder.getCount(), remainderExecutions)));
        }
        return true;
    }

    private static void addToInventory(List<ItemStack> inventory, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        for (ItemStack existing : inventory) {
            if (ItemStack.isSameItemSameTags(existing, stack)) {
                existing.grow(stack.getCount());
                return;
            }
        }
        inventory.add(stack.copy());
    }
}
