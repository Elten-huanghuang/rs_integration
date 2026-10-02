package com.huanghuang.rsintegration.benchmark;

import com.google.gson.GsonBuilder;
import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.huanghuang.rsintegration.disk.core.ResourceTable;
import com.huanghuang.rsintegration.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.disk.persistence.DiskFileStore;
import com.huanghuang.rsintegration.disk.persistence.DiskFileStore.Snapshot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 独立进程验证两张满表、数量上限和保存恢复；只创建本次测试的新目录。 */
public final class UnifiedDiskLimitCheck {
    private static final int CAPACITY = 262144;
    private UnifiedDiskLimitCheck() {}

    private static FrozenKey key(FrozenKey.Kind kind, int id) {
        if (kind == FrozenKey.Kind.ITEM) {
            ItemStack stack = new ItemStack(Items.STONE);
            stack.getOrCreateTag().putInt("variant", id);
            return FrozenKey.item(stack);
        }
        FluidStack stack = new FluidStack(Fluids.WATER, 1);
        stack.getOrCreateTag().putInt("variant", id);
        return FrozenKey.fluid(stack);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private static long retainedHeap() throws InterruptedException {
        System.gc(); Thread.sleep(300);
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private static double millis(long started) { return (System.nanoTime() - started) / 1_000_000.0; }

    private static void acknowledge(UnifiedDiskCore core, Snapshot snapshot) {
        core.items.acknowledge(snapshot.items()); core.fluids.acknowledge(snapshot.fluids());
    }

    public static void main(String[] args) throws Exception {
        UnifiedDiskBenchmark.bootstrap();
        Path run = Path.of(args[0]).resolve(UUID.randomUUID().toString());
        Files.createDirectories(run);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timeUtc", Instant.now().toString());
        result.put("java", System.getProperty("java.runtime.version"));
        result.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        long baseline = retainedHeap();
        UnifiedDiskCore core = UnifiedDiskBenchmark.core();
        long started = System.nanoTime();
        for (FrozenKey.Kind kind : FrozenKey.Kind.values()) {
            for (int i = 0; i < CAPACITY; i++) {
                check(core.insert(key(kind, i), Integer.MAX_VALUE, true) == Integer.MAX_VALUE, "填盘失败 " + kind + " " + i);
            }
            System.out.println("filled " + kind + " " + core.table(kind).size());
            check(core.table(kind).displayTotal() == (long) CAPACITY * Integer.MAX_VALUE,
                    "最大容量的精确展示总量错误 " + kind);
        }
        result.put("fillBothTablesMillis", millis(started));
        result.put("retainedHeapDeltaBytesApprox", retainedHeap() - baseline);
        result.put("payloadBytes", core.payloadBytes());
        result.put("itemEntries", core.items.size());
        result.put("fluidEntries", core.fluids.size());
        for (FrozenKey.Kind kind : FrozenKey.Kind.values()) {
            FrozenKey existing = key(kind, 10), extra = key(kind, CAPACITY);
            ResourceTable table = core.table(kind);
            check(core.insert(extra, 1, false) == 0 && core.insert(extra, 1, true) == 0, "种类上限没有拒绝新 Key");
            check(core.insert(existing, 1, true) == 0, "MAX 数量没有拒绝溢出");
            check(core.extract(kind, table.exactSlot(existing), 7, true) == 7, "提取失败");
            check(core.insert(existing, 7, true) == 7, "满种类盘无法增加已有 Key");
            ResourceTable.Handle old = table.handle(existing);
            check(core.extract(kind, old.slot(), Integer.MAX_VALUE, true) == Integer.MAX_VALUE, "删除失败");
            check(core.insert(extra, Integer.MAX_VALUE, true) == Integer.MAX_VALUE, "复用空槽失败");
            check(!table.valid(old), "旧句柄再次有效");
            check(table.total() == Integer.MAX_VALUE && table.size() == CAPACITY, "总量或种类统计错误");
        }
        DiskFileStore files = new DiskFileStore(run.resolve("pages"));
        started = System.nanoTime();
        Snapshot snapshot = Snapshot.freeze(core);
        result.put("initialFreezeMillis", millis(started));
        started = System.nanoTime();
        var saved = files.save(snapshot, null);
        result.put("initialSaveMillis", millis(started));
        result.put("initialKeySegments", saved.keySegmentsWritten());
        acknowledge(core, snapshot);
        for (FrozenKey.Kind kind : FrozenKey.Kind.values()) {
            for (int i = 0; i < CAPACITY; i += 997) {
                if (i != 10) core.extract(kind, core.table(kind).exactSlot(key(kind, i)), 1, true);
            }
        }
        started = System.nanoTime();
        snapshot = Snapshot.freeze(core);
        result.put("amountOnlyFreezeMillis", millis(started));
        started = System.nanoTime();
        saved = files.save(snapshot, saved.manifest());
        result.put("amountOnlySaveMillis", millis(started));
        result.put("amountOnlyKeySegments", saved.keySegmentsWritten());
        result.put("amountOnlyAmountPages", saved.amountPagesWritten());
        check(saved.keySegmentsWritten() == 0, "数量变化重写了身份页");
        acknowledge(core, snapshot);
        started = System.nanoTime();
        for (int i = 0; i < 10000; i++) check(Snapshot.freeze(core).items().isEmpty(), "已确认盘仍生成脏页快照");
        result.put("cleanFreezeMeanNanos", (System.nanoTime() - started) / 10000.0);
        started = System.nanoTime();
        UnifiedDiskCore loaded = files.load(core.worldId, core.diskId);
        result.put("reloadMillis", millis(started));
        check(loaded.items.size() == CAPACITY && loaded.fluids.size() == CAPACITY, "恢复种类数错误");
        for (FrozenKey.Kind kind : FrozenKey.Kind.values()) {
            check(loaded.table(kind).displayTotal() == core.table(kind).displayTotal(), "恢复展示总量错误 " + kind);
            for (int i = 0; i <= CAPACITY; i++) {
                int expected = i == 10 ? 0 : i < CAPACITY && i % 997 == 0 ? Integer.MAX_VALUE - 1 : Integer.MAX_VALUE;
                check(loaded.table(kind).amount(key(kind, i)) == expected, "恢复数量错误 " + kind + " " + i);
            }
        }
        check(!loaded.dirty(), "恢复后仍 dirty");
        result.put("allRestoredAmountsVerified", true);
        try (var paths = Files.walk(run)) {
            long bytes = 0;
            for (Path path : paths.filter(Files::isRegularFile).toList()) bytes += Files.size(path);
            result.put("savedFilesBytes", bytes);
        }
        UnifiedDiskCore bounded = new UnifiedDiskCore(UUID.randomUUID(), UUID.randomUUID(), null,
                new UnifiedDiskCore.Limits(16, 16, 1048576, 1048576));
        ItemStack big = new ItemStack(Items.STONE);
        big.getOrCreateTag().putByteArray("large", new byte[600000]);
        FrozenKey first = FrozenKey.item(big);
        check(bounded.insert(first, 1, true) == 1, "载荷预算内插入失败");
        big.getOrCreateTag().putInt("different", 1);
        check(bounded.insert(FrozenKey.item(big), 1, true) == 0, "累计载荷预算未生效");
        big.getTag().putByteArray("large", new byte[1048576]);
        boolean rejected = false;
        try { FrozenKey.item(big); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "单 Key 载荷预算未生效");
        result.put("payloadLimitsVerified", true);
        result.put("status", "PASS");
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
        Files.writeString(run.resolve("result.json"), json);
        Files.writeString(Path.of(args[0]).resolve("latest-result.json"), json);
        System.out.println(json);
    }
}
