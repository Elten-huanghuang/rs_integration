package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import com.huanghuang.rsintegration.crafting.StackCopies;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.consumption.DurabilityMath;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 订单材料按槽位、精确变体和来源保存连续数量段，不创建“次数 × 槽位”矩阵。
 * 来源边界允许落在一次操作中间，但一次物理槽位始终使用相同的物品及 NBT。
 * 催化剂和耐久工具按实际 worker 保存，不能用全局操作编号轮转工具。
 */
public final class CountedOperationMaterials {
    public enum Origin { INITIAL, VIRTUAL, PRODUCER }

    private static final class Lot {
        final ItemStack template;
        final Origin origin;
        long quantity;

        Lot(ItemStack template, Origin origin, long quantity) {
            this.template = template.copyWithCount(1);
            this.origin = origin;
            this.quantity = quantity;
        }
    }

    /** 可被多个槽位共用；调用方只传入快照，不修改外部物品栈。 */
    public static final class Pool {
        private final List<Lot> lots = new ArrayList<>();
        private final Map<StackKey, List<Lot>> identities = new LinkedHashMap<>();

        public Pool add(Origin origin, List<ItemStack> stacks) {
            for (ItemStack stack : stacks) add(origin, stack, stack.getCount());
            return this;
        }

        public Pool add(Origin origin, ItemStack template, long quantity) {
            if (template == null || template.isEmpty() || quantity <= 0L) return this;
            List<Lot> sameIdentity = identities.computeIfAbsent(StackKey.of(template, true), ignored -> new ArrayList<>());
            for (Lot lot : sameIdentity) {
                if (lot.origin == origin) {
                    lot.quantity = Math.addExact(lot.quantity, quantity);
                    return this;
                }
            }
            Lot lot = new Lot(template, origin, quantity);
            lots.add(lot);
            sameIdentity.add(lot);
            return this;
        }

        public List<ItemStack> remaining(Origin origin) {
            List<ItemStack> result = new ArrayList<>();
            for (Lot lot : lots) if (lot.origin == origin) append(result, lot.template, lot.quantity);
            return result;
        }

        /** 从补充材料快照扣除图已占用的初始材料，避免同一现货被承诺两次。 */
        public void subtract(Origin origin, List<ItemStack> stacks) {
            for (ItemStack stack : stacks) {
                long remaining = stack.getCount();
                for (Lot lot : identityLots(stack)) {
                    if (lot.origin != origin) continue;
                    long take = Math.min(remaining, lot.quantity);
                    lot.quantity -= take;
                    remaining -= take;
                }
            }
        }

        private List<Run> take(IngredientSpec spec, int copies) {
            List<Run> result = new ArrayList<>();
            int first = 0;
            while (first < copies) {
                Lot selected = null;
                long available = 0L;
                for (Lot candidate : lots) {
                    if (candidate.quantity <= 0L || !IngredientMatcher.test(spec.ingredient(), candidate.template)) continue;
                    long total = 0L;
                    for (Lot lot : identityLots(candidate.template)) {
                        total = Math.addExact(total, lot.quantity);
                    }
                    if (total >= spec.count()) { selected = candidate; available = total; break; }
                }
                if (selected == null) throw new IllegalArgumentException("insufficient exact material for slot");
                int count = (int) Math.min(copies - first, available / spec.count());
                long needed = Math.multiplyExact((long) spec.count(), count);
                long offset = 0L;
                List<SourceRange> sources = new ArrayList<>();
                for (Lot lot : identityLots(selected.template)) {
                    if (needed == 0L) break;
                    long take = Math.min(needed, lot.quantity);
                    if (take <= 0L) continue;
                    sources.add(new SourceRange(offset, offset + take, lot.origin));
                    lot.quantity -= take;
                    needed -= take;
                    offset += take;
                }
                result.add(new Run(first, first + count, selected.template.copyWithCount(spec.count()), List.copyOf(sources)));
                first += count;
            }
            return List.copyOf(result);
        }

        private List<Run> takeTool(IngredientSpec spec, int executions, int worker) {
            List<Run> selected = new ArrayList<>();
            long remaining = executions;
            for (Lot candidate : lots) {
                if (candidate.quantity <= 0L || !IngredientMatcher.test(spec.ingredient(), candidate.template)) continue;
                ItemStack tool = candidate.template;
                long uses = tool.isDamageableItem()
                        ? DurabilityMath.usesForRemainingDurability(
                                tool.getMaxDamage() - tool.getDamageValue(), spec.consumption().unitsPerOperation())
                        : Long.MAX_VALUE;
                if (uses <= 0L) continue;
                long required = remaining / uses + (remaining % uses == 0L ? 0 : 1);
                long available = 0L;
                for (Lot lot : identityLots(tool)) {
                    available = Math.addExact(available, lot.quantity);
                }
                required = Math.min(required, Math.min(available, Integer.MAX_VALUE));
                if (required == 0L) continue;
                IngredientSpec exact = new IngredientSpec(StrictNBTIngredient.of(tool),
                        (int) required, spec.consumption());
                Run run = take(exact, 1).get(0);
                selected.add(new Run(worker, worker + 1, run.template, run.sources));
                long covered = uses > Long.MAX_VALUE / required ? Long.MAX_VALUE : uses * required;
                remaining = Math.max(0L, remaining - covered);
                if (remaining == 0L) return List.copyOf(selected);
            }
            throw new IllegalArgumentException("insufficient tool durability for worker");
        }

        private List<Lot> identityLots(ItemStack template) {
            return identities.getOrDefault(StackKey.of(template, true), List.of());
        }

        private void link(Pool pool) {
            for (Lot lot : pool.lots) {
                lots.add(lot);
                identities.computeIfAbsent(StackKey.of(lot.template, true), ignored -> new ArrayList<>()).add(lot);
            }
        }
    }

    private record SourceRange(long first, long end, Origin origin) {}
    private record Run(int first, int end, ItemStack template, List<SourceRange> sources) {}
    private record Slot(boolean reusable, List<Run> runs) {}

    public static final class Builder {
        private final int operations;
        private final int workers;
        private final List<Slot> slots = new ArrayList<>();

        public Builder(int operations, int workers) {
            if (operations <= 0 || workers <= 0 || workers > operations) throw new IllegalArgumentException("invalid execution counts");
            this.operations = operations;
            this.workers = workers;
        }

        public Builder add(IngredientSpec spec, Pool pool) {
            if (spec.isEmpty()) slots.add(new Slot(false, List.of()));
            else if (spec.consumption().kind() == InputConsumption.Kind.DURABILITY) addTools(spec, pool, pool);
            else {
                boolean reusable = spec.consumption().reusableWithinWorkerBatch();
                slots.add(new Slot(reusable, pool.take(spec, reusable ? workers : operations)));
            }
            return this;
        }

        /** 图只为第一台机器声明工具，其余机器的工具来自可用库存。 */
        public Builder addGraphReusable(IngredientSpec spec, Pool graph, Pool extra) {
            if (spec.consumption().kind() == InputConsumption.Kind.DURABILITY) {
                addTools(spec, graph, extra);
                return this;
            }
            List<Run> runs = new ArrayList<>(graph.take(spec, 1));
            if (workers > 1) for (Run run : extra.take(spec, workers - 1)) {
                runs.add(new Run(run.first + 1, run.end + 1, run.template, run.sources));
            }
            slots.add(new Slot(true, List.copyOf(runs)));
            return this;
        }

        private void addTools(IngredientSpec spec, Pool first, Pool extra) {
            List<Run> runs = new ArrayList<>();
            Pool tools = first;
            if (first != extra) {
                tools = new Pool();
                tools.link(first);
                tools.link(extra);
            }
            for (int worker = 0; worker < workers; worker++) {
                int share = operations / workers + (worker < operations % workers ? 1 : 0);
                runs.addAll(tools.takeTool(spec, share, worker));
            }
            slots.add(new Slot(true, List.copyOf(runs)));
        }

        public CountedOperationMaterials build() { return new CountedOperationMaterials(operations, workers, slots); }
    }

    private final int operations;
    private final int workers;
    private final List<Slot> slots;

    private CountedOperationMaterials(int operations, int workers, List<Slot> slots) {
        this.operations = operations;
        this.workers = workers;
        this.slots = List.copyOf(slots);
    }

    public int operations() { return operations; }
    public int stride() { return slots.size(); }
    public int segmentCount() { return slots.stream().mapToInt(slot -> slot.runs.size()).sum(); }

    public boolean matches(List<IngredientSpec> specs) {
        if (specs == null || specs.size() != slots.size()) return false;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            IngredientSpec spec = specs.get(i);
            if (slot.runs.isEmpty() != spec.isEmpty()) return false;
            if (!spec.isEmpty() && slot.reusable != spec.consumption().reusableWithinWorkerBatch()) return false;
            for (Run run : slot.runs) if (spec.consumption().kind() != InputConsumption.Kind.DURABILITY
                    && run.template.getCount() != spec.count()
                    || !IngredientMatcher.test(spec.ingredient(), run.template)) return false;
        }
        return true;
    }

    public int compatibleBatchSize(int first, int requested) {
        Objects.checkIndex(first, operations);
        int count = Math.min(requested, operations - first);
        if (count <= 0) throw new IllegalArgumentException("empty batch");
        for (Slot slot : slots) {
            if (slot.reusable || slot.runs.isEmpty()) continue;
            Run run = at(slot, first);
            count = Math.min(count, Math.min(run.end - first, Integer.MAX_VALUE / run.template.getCount()));
        }
        return count;
    }

    public List<ItemStack> batchMaterials(int worker, int first, int count) {
        Objects.checkIndex(worker, workers);
        Objects.checkFromIndexSize(first, count, operations);
        if (count <= 0 || compatibleBatchSize(first, count) != count) throw new IllegalArgumentException("incompatible batch");
        List<ItemStack> result = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            if (slot.runs.isEmpty()) { result.add(ItemStack.EMPTY); continue; }
            Run run = at(slot, slot.reusable ? worker : first);
            result.add(run.template.copyWithCount(Math.multiplyExact(run.template.getCount(), slot.reusable ? 1 : count)));
        }
        return result;
    }

    public List<ItemStack> materials(Origin origin, int first, int count) {
        Objects.checkFromIndexSize(first, count, operations);
        return materials(origin, first, count, false);
    }

    public List<ItemStack> reusableMaterials(Origin origin, int worker) {
        Objects.checkIndex(worker, workers);
        return materials(origin, worker, 1, true);
    }

    /** 仅为需要多个精确工具变体的 worker 生成交接列表。 */
    public Map<Integer, List<ItemStack>> mixedReusableStock(int worker) {
        Objects.checkIndex(worker, workers);
        Map<Integer, List<ItemStack>> result = new LinkedHashMap<>();
        for (int index = 0; index < slots.size(); index++) {
            Slot slot = slots.get(index);
            if (!slot.reusable) continue;
            List<ItemStack> stock = new ArrayList<>();
            for (Run run : slot.runs) if (worker >= run.first && worker < run.end) stock.add(run.template.copy());
            if (stock.size() > 1) result.put(index, List.copyOf(stock));
        }
        return Map.copyOf(result);
    }

    private List<ItemStack> materials(Origin origin, int first, int count, boolean reusable) {
        List<ItemStack> result = new ArrayList<>();
        for (Slot slot : slots) {
            if (slot.reusable != reusable) continue;
            for (Run run : slot.runs) {
                int start = Math.max(first, run.first);
                int end = Math.min(first + count, run.end);
                if (start >= end) continue;
                long startItem = (long) (start - run.first) * run.template.getCount();
                long endItem = (long) (end - run.first) * run.template.getCount();
                for (SourceRange source : run.sources) if (source.origin == origin) {
                    append(result, run.template, Math.max(0L, Math.min(endItem, source.end) - Math.max(startItem, source.first)));
                }
            }
        }
        return StackCopies.excludingEmpty(result);
    }

    private static Run at(Slot slot, int index) {
        int low = 0, high = slot.runs.size() - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (index < slot.runs.get(middle).end) high = middle;
            else low = middle + 1;
        }
        Run run = slot.runs.get(low);
        if (index < run.first || index >= run.end) throw new IllegalArgumentException("operation outside slot");
        return run;
    }

    private static void append(List<ItemStack> result, ItemStack template, long quantity) {
        while (quantity > 0L) {
            int take = (int) Math.min(Integer.MAX_VALUE, quantity);
            result.add(template.copyWithCount(take));
            quantity -= take;
        }
    }
}
