package com.huanghuang.rsintegration.benchmark;

import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.huanghuang.rsintegration.disk.rs.IndexedStackList;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.api.util.IStackList;
import com.refinedmods.refinedstorage.apiimpl.util.ItemStackList;
import com.refinedmods.refinedstorage.apiimpl.util.FluidStackList;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/** 批量成对操作保持库存恒定，避免高速基准在测量中把库存耗尽或填满。 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms1g", "-Xmx3g"})
public class UnifiedDiskWorkloadBenchmark {
    @State(Scope.Thread)
    public static class BatchData extends UnifiedDiskBenchmark.Data {
        @Param({"64"}) public int batchAmount;
        ItemStack missingItem;
        FluidStack missingFluid;
        @Setup(Level.Trial) public void missing() {
            missingItem = UnifiedDiskBenchmark.item(entries + 1, "variants", payloadBytes, types);
            missingFluid = UnifiedDiskBenchmark.fluid(entries + 1, "variants", payloadBytes);
            UnifiedDiskBenchmark.check(initialAmount >= batchAmount + 1);
        }
    }

    private int insert(BatchData data, int i, boolean perform) {
        if (data.core != null) return data.kind.equals("item")
                ? data.core.insertItem(data.itemQueries[i], data.batchAmount, perform)
                : data.core.insertFluid(data.fluidQueries[i], data.batchAmount, perform);
        Action action = perform ? Action.PERFORM : Action.SIMULATE;
        return data.kind.equals("item") ? data.batchAmount - data.rsItems.insert(data.itemQueries[i], data.batchAmount, action).getCount()
                : data.batchAmount - data.rsFluids.insert(data.fluidQueries[i], data.batchAmount, action).getAmount();
    }

    private int extract(BatchData data, int i, boolean perform, Blackhole sink) {
        if (data.core != null) {
            FrozenKey.Kind kind = data.keys[i].kind();
            int slot = data.kind.equals("item") ? data.core.items.exactSlot(data.itemQueries[i]) : data.core.fluids.exactSlot(data.fluidQueries[i]);
            FrozenKey key = data.core.table(kind).key(slot);
            int taken = data.core.extract(kind, slot, data.batchAmount, perform);
            // 消费真实提取副本，避免把没有构造返回 Stack 的核心耗时放进公平比较。
            Object result = kind == FrozenKey.Kind.ITEM ? key.itemStack(taken) : key.fluidStack(taken);
            sink.consume(result); return taken;
        }
        Action action = perform ? Action.PERFORM : Action.SIMULATE;
        Object result = data.kind.equals("item") ? data.rsItems.extract(data.itemQueries[i], data.batchAmount, IComparer.COMPARE_NBT, action)
                : data.rsFluids.extract(data.fluidQueries[i], data.batchAmount, IComparer.COMPARE_NBT, action);
        sink.consume(result);
        return result instanceof ItemStack stack ? stack.getCount() : ((FluidStack) result).getAmount();
    }

    @Benchmark public int transferPair(BatchData data, Blackhole sink) {
        int i = data.next(); return extract(data, i, true, sink) + insert(data, i, true);
    }
    @Benchmark public int simulatedInsert(BatchData data) { return insert(data, data.next(), false); }
    @Benchmark public int simulatedExtract(BatchData data, Blackhole sink) { return extract(data, data.next(), false, sink); }
    @Benchmark public Object missingExtract(BatchData data) {
        if (data.core != null) return data.kind.equals("item") ? data.core.items.exactSlot(data.missingItem) : data.core.fluids.exactSlot(data.missingFluid);
        return data.kind.equals("item") ? data.rsItems.extract(data.missingItem, data.batchAmount, IComparer.COMPARE_NBT, Action.PERFORM)
                : data.rsFluids.extract(data.missingFluid, data.batchAmount, IComparer.COMPARE_NBT, Action.PERFORM);
    }
    @Benchmark public int rejectedNewKey(BatchData data) {
        if (data.core == null) throw new IllegalStateException("原版无限盘没有对应的 Key 容量拒绝语义");
        return data.kind.equals("item") ? data.core.insertItem(data.missingItem, data.batchAmount, true)
                : data.core.insertFluid(data.missingFluid, data.batchAmount, true);
    }

    @State(Scope.Thread)
    public static class CacheData extends UnifiedDiskBenchmark.Data {
        IStackList<ItemStack> items;
        IStackList<FluidStack> fluids;
        @Setup(Level.Trial) public void cache() {
            items = core != null ? new IndexedStackList<>(FrozenKey.Kind.ITEM) : new ItemStackList();
            fluids = core != null ? new IndexedStackList<>(FrozenKey.Kind.FLUID) : new FluidStackList();
            for (int i = 0; i < entries; i++) {
                if (kind.equals("item")) items.add(UnifiedDiskBenchmark.item(i, profile, payloadBytes, types), 1000);
                else fluids.add(UnifiedDiskBenchmark.fluid(i, profile, payloadBytes), 1000);
            }
        }
        @Override @TearDown(Level.Iteration) public void validate() {
            super.validate();
            UnifiedDiskBenchmark.check((kind.equals("item") ? items.size() : fluids.size()) == entries);
            for (int i = 0; i < keys.length; i++) UnifiedDiskBenchmark.check((kind.equals("item")
                    ? items.getCount(itemQueries[i]) : fluids.getCount(fluidQueries[i])) == 1000);
        }
    }
    @Benchmark public int cacheUpdatePair(CacheData data) {
        int i = data.next();
        if (data.kind.equals("item")) {
            itemsRemoveAdd(data.items, data.itemQueries[i]); return data.items.getCount(data.itemQueries[i]);
        }
        data.fluids.remove(data.fluidQueries[i], 1); data.fluids.add(data.fluidQueries[i], 1);
        return data.fluids.getCount(data.fluidQueries[i]);
    }
    private void itemsRemoveAdd(IStackList<ItemStack> list, ItemStack stack) { list.remove(stack, 1); list.add(stack, 1); }
}
