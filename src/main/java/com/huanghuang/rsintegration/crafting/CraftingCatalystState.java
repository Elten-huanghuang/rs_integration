package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 按真实配方槽记录最后一次返还状态，跨 tick 保留催化剂变化后的 NBT。 */
final class CraftingCatalystState {
    private final Map<Integer, ItemStack> returns = new HashMap<>();

    void record(List<IngredientSpec> specs, List<ItemStack> remainders) {
        for (int slot = 0; slot < specs.size(); slot++) {
            if (specs.get(slot).role() != DemandRole.CATALYST) continue;
            ItemStack remainder = slot < remainders.size() ? remainders.get(slot) : ItemStack.EMPTY;
            returns.put(slot, remainder.copy());
        }
    }

    boolean tracks(OutputDeclaration output) {
        return output.kind() == OutputKind.CATALYST_RETURN && output.sourceInput() != null
                && returns.containsKey(output.sourceInput().index());
    }

    long returnedQuantity(OutputDeclaration output) {
        ItemStack remainder = returns.get(output.sourceInput().index());
        return remainder == null || remainder.isEmpty() || remainder.getItem() != output.material().item()
                ? 0L : Math.min(output.quantity(), remainder.getCount());
    }

    List<ItemStack> removeReturned(OutputDeclaration output, List<ItemStack> inventory) {
        ItemStack expected = returns.get(output.sourceInput().index());
        long remaining = returnedQuantity(output);
        List<ItemStack> fragments = new ArrayList<>();
        for (ItemStack stack : inventory) {
            if (remaining <= 0L) break;
            if (!ItemStack.isSameItemSameTags(expected, stack)) continue;
            int take = (int) Math.min(remaining, stack.getCount());
            fragments.add(stack.copyWithCount(take));
            stack.shrink(take);
            remaining -= take;
        }
        return List.copyOf(fragments);
    }
}
