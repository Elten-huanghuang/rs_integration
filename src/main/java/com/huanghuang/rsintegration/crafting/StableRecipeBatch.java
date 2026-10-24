package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.feature.ftbquests.ExternalItemProgressBridge;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仅处理可证明稳定的原版工作台配方。
 *
 * <p>KubeJS、事件型配方、动态余料、催化剂和任何非精确单物品输入
 * 都不会进入该路径，而是继续使用逐次执行。</p>
 */
final class StableRecipeBatch {
    enum Outcome {
        NOT_APPLICABLE,
        SUCCESS,
        DEFERRED,
        FAILURE
    }

    private StableRecipeBatch() {}

    static boolean isEligible(ServerLevel level, CraftingResolver.ResolutionStep step,
                              @Nullable ItemStack targetOutput,
                              Map<String, ItemStack> materialLocks) {
        return analyze(level, step, targetOutput, materialLocks) != null;
    }

    static Outcome tryExecute(ServerLevel level, CraftingResolver.ResolutionStep step,
                              ServerPlayer player, List<ItemStack> workingInventory,
                              ExtractionLedger ledger, FlatCraftExecutor.Context context) {
        return tryExecuteDetailed(level, step, player, workingInventory, ledger, context).outcome();
    }

    static Result tryExecuteDetailed(
            ServerLevel level, CraftingResolver.ResolutionStep step,
            ServerPlayer player, List<ItemStack> workingInventory,
            ExtractionLedger ledger, FlatCraftExecutor.Context context) {
        if (context.chainSteps().stream().anyMatch(value -> value.modType() != ModType.GENERIC)) {
            return new Result(Outcome.NOT_APPLICABLE, "mixed execution chain");
        }
        if (ledger.hasPreferredResonanceReservations()) {
            return new Result(Outcome.NOT_APPLICABLE, "resonance reservation preferred");
        }
        BatchPlan plan = analyze(level, step, context.targetOutput(), context.materialLocks());
        if (plan == null) return new Result(Outcome.NOT_APPLICABLE, "recipe is not stable batchable");

        long requested = Math.max(1, step.executions());
        Availability availability = maximumAvailableCrafts(
                plan, requested, workingInventory, ledger, player);
        if (!availability.snapshotReadable()) {
            logBatch(context, step, "deferred", requested, availability);
            return new Result(Outcome.DEFERRED,
                    "storage snapshot unavailable (" + availability.detail() + ")");
        }
        if (availability.availableCrafts() < requested) {
            logBatch(context, step, "insufficient", requested, availability);
            String detail = "requested=" + requested
                    + " available=" + availability.availableCrafts();
            return new Result(Outcome.DEFERRED, detail);
        }

        long maxChunk = requested;
        long outputPerCraft = Math.max(1, plan.output().getCount());
        maxChunk = Math.min(maxChunk, Integer.MAX_VALUE / outputPerCraft);
        long totalInputPerCraft = 0L;
        for (SlotRequirement slot : plan.slots()) {
            if (slot.perCraft() > Integer.MAX_VALUE) {
                return new Result(Outcome.NOT_APPLICABLE, "input count exceeds chunk limit");
            }
            maxChunk = Math.min(maxChunk, Integer.MAX_VALUE / slot.perCraft());
            totalInputPerCraft = saturatedAdd(totalInputPerCraft, slot.perCraft());
        }
        maxChunk = Math.min(maxChunk, Integer.MAX_VALUE / totalInputPerCraft);
        if (maxChunk < 1) return new Result(Outcome.NOT_APPLICABLE, "batch chunk is empty");

        List<ItemStack> inventoryBefore = copyInventory(workingInventory);
        long remainingBatch = requested;
        while (remainingBatch > 0) {
            int chunk = (int) Math.min(remainingBatch, maxChunk);
            List<Requirement> requirements = resolveChunkRequirements(
                    plan, chunk, workingInventory, ledger, player);
            if (requirements == null) {
                restoreInventory(workingInventory, inventoryBefore);
                return new Result(Outcome.FAILURE, "stable stock changed during chunk planning");
            }
            int reservationMark = ledger.reservationMark();
            if (!executeChunk(plan, requirements, chunk, player, workingInventory, ledger)) {
                ledger.cancelReservationsSince(reservationMark);
                restoreInventory(workingInventory, inventoryBefore);
                logBatch(context, step, "reservation changed", requested, availability);
                return new Result(Outcome.DEFERRED,
                        "exact reservation changed during chunk execution");
            }
            remainingBatch -= chunk;
        }
        context.host().logVirtualInventory("after stable batch");
        return new Result(Outcome.SUCCESS, "completed=" + requested);
    }

    private static boolean executeChunk(BatchPlan plan, List<Requirement> requirements,
                                        int crafts, ServerPlayer player,
                                        List<ItemStack> workingInventory,
                                        ExtractionLedger ledger) {
        for (Requirement requirement : requirements) {
            long needed = requirement.count();
            long fromVirtual = consumeFromWorkingInventory(
                    workingInventory, requirement.template(), needed);
            long remaining = needed - fromVirtual;
            if (remaining <= 0) continue;

            int amount = (int) Math.min(Integer.MAX_VALUE, remaining);
            var endpoint = ledger.storageEndpoint();
            ItemStack reserved = ledger.reserveExactAcrossNetworkAndInventory(
                    requirement.template(), amount, endpoint, player);
            if (reserved.isEmpty() || reserved.getCount() != amount) {
                return false;
            }
        }

        long producedCount = (long) plan.output().getCount() * crafts;
        if (producedCount > Integer.MAX_VALUE) return false;
        ItemStack produced = plan.output().copyWithCount((int) producedCount);
        addToWorkingInventory(workingInventory, produced);
        ExternalItemProgressBridge.enqueueCrafted(player, produced);
        return true;
    }

    @Nullable
    private static BatchPlan analyze(ServerLevel level, CraftingResolver.ResolutionStep step,
                                     @Nullable ItemStack targetOutput,
                                     Map<String, ItemStack> materialLocks) {
        if (step.modType() != ModType.GENERIC || !materialLocks.isEmpty()) return null;
        Recipe<?> recipe = level.getRecipeManager().byKey(step.recipeId()).orElse(null);
        if (recipe == null) return null;
        boolean explicitContract = recipe instanceof StableBatchRecipe;
        boolean trustedCrafting = recipe instanceof CraftingRecipe
                && (recipe.getClass() == ShapedRecipe.class
                || recipe.getClass() == ShapelessRecipe.class
                || explicitContract);
        boolean trustedInstantRecipe = recipe instanceof StonecutterRecipe || explicitContract;
        if (!trustedCrafting && !trustedInstantRecipe) return null;

        List<IngredientSpec> specs = recipe instanceof CraftingRecipe craftingRecipe
                ? CraftPacketUtils.extractCraftingIngredientSpecs(craftingRecipe)
                : CraftPacketUtils.extractIngredientSpecs(recipe);
        if (specs == null || specs.isEmpty()) return null;
        List<ItemStack> samples = new ArrayList<>(specs.size());
        List<SlotRequirement> slots = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) {
                samples.add(ItemStack.EMPTY);
                continue;
            }
            if (spec.role() != DemandRole.CONSUMED) return null;
            ItemStack[] options = spec.ingredient().getItems();
            if (options.length == 0) return null;
            List<ItemStack> candidates = new ArrayList<>(options.length);
            for (ItemStack option : options) {
                if (option.isEmpty()) continue;
                ItemStack candidate = option.copyWithCount(1);
                if (!candidate.getCraftingRemainingItem().isEmpty()) return null;
                candidates.add(candidate);
            }
            if (candidates.isEmpty()) return null;
            ItemStack sample = candidates.get(0);
            samples.add(sample);
            int perCraft = CraftPacketUtils.requiredCount(spec, 1);
            if (perCraft <= 0) return null;
            slots.add(new SlotRequirement(spec, perCraft));
        }
        if (slots.isEmpty()) return null;

        ItemStack output;
        if (recipe instanceof CraftingRecipe craftingRecipe) {
            ItemStack[] sampleGrid = new ItemStack[Math.min(samples.size(), 9)];
            for (int index = 0; index < sampleGrid.length; index++) {
                sampleGrid[index] = samples.get(index);
            }
            for (ItemStack remainder : CraftPacketUtils.getRecipeRemaindersBySpec(
                    craftingRecipe, sampleGrid)) {
                if (!remainder.isEmpty()) return null;
            }
            output = CraftPacketUtils.assembleCraftingOutput(
                    craftingRecipe, sampleGrid, level.registryAccess());
        } else {
            output = ModRecipeHandlers.tryGetResultItem(recipe, level.registryAccess());
        }
        if (output.isEmpty()) return null;
        // 递归链的前置步骤会产出低阶物品，不能拿最终点击的 9x 产物拒绝它们。
        // 目标只用于约束同一种物品之间的 NBT 变体选择。
        if (targetOutput != null && !targetOutput.isEmpty()
                && ItemStack.isSameItem(output, targetOutput)
                && !ItemStack.isSameItemSameTags(
                output.copyWithCount(1), targetOutput.copyWithCount(1))) {
            return null;
        }
        // Keep the recipe's real yield. Batch execution multiplies this count
        // by the number of crafts; reducing it to one silently deletes surplus
        // such as the remaining three planks from a log-to-planks recipe.
        return new BatchPlan(output.copy(),
                List.copyOf(slots));
    }

    @Nullable
    private static List<Requirement> resolveChunkRequirements(
            BatchPlan plan, long crafts, List<ItemStack> workingInventory,
            ExtractionLedger ledger, ServerPlayer player) {
        LinkedHashMap<CraftingResolver.StackKey, Requirement> selected = new LinkedHashMap<>();
        Map<CraftingResolver.StackKey, Long> assigned = new LinkedHashMap<>();
        for (SlotRequirement slot : plan.slots()) {
            long remaining = slot.perCraft() * crafts;
            Map<CraftingResolver.StackKey, ItemStack> available =
                    matchingAvailable(slot.spec().ingredient(), workingInventory, ledger, player);
            for (Map.Entry<CraftingResolver.StackKey, ItemStack> entry : available.entrySet()) {
                if (remaining <= 0) break;
                CraftingResolver.StackKey key = entry.getKey();
                ItemStack candidate = entry.getValue();
                long existing = available.getOrDefault(key, ItemStack.EMPTY).getCount();
                long alreadyAssigned = assigned.getOrDefault(key, 0L);
                long take = Math.min(remaining, Math.max(0L, existing - alreadyAssigned));
                if (take <= 0) continue;
                assigned.merge(key, take, StableRecipeBatch::saturatedAdd);
                Requirement previous = selected.get(key);
                selected.put(key, previous == null
                        ? new Requirement(candidate, take)
                    : new Requirement(candidate, saturatedAdd(previous.count(), take)));
                remaining -= take;
            }
            if (remaining > 0) return null;
        }
        return List.copyOf(selected.values());
    }

    private static Availability maximumAvailableCrafts(
            BatchPlan plan, long requested, List<ItemStack> workingInventory,
            ExtractionLedger ledger, ServerPlayer player) {
        long upperBound = Long.MAX_VALUE;
        for (SlotRequirement slot : plan.slots()) {
            long slotAvailable = 0L;
            ExtractionLedger.BatchMatch backing =
                    ledger.inspectBatchMatching(slot.spec().ingredient(), player);
            if (!backing.snapshotReadable()) {
                return new Availability(0L, false, backing.detail());
            }
            for (ItemStack candidate : matchingWorkingStacks(
                    slot.spec().ingredient(), workingInventory)) {
                slotAvailable = saturatedAdd(slotAvailable, candidate.getCount());
            }
            for (ItemStack candidate : backing.stacks()) {
                slotAvailable = saturatedAdd(slotAvailable, candidate.getCount());
            }
            upperBound = Math.min(upperBound, slotAvailable / slot.perCraft());
        }
        long requestedCrafts = Math.max(1L, requested);
        long bounded = upperBound == Long.MAX_VALUE
                ? requestedCrafts : Math.min(upperBound, requestedCrafts);
        if (bounded < requestedCrafts) {
            return new Availability(bounded, true, "slot upper bound");
        }
        // 同一物品可能出现在多个输入槽。各槽独立看都有库存并不代表总量足够，
        // 因此用真实批量分配再确认一次，避免把硬缺料误报成执行期库存变化。
        long available = maximumAllocatableCrafts(
                plan, requestedCrafts, workingInventory, ledger, player);
        return new Availability(available, true, "allocation aware");
    }

    private static long maximumAllocatableCrafts(
            BatchPlan plan, long requested, List<ItemStack> workingInventory,
            ExtractionLedger ledger, ServerPlayer player) {
        if (resolveChunkRequirements(
                plan, requested, workingInventory, ledger, player) != null) {
            return requested;
        }
        long low = 0L;
        long high = requested - 1L;
        while (low < high) {
            long middle = low + (high - low + 1L) / 2L;
            if (resolveChunkRequirements(
                    plan, middle, workingInventory, ledger, player) != null) {
                low = middle;
            } else {
                high = middle - 1L;
            }
        }
        return low;
    }

    private static Map<CraftingResolver.StackKey, ItemStack> matchingAvailable(
            Ingredient ingredient, List<ItemStack> workingInventory,
            ExtractionLedger ledger, ServerPlayer player) {
        Map<CraftingResolver.StackKey, ItemStack> available = new LinkedHashMap<>();
        for (ItemStack stack : matchingWorkingStacks(ingredient, workingInventory)) {
            mergeAvailable(available, stack);
        }
        ExtractionLedger.BatchMatch stored = ledger.inspectBatchMatching(ingredient, player);
        for (ItemStack stack : stored.stacks()) mergeAvailable(available, stack);
        return available;
    }

    private static List<ItemStack> matchingWorkingStacks(
            Ingredient ingredient, List<ItemStack> inventory) {
        Map<CraftingResolver.StackKey, ItemStack> matches = new LinkedHashMap<>();
        for (ItemStack stack : inventory) {
            if (stack == null || stack.isEmpty() || !IngredientMatcher.test(ingredient, stack)) continue;
            mergeAvailable(matches, stack);
        }
        return List.copyOf(matches.values());
    }

    private static void mergeAvailable(Map<CraftingResolver.StackKey, ItemStack> available,
                                       ItemStack addition) {
        CraftingResolver.StackKey key = CraftingResolver.StackKey.of(addition, true);
        ItemStack existing = available.get(key);
        if (existing == null) {
            available.put(key, addition.copyWithCount(addition.getCount()));
            return;
        }
        long combined = (long) existing.getCount() + addition.getCount();
        existing.setCount((int) Math.min(Integer.MAX_VALUE, combined));
    }

    private static void logBatch(FlatCraftExecutor.Context context,
                                 CraftingResolver.ResolutionStep step,
                                 String outcome, long requested,
                                 Availability availability) {
        String message = context.logContext().format(
                "Stable batch {} for {}: requested={} availableCrafts={} snapshot={}");
        RSIntegrationMod.LOGGER.debug(message, outcome, step.recipeId(), requested,
                availability.availableCrafts(), availability.detail());
    }

    private static long countInWorkingInventory(List<ItemStack> inventory, ItemStack template) {
        CraftingResolver.StackKey key = CraftingResolver.StackKey.of(template, true);
        long total = 0L;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && CraftingResolver.StackKey.of(stack, true).equals(key)) {
                total = saturatedAdd(total, stack.getCount());
            }
        }
        return total;
    }

    private static long consumeFromWorkingInventory(List<ItemStack> inventory,
                                                   ItemStack template, long needed) {
        CraftingResolver.StackKey key = CraftingResolver.StackKey.of(template, true);
        long consumed = 0L;
        var iterator = inventory.iterator();
        while (iterator.hasNext() && consumed < needed) {
            ItemStack stack = iterator.next();
            if (stack.isEmpty() || !CraftingResolver.StackKey.of(stack, true).equals(key)) continue;
            int take = (int) Math.min(needed - consumed, stack.getCount());
            stack.shrink(take);
            consumed += take;
            if (stack.isEmpty()) iterator.remove();
        }
        return consumed;
    }

    private static void addToWorkingInventory(List<ItemStack> inventory, ItemStack addition) {
        if (addition.isEmpty()) return;
        for (ItemStack existing : inventory) {
            if (!ItemStack.isSameItemSameTags(existing, addition)) continue;
            long combined = (long) existing.getCount() + addition.getCount();
            if (combined <= Integer.MAX_VALUE) {
                existing.setCount((int) combined);
                return;
            }
        }
        inventory.add(addition.copy());
    }

    private static List<ItemStack> copyInventory(List<ItemStack> inventory) {
        List<ItemStack> copy = new ArrayList<>(inventory.size());
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) copy.add(stack.copy());
        }
        return copy;
    }

    private static void restoreInventory(List<ItemStack> target, List<ItemStack> snapshot) {
        target.clear();
        for (ItemStack stack : snapshot) target.add(stack.copy());
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private record Requirement(ItemStack template, long count) {}
    private record SlotRequirement(IngredientSpec spec, long perCraft) {}
    private record BatchPlan(ItemStack output, List<SlotRequirement> slots) {}
    private record Availability(long availableCrafts, boolean snapshotReadable,
                                String detail) {}
    record Result(Outcome outcome, String detail) {
        Result {
            detail = detail == null ? "" : detail;
        }
    }
}
