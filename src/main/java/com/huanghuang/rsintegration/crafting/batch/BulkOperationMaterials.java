package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import com.huanghuang.rsintegration.crafting.StackCopies;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 同质普通消费输入的紧凑执行计划。每个槽位保存一份精确模板和来源，
 * 只在机器领取一批操作时生成物料；催化剂、变体混用等情况仍走原执行路径。
 */
public final class BulkOperationMaterials {
    private record Slot(ItemStack template, boolean producer) {}

    private final int operations;
    private final List<Slot> slots;
    private final List<ItemStack> initialRemainder;
    private final List<ItemStack> producerRemainder;

    private BulkOperationMaterials(int operations, List<Slot> slots,
                                   List<ItemStack> initial, List<ItemStack> producer) {
        this.operations = operations;
        this.slots = List.copyOf(slots);
        this.initialRemainder = StackCopies.excludingEmpty(initial);
        this.producerRemainder = StackCopies.excludingEmpty(producer);
    }

    /** 预检只改副本；失败后原池和物理库存均保持不变。 */
    @Nullable
    public static BulkOperationMaterials tryCreate(List<IngredientSpec> specs, int operations,
                                                   List<ItemStack> initial, List<ItemStack> producer) {
        if (operations <= 0 || specs.isEmpty()) return null;
        List<ItemStack> initialPool = new ArrayList<>(StackCopies.excludingEmpty(initial));
        List<ItemStack> producerPool = new ArrayList<>(StackCopies.excludingEmpty(producer));
        List<Slot> slots = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) {
                slots.add(new Slot(ItemStack.EMPTY, false));
                continue;
            }
            if (spec.consumption().kind() != InputConsumption.Kind.CONSUMED) return null;
            boolean fromProducer = producerPool.stream()
                    .anyMatch(stack -> !stack.isEmpty() && IngredientMatcher.test(spec.ingredient(), stack));
            List<ItemStack> pool = fromProducer ? producerPool : initialPool;
            ItemStack template = pool.stream()
                    .filter(stack -> !stack.isEmpty() && IngredientMatcher.test(spec.ingredient(), stack))
                    .findFirst().orElse(ItemStack.EMPTY).copy();
            if (template.isEmpty()) return null;
            // 同一材料若跨来源或跨 NBT 变体，保留逐操作的精确分配契约。
            List<ItemStack> other = fromProducer ? initial : producer;
            if (other.stream().anyMatch(stack -> !stack.isEmpty()
                    && MaterialMatcher.sameRuntimeFragment(template, stack))) return null;
            long remaining = (long) spec.count() * operations;
            for (ItemStack stack : pool) {
                if (remaining == 0L) break;
                if (!MaterialMatcher.sameRuntimeFragment(template, stack)) continue;
                int take = (int) Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
            if (remaining != 0L) return null;
            // 使用扣料前复制的模板，池元素扣空后也不会丢失物品与 NBT 身份。
            slots.add(new Slot(template.copyWithCount(spec.count()), fromProducer));
        }
        return new BulkOperationMaterials(operations, slots, initialPool, producerPool);
    }

    public int operations() { return operations; }
    public int stride() { return slots.size(); }

    /** 按真实槽位契约验证，不能依赖 Ingredient 对象是否是同一个实例。 */
    public boolean matches(List<IngredientSpec> specs) {
        if (specs == null || specs.size() != slots.size()) return false;
        for (int slot = 0; slot < specs.size(); slot++) {
            IngredientSpec spec = specs.get(slot);
            ItemStack stack = slots.get(slot).template();
            if (spec.isEmpty()) {
                if (!stack.isEmpty()) return false;
            } else if (spec.consumption().kind() != InputConsumption.Kind.CONSUMED
                    || stack.getCount() != spec.count() || !IngredientMatcher.test(spec.ingredient(), stack)) {
                return false;
            }
        }
        return true;
    }

    public int maxBatchSize() {
        int max = operations;
        for (Slot slot : slots) if (!slot.template().isEmpty()) {
            max = Math.min(max, Integer.MAX_VALUE / slot.template().getCount());
        }
        return max;
    }

    public List<ItemStack> batchMaterials(int first, int count) {
        checkRange(first, count);
        if (count > maxBatchSize()) throw new IllegalArgumentException("batch exceeds ItemStack count range");
        List<ItemStack> result = new ArrayList<>(slots.size());
        for (Slot slot : slots) result.add(slot.template().isEmpty() ? ItemStack.EMPTY
                : slot.template().copyWithCount(Math.multiplyExact(slot.template().getCount(), count)));
        return result;
    }

    public List<ItemStack> initialMaterials(int first, int count) { return materials(first, count, false); }
    public List<ItemStack> producerMaterials(int first, int count) { return materials(first, count, true); }
    public List<ItemStack> initialRemainder() { return StackCopies.excludingEmpty(initialRemainder); }
    public List<ItemStack> producerRemainder() { return StackCopies.excludingEmpty(producerRemainder); }

    private List<ItemStack> materials(int first, int count, boolean producer) {
        checkRange(first, count);
        List<ItemStack> result = new ArrayList<>();
        for (Slot slot : slots) {
            if (slot.producer() != producer || slot.template().isEmpty()) continue;
            long remaining = (long) slot.template().getCount() * count;
            while (remaining > 0L) {
                int take = (int) Math.min(remaining, Integer.MAX_VALUE);
                result.add(slot.template().copyWithCount(take));
                remaining -= take;
            }
        }
        return result;
    }

    private void checkRange(int first, int count) {
        if (count < 0) throw new IllegalArgumentException("negative count");
        Objects.checkFromIndexSize(first, count, operations);
    }
}
