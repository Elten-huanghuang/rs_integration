package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按真实配方槽记录最后一次返还状态，跨 tick 保留催化剂变化后的 NBT。 */
final class CraftingCatalystState {
    private final List<InputDemand> graphInputs;
    private final Map<Integer, List<Integer>> slotsByInput = new HashMap<>();
    private final Map<Integer, ItemStack> initialInputs = new HashMap<>();
    private final Map<Integer, ItemStack> returns = new HashMap<>();
    private boolean bound;

    CraftingCatalystState() {
        this(List.of());
    }

    CraftingCatalystState(List<InputDemand> graphInputs) {
        this.graphInputs = List.copyOf(graphInputs);
    }

    void record(List<IngredientSpec> specs, ItemStack[] consumed, List<ItemStack> remainders) {
        if (!bound) bindSlots(specs, consumed);
        for (int slot = 0; slot < specs.size(); slot++) {
            if (specs.get(slot).role() != DemandRole.CATALYST) continue;
            ItemStack remainder = slot < remainders.size() ? remainders.get(slot) : ItemStack.EMPTY;
            returns.put(slot, remainder.copy());
        }
    }

    /** 图端口可能合并重复原料并跳过空槽，不能直接当作工作台槽位使用。 */
    private void bindSlots(List<IngredientSpec> specs, ItemStack[] consumed) {
        Map<Integer, Long> assigned = new HashMap<>();
        for (int slot = 0; slot < specs.size(); slot++) {
            IngredientSpec spec = specs.get(slot);
            if (spec.role() != DemandRole.CATALYST || slot >= consumed.length
                    || consumed[slot] == null || consumed[slot].isEmpty()) continue;
            initialInputs.put(slot, consumed[slot].copy());
            if (graphInputs.isEmpty()) {
                slotsByInput.put(slot, List.of(slot));
            }
        }
        Set<Integer> mappedSlots = new HashSet<>();
        // 先匹配所有仍保持原状态的投入，避免已变动的图腾抢占另一件图腾的精确端口。
        for (boolean requireExact : List.of(true, false)) {
            for (int slot = 0; slot < specs.size(); slot++) {
                ItemStack actual = initialInputs.get(slot);
                if (actual == null || mappedSlots.contains(slot)) continue;
                IngredientSpec spec = specs.get(slot);
                for (InputDemand input : graphInputs) {
                    int inputIndex = input.id().index();
                    if (input.role() != DemandRole.CATALYST
                            || input.quantity() - assigned.getOrDefault(inputIndex, 0L) < spec.count()
                            || input.displayHint().getItem() != actual.getItem()
                            || !IngredientMatcher.test(spec.ingredient(), input.displayHint())
                            || (requireExact && !IngredientMatcher.test(input.ingredient(), actual))) continue;
                    slotsByInput.computeIfAbsent(inputIndex, ignored -> new ArrayList<>()).add(slot);
                    assigned.merge(inputIndex, (long) spec.count(), Long::sum);
                    mappedSlots.add(slot);
                    break;
                }
            }
        }
        bound = true;
    }

    boolean tracks(OutputDeclaration output) {
        return output.kind() == OutputKind.CATALYST_RETURN && output.sourceInput() != null
                && slotsByInput.containsKey(output.sourceInput().index());
    }

    private List<Integer> returnSlots(OutputDeclaration output) {
        List<Integer> slots = slotsByInput.getOrDefault(output.sourceInput().index(), List.of());
        List<Integer> exact = slots.stream()
                .filter(slot -> MaterialMatcher.matchesExact(output.material(), initialInputs.get(slot)))
                .toList();
        // 后续节点借用的催化剂可能已改变状态，图中仍保留最初的材料身份。
        return exact.isEmpty() ? slots : exact;
    }

    long returnedQuantity(OutputDeclaration output) {
        long quantity = 0L;
        for (int slot : returnSlots(output)) {
            ItemStack remainder = returns.get(slot);
            if (remainder != null && !remainder.isEmpty() && remainder.getItem() == output.material().item()) {
                quantity += remainder.getCount();
            }
        }
        return Math.min(output.quantity(), quantity);
    }

    List<ItemStack> removeReturned(OutputDeclaration output, List<ItemStack> inventory) {
        long remaining = returnedQuantity(output);
        List<ItemStack> fragments = new ArrayList<>();
        for (int slot : returnSlots(output)) {
            ItemStack expected = returns.get(slot);
            if (expected == null || expected.isEmpty() || expected.getItem() != output.material().item()) continue;
            int slotRemaining = expected.getCount();
            for (ItemStack stack : inventory) {
                if (remaining <= 0L || slotRemaining <= 0) break;
                if (!ItemStack.isSameItemSameTags(expected, stack)) continue;
                int take = (int) Math.min(remaining, Math.min(slotRemaining, stack.getCount()));
                fragments.add(stack.copyWithCount(take));
                stack.shrink(take);
                remaining -= take;
                slotRemaining -= take;
            }
        }
        return List.copyOf(fragments);
    }
}
