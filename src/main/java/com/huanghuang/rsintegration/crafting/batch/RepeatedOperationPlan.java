package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 各行共享同一声明，列按条目顺序编码，与机器槽号无关。 */
public final class RepeatedOperationPlan {
    private final MaterialPlan materialPlan;
    private final int operations;
    private final List<ItemStack> flatMaterials;

    public RepeatedOperationPlan(MaterialPlan materialPlan, int operations, List<ItemStack> flatMaterials) {
        int width = materialPlan.entries().size();
        if (operations <= 0 || width == 0 || (long) operations * width != flatMaterials.size()) {
            throw new IllegalArgumentException("invalid operation matrix");
        }
        List<ItemStack> copy = new ArrayList<>(flatMaterials.size());
        for (int i = 0; i < flatMaterials.size(); i++) {
            var entry = materialPlan.entries().get(i % width);
            ItemStack stack = flatMaterials.get(i);
            if (stack == null || stack.getCount() != entry.spec().count()
                    || !IngredientMatcher.test(entry.spec().ingredient(), stack)) {
                throw new IllegalArgumentException("invalid material for " + entry.id());
            }
            copy.add(stack.copy());
        }
        this.materialPlan = materialPlan;
        this.operations = operations;
        this.flatMaterials = List.copyOf(copy);
    }

    public MaterialPlan materialPlan() { return materialPlan; }
    public int operations() { return operations; }

    public ItemStack materialAt(int operation, int column) {
        int width = materialPlan.entries().size();
        return flatMaterials.get(Objects.checkIndex(operation, operations) * width
                + Objects.checkIndex(column, width)).copy();
    }
}
