package com.huanghuang.rsintegration.benchmark;

import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.huanghuang.rsintegration.disk.core.UnifiedDiskCore;
import com.refinedmods.refinedstorage.api.storage.AccessType;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.storage.disk.ItemStorageDisk;
import com.refinedmods.refinedstorage.apiimpl.storage.disk.FluidStorageDisk;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 单线程盘级比较；不把世界、租约、网络缓存和文件写入冒充为已测路径。 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms1g", "-Xmx3g"})
public class UnifiedDiskBenchmark {
    static final int BASE_AMOUNT = 500_000_000;

    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (API.instance().getComparer() == null) throw new IllegalStateException("RS 比较器未初始化");
    }

    static UnifiedDiskCore core() {
        return new UnifiedDiskCore(UUID.randomUUID(), UUID.randomUUID(), null,
                new UnifiedDiskCore.Limits(262144, 262144, 1048576, 268435456));
    }

    static ItemStack item(int id, String profile, int payloadBytes, List<Item> types) {
        Item type = profile.equals("mixed") || profile.equals("materials") ? types.get(id % types.size()) : Items.STONE;
        ItemStack stack = new ItemStack(type);
        if (!profile.equals("plain") && !profile.equals("materials")) stack.getOrCreateTag().putInt("variant", id);
        if (payloadBytes > 0) stack.getOrCreateTag().putByteArray("payload", new byte[payloadBytes]);
        return stack;
    }

    static FluidStack fluid(int id, String profile, int payloadBytes) {
        FluidStack stack = new FluidStack(Fluids.WATER, 1);
        if (!profile.equals("plain")) stack.getOrCreateTag().putInt("variant", id);
        if (payloadBytes > 0) stack.getOrCreateTag().putByteArray("payload", new byte[payloadBytes]);
        return stack;
    }

    @State(Scope.Thread)
    public static class Data {
        @Param({"unifiedKeyPath", "rs"}) public String implementation;
        @Param({"item"}) public String kind;
        @Param({"variants"}) public String profile;
        @Param({"1000", "10000", "50000", "262144"}) public int entries;
        @Param({"0"}) public int payloadBytes;
        @Param({"hot256"}) public String access;
        @Param({"500000000"}) public int initialAmount;
        UnifiedDiskCore core;
        ItemStorageDisk rsItems;
        FluidStorageDisk rsFluids;
        ItemStack[] itemQueries = new ItemStack[256];
        FluidStack[] fluidQueries = new FluidStack[256];
        FrozenKey[] keys = new FrozenKey[256];
        ItemStack[] rsItemTemplates = new ItemStack[256];
        FluidStack[] rsFluidTemplates = new FluidStack[256];
        int position;
        List<Item> types;

        @Setup(Level.Trial) public void create() {
            bootstrap();
            if (profile.equals("plain") && entries != 1) throw new IllegalArgumentException("无 NBT 同种材料只占一个 Key");
            if (profile.equals("mixed") && kind.equals("fluid")) throw new IllegalArgumentException("混合注册类型场景仅测物品");
            if (!List.of("rs", "unifiedCore", "unifiedKeyPath").contains(implementation)) throw new IllegalArgumentException(implementation);
            types = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR).limit(512).toList();
            int queries = access.equals("full") ? entries : 256;
            itemQueries = new ItemStack[queries]; fluidQueries = new FluidStack[queries]; keys = new FrozenKey[queries];
            rsItemTemplates = new ItemStack[queries]; rsFluidTemplates = new FluidStack[queries];
            ItemStack[] allItems = new ItemStack[entries]; FluidStack[] allFluids = new FluidStack[entries];
            if (implementation.equals("rs")) {
                rsItems = new ItemStorageDisk(null, -1, null);
                rsFluids = new FluidStorageDisk(null, -1, null);
                rsItems.setSettings(() -> {}, () -> AccessType.INSERT_EXTRACT);
                rsFluids.setSettings(() -> {}, () -> AccessType.INSERT_EXTRACT);
            } else core = core();
            for (int i = 0; i < entries; i++) {
                if (kind.equals("item")) {
                    ItemStack template = item(i, profile, payloadBytes, types);
                    if (core != null) check(core.insert(FrozenKey.item(template), initialAmount, true) == initialAmount);
                    else { allItems[i] = template.copyWithCount(initialAmount); rsItems.getRawStacks().put(template.getItem(), allItems[i]); }
                } else {
                    FluidStack template = fluid(i, profile, payloadBytes);
                    if (core != null) check(core.insert(FrozenKey.fluid(template), initialAmount, true) == initialAmount);
                    else { FluidStack stored = template.copy(); stored.setAmount(initialAmount); allFluids[i] = stored; rsFluids.getRawStacks().put(stored.getFluid(), stored); }
                }
            }
            // 原版预填通过官方 raw 表，避开 O(n²) 初始化；总数保持相同有符号 int 表示。
            if (rsItems != null) rsItems.updateItemCount();
            for (int i = 0; i < keys.length; i++) {
                int id = (int) (((long) i * 104729 + entries / 2) % entries);
                if (kind.equals("item")) {
                    itemQueries[i] = item(id, profile, payloadBytes, types);
                    keys[i] = FrozenKey.item(itemQueries[i]);
                    if (rsItems != null) rsItemTemplates[i] = allItems[id];
                } else {
                    fluidQueries[i] = fluid(id, profile, payloadBytes);
                    keys[i] = FrozenKey.fluid(fluidQueries[i]);
                    if (rsFluids != null) rsFluidTemplates[i] = allFluids[id];
                }
            }
        }

        @Setup(Level.Iteration) public void reset() {
            position = 0;
            for (int i = 0; i < keys.length; i++) {
                if (core != null) {
                    int slot = core.table(keys[i].kind()).exactSlot(keys[i]);
                    int current = core.table(keys[i].kind()).amount(slot);
                    if (current < initialAmount) core.insert(keys[i], initialAmount - current, true);
                    else if (current > initialAmount) core.extract(keys[i].kind(), slot, current - initialAmount, true);
                } else if (kind.equals("item")) rsItemTemplates[i].setCount(initialAmount);
                else rsFluidTemplates[i].setAmount(initialAmount);
            }
            if (rsItems != null) rsItems.updateItemCount();
        }

        @TearDown(Level.Iteration) public void validate() {
            for (int i = 0; i < keys.length; i++) {
                int amount = core != null ? core.table(keys[i].kind()).amount(keys[i])
                        : kind.equals("item") ? rsItemTemplates[i].getCount() : rsFluidTemplates[i].getAmount();
                check(amount > 0 && amount <= Integer.MAX_VALUE);
            }
            if (core != null) check(core.table(kind.equals("item") ? FrozenKey.Kind.ITEM : FrozenKey.Kind.FLUID).size() == entries);
        }

        int next() { int result = position++; if (position == keys.length) position = 0; return result; }
    }

    static void check(boolean value) { if (!value) throw new IllegalStateException("基准库存数量或容量不符合预期"); }

    @Benchmark public int existingInsert(Data data) {
        int i = data.next();
        if (data.core != null) return data.implementation.equals("unifiedCore") ? data.core.insert(data.keys[i], 1, true)
                : data.kind.equals("item") ? data.core.insertItem(data.itemQueries[i], 1, true)
                : data.core.insertFluid(data.fluidQueries[i], 1, true);
        return data.kind.equals("item") ? 1 - data.rsItems.insert(data.itemQueries[i], 1, Action.PERFORM).getCount()
                : 1 - data.rsFluids.insert(data.fluidQueries[i], 1, Action.PERFORM).getAmount();
    }

    @Benchmark public Object existingExtract(Data data) {
        int i = data.next();
        if (data.core != null) {
            FrozenKey.Kind kind = data.keys[i].kind();
            int slot = data.implementation.equals("unifiedCore") ? data.core.table(kind).exactSlot(data.keys[i])
                    : data.kind.equals("item") ? data.core.items.exactSlot(data.itemQueries[i])
                    : data.core.fluids.exactSlot(data.fluidQueries[i]);
            FrozenKey key = data.core.table(kind).key(slot);
            int taken = data.core.extract(kind, slot, 1, true);
            // KeyPath 包含模板返回副本，与原版 extract 返回 Stack 的成本相符。
            if (data.implementation.equals("unifiedKeyPath")) return key.kind() == FrozenKey.Kind.ITEM
                    ? key.itemStack(taken) : key.fluidStack(taken);
            return taken;
        }
        return data.kind.equals("item") ? data.rsItems.extract(data.itemQueries[i], 1, IComparer.COMPARE_NBT, Action.PERFORM)
                : data.rsFluids.extract(data.fluidQueries[i], 1, IComparer.COMPARE_NBT, Action.PERFORM);
    }

    @State(Scope.Thread)
    public static class NewData extends Data {
        ItemStack newItem;
        FluidStack newFluid;
        FrozenKey newKey;

        @Setup(Level.Trial) public void createNew() {
            if (entries >= 262144) throw new IllegalArgumentException("新增场景需要空余 Key 容量及不同身份");
            newItem = item(entries + 7, profile, payloadBytes, types);
            newFluid = fluid(entries + 7, profile, payloadBytes);
            if (profile.equals("plain")) { newItem = new ItemStack(Items.DIAMOND); newFluid = new FluidStack(Fluids.LAVA, 1); }
            if (profile.equals("materials")) newItem = new ItemStack(types.get(entries));
            newKey = kind.equals("item") ? FrozenKey.item(newItem) : FrozenKey.fluid(newFluid);
        }

        // 只对新增基准使用 Invocation setup，移除放在计时区间之外，不计为插入耗时。
        @Setup(Level.Invocation) public void removePrevious() {
            if (core != null) core.extract(newKey.kind(), core.table(newKey.kind()).exactSlot(newKey), 1, true);
            else if (kind.equals("item")) rsItems.extract(newItem, 1, IComparer.COMPARE_NBT, Action.PERFORM);
            else rsFluids.extract(newFluid, 1, IComparer.COMPARE_NBT, Action.PERFORM);
        }

        @Override @TearDown(Level.Iteration) public void validate() { removePrevious(); super.validate(); }
    }

    @Benchmark public int newEntryInsert(NewData data) {
        if (data.core != null) {
            return data.implementation.equals("unifiedCore") ? data.core.insert(data.newKey, 1, true)
                    : data.kind.equals("item") ? data.core.insertItem(data.newItem, 1, true)
                    : data.core.insertFluid(data.newFluid, 1, true);
        }
        return data.kind.equals("item") ? 1 - data.rsItems.insert(data.newItem, 1, Action.PERFORM).getCount()
                : 1 - data.rsFluids.insert(data.newFluid, 1, Action.PERFORM).getAmount();
    }
}
