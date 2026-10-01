package com.huanghuang.rsintegration.benchmark;

import com.google.gson.GsonBuilder;
import com.huanghuang.rsintegration.disk.persistence.DiskFileStore;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/** 只读打开上一版真实满盘文件，逐项检查新索引对旧磁盘格式的兼容性。 */
public final class UnifiedDiskCompatibilityCheck {
    public static void main(String[] args) throws Exception {
        UnifiedDiskBenchmark.bootstrap();
        Path manifest = Path.of(args[0]);
        UUID diskId = UUID.fromString(manifest.getParent().getFileName().toString());
        var files = new DiskFileStore(manifest.getParent().getParent());
        var metadata = files.manifest(diskId);
        var core = files.load(metadata.world(), diskId);
        for (int i = 0; i <= 262144; i++) {
            ItemStack item = new ItemStack(Items.STONE); item.getOrCreateTag().putInt("variant", i);
            FluidStack fluid = new FluidStack(Fluids.WATER, 1); fluid.getOrCreateTag().putInt("variant", i);
            int expected = i == 10 ? 0 : i < 262144 && i % 997 == 0 ? Integer.MAX_VALUE - 1 : Integer.MAX_VALUE;
            int itemSlot = core.items.exactSlot(item), fluidSlot = core.fluids.exactSlot(fluid);
            UnifiedDiskBenchmark.check((itemSlot < 0 ? 0 : core.items.amount(itemSlot)) == expected);
            UnifiedDiskBenchmark.check((fluidSlot < 0 ? 0 : core.fluids.amount(fluidSlot)) == expected);
        }
        UnifiedDiskBenchmark.check(!core.dirty() && core.items.size() == 262144 && core.fluids.size() == 262144);
        // 核验模板数量，避免把旧盘 quantity 字段混进新身份。
        ItemStack zero = new ItemStack(Items.STONE); zero.getOrCreateTag().putInt("variant", 0);
        UnifiedDiskBenchmark.check(core.items.key(core.items.exactSlot(zero)).itemStack(1).getCount() == 1);
        var result = Map.of("status", "PASS", "readOnly", true, "format", DiskFileStore.FORMAT,
                "sourceManifest", manifest.toAbsolutePath().toString(), "itemEntries", core.items.size(),
                "fluidEntries", core.fluids.size(), "allOldAmountsVerified", true);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
        Path output = Path.of(args[1]); Files.createDirectories(output.getParent());
        Files.writeString(output, json); System.out.println(json);
    }
}
