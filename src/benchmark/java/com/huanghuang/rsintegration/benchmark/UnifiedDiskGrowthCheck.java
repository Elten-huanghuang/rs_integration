package com.huanghuang.rsintegration.benchmark;

import com.google.gson.GsonBuilder;
import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.refinedmods.refinedstorage.api.storage.AccessType;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.apiimpl.storage.disk.ItemStorageDisk;
import com.refinedmods.refinedstorage.apiimpl.storage.disk.FluidStorageDisk;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fluids.FluidStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 持续填入不同身份，保留各批次原始耗时；属于单 JVM 观察，不冒充 JMH 置信区间。 */
public final class UnifiedDiskGrowthCheck {
    private UnifiedDiskGrowthCheck() {}
    private static void run(List<Map<String, Object>> results, String profile, String kind, int count, int payload) {
        List<Item> types = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR).limit(512).toList();
        ItemStack[] items = new ItemStack[count]; FluidStack[] fluids = new FluidStack[count];
        for (int i = 0; i < count; i++) {
            items[i] = UnifiedDiskBenchmark.item(i, profile, payload, types);
            fluids[i] = UnifiedDiskBenchmark.fluid(i, profile, payload);
        }
        for (int repeat = 0; repeat < 3; repeat++) for (String implementation : new String[] {"unifiedKeyPath", "rs"}) {
            var core = UnifiedDiskBenchmark.core();
            var rsItems = new ItemStorageDisk(null, -1, null);
            var rsFluids = new FluidStorageDisk(null, -1, null);
            rsItems.setSettings(() -> {}, () -> AccessType.INSERT_EXTRACT);
            rsFluids.setSettings(() -> {}, () -> AccessType.INSERT_EXTRACT);
            List<Map<String, Object>> batches = new ArrayList<>();
            long totalNanos = 0;
            for (int start = 0; start < count; start += 256) {
                int end = Math.min(count, start + 256);
                long began = System.nanoTime();
                for (int i = start; i < end; i++) {
                    int accepted;
                    if (implementation.equals("unifiedKeyPath")) accepted = kind.equals("item") ? core.insertItem(items[i], 64, true) : core.insertFluid(fluids[i], 1000, true);
                    else accepted = kind.equals("item") ? 64 - rsItems.insert(items[i], 64, Action.PERFORM).getCount() : 1000 - rsFluids.insert(fluids[i], 1000, Action.PERFORM).getAmount();
                    UnifiedDiskBenchmark.check(accepted == (kind.equals("item") ? 64 : 1000));
                }
                long elapsed = System.nanoTime() - began; totalNanos += elapsed;
                batches.add(Map.of("firstKey", start, "entries", end - start, "elapsedNanos", elapsed));
            }
            int size = implementation.equals("unifiedKeyPath") ? core.table(kind.equals("item") ? FrozenKey.Kind.ITEM : FrozenKey.Kind.FLUID).size()
                    : kind.equals("item") ? rsItems.getRawStacks().size() : rsFluids.getRawStacks().size();
            UnifiedDiskBenchmark.check(size == count);
            // 逐项核对，验证持续增长和扩容后仍能精确提取每一种身份。
            for (int i = 0; i < count; i++) {
                int actual;
                if (implementation.equals("unifiedKeyPath")) actual = kind.equals("item") ? core.items.amount(core.items.exactSlot(items[i])) : core.fluids.amount(core.fluids.exactSlot(fluids[i]));
                else actual = kind.equals("item") ? rsItems.extract(items[i], 64, 1, Action.SIMULATE).getCount() : rsFluids.extract(fluids[i], 1000, 1, Action.SIMULATE).getAmount();
                UnifiedDiskBenchmark.check(actual == (kind.equals("item") ? 64 : 1000));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("profile", profile); result.put("kind", kind); result.put("entries", count);
            result.put("payloadBytes", payload); result.put("implementation", implementation); result.put("repeat", repeat);
            result.put("totalNanos", totalNanos); result.put("meanNanosPerNewKey", (double) totalNanos / count);
            result.put("batches", batches); result.put("everyAmountVerified", true); results.add(result);
            System.out.println("GROWTH " + profile + " " + kind + " " + implementation + " " + repeat + " " + totalNanos);
        }
    }
    public static void main(String[] args) throws Exception {
        UnifiedDiskBenchmark.bootstrap();
        List<Map<String, Object>> results = new ArrayList<>();
        run(results, "materials", "item", 512, 0);
        run(results, "variants", "item", 10000, 0);
        run(results, "variants", "fluid", 10000, 0);
        run(results, "variants", "item", 1000, 4096);
        Files.writeString(Path.of(args[0]), new GsonBuilder().setPrettyPrinting().create().toJson(results));
    }
}
