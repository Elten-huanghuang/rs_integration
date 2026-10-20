package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.compat.ftbquests.ExternalItemProgressBridge;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
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
        if (context.chainSteps().stream().anyMatch(value -> value.modType() != ModType.GENERIC)) {
            return Outcome.NOT_APPLICABLE;
        }
        if (ledger.hasPreferredResonanceReservations()) return Outcome.NOT_APPLICABLE;
        BatchPlan plan = analyze(level, step, context.targetOutput(), context.materialLocks());
        if (plan == null) return Outcome.NOT_APPLICABLE;

        long requested = Math.max(1, step.executions());
        long availableBatch = maximumAvailableCrafts(
                plan, workingInventory, ledger, player);
        if (availableBatch < requested) {
            return Outcome.FAILURE;
        }

        long maxChunk = requested;
        long outputPerCraft = Math.max(1, plan.output().getCount());
        maxChunk = Math.min(maxChunk, Integer.MAX_VALUE / outputPerCraft);
        long totalInputPerCraft = 0L;
        for (SlotRequirement slot : plan.slots()) {
            if (slot.perCraft() > Integer.MAX_VALUE) return Outcome.NOT_APPLICABLE;
            maxChunk = Math.min(maxChunk, Integer.MAX_VALUE / slot.perCraft());
            totalInputPerCraft = saturatedAdd(totalInputPerCraft, slot.perCraft());
        }
        maxChunk = Math.min(maxChunk, Integer.MAX_VALUE / totalInputPerCraft);
        if (maxChunk < 1) return Outcome.NOT_APPLICABLE;

        List<ItemStack> inventoryBefore = copyInventory(workingInventory);
        long remainingBatch = requested;
        while (remainingBatch > 0) {
            int chunk = (int) Math.min(remainingBatch, maxChunk);
            List<Requirement> requirements = resolveChunkRequirements(
                    plan, chunk, workingInventory, ledger, player);
            if (requirements == null) {
                restoreInventory(workingInventory, inventoryBefore);
                return Outcome.FAILURE;
            }
            int reservationMark = ledger.reservationMark();
            if (!executeChunk(plan, requirements, chunk, player, workingInventory, ledger)) {
                ledger.cancelReservationsSince(reservationMark);
                restoreInventory(workingInventory, inventoryBefore);
                return Outcome.FAILURE;
            }
            remainingBatch -= chunk;
        }
        context.host().logVirtualInventory("after stable batch");
        return Outcome.SUCCESS;
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
            slots.add(new SlotRequirement(List.copyOf(candidates), perCraft));
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
            for (ItemStack candidate : slot.candidates()) {
                if (remaining <= 0) break;
                long available = countInWorkingInventory(workingInventory, candidate)
                        + ledger.countAvailableExactForBatch(candidate, player);
                CraftingResolver.StackKey key = CraftingResolver.StackKey.of(candidate, true);
                long alreadyAssigned = assigned.getOrDefault(key, 0L);
                long take = Math.min(remaining, Math.max(0L, available - alreadyAssigned));
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

    private static long maximumAvailableCrafts(
            BatchPlan plan, List<ItemStack> workingInventory,
            ExtractionLedger ledger, ServerPlayer player) {
        long available = Long.MAX_VALUE;
        for (SlotRequirement slot : plan.slots()) {
            long slotAvailable = 0L;
            Map<CraftingResolver.StackKey, ItemStack> unique = new LinkedHashMap<>();
            for (ItemStack candidate : slot.candidates()) {
                unique.putIfAbsent(CraftingResolver.StackKey.of(candidate, true), candidate);
            }
            for (ItemStack candidate : unique.values()) {
                slotAvailable = saturatedAdd(slotAvailable,
                        countInWorkingInventory(workingInventory, candidate)
                                + ledger.countAvailableExactForBatch(candidate, player));
            }
            available = Math.min(available, slotAvailable / slot.perCraft());
        }
        return available == Long.MAX_VALUE ? 0L : available;
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
    private record SlotRequirement(List<ItemStack> candidates, long perCraft) {}
    private record BatchPlan(ItemStack output, List<SlotRequirement> slots) {}
}
