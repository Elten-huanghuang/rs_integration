package com.huanghuang.rsintegration.disk;

import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.huanghuang.rsintegration.disk.core.ResourceTable;
import com.huanghuang.rsintegration.disk.core.ResourceTable.PageSnapshot;
import com.huanghuang.rsintegration.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.disk.core.UnifiedDiskSummary;
import com.huanghuang.rsintegration.disk.persistence.DiskFileStore;
import com.huanghuang.rsintegration.disk.persistence.DiskFileStore.Snapshot;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnifiedDiskQuarantineTest extends BootstrapTest {
    @TempDir Path directory;

    private record Fixture(DiskFileStore files, UnifiedDiskCore original, DiskFileStore.Saved saved,
                           FrozenKey healthy, byte[] payload) {}

    private FrozenKey key(FrozenKey.Kind kind, boolean first) {
        if (kind == FrozenKey.Kind.ITEM) {
            ItemStack stack = new ItemStack(first ? Items.STONE : Items.DIAMOND);
            stack.getOrCreateTag().putString("custom", first ? "待恢复物品" : "正常物品");
            return FrozenKey.item(stack);
        }
        FluidStack stack = new FluidStack(first ? Fluids.LAVA : Fluids.WATER, 1);
        CompoundTag tag = new CompoundTag(); tag.putInt("temperature", first ? 400 : 20);
        stack.setTag(tag);
        return FrozenKey.fluid(stack);
    }

    private byte[] encode(CompoundTag tag) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(output));
        return output.toByteArray();
    }

    private CompoundTag decode(byte[] payload) throws IOException {
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(payload)), new NbtAccounter(FrozenKey.MAX_BYTES * 16L));
    }

    private Snapshot replaceFirst(Snapshot snapshot, FrozenKey.Kind kind, byte[] payload) {
        return replace(snapshot, kind, 0, payload);
    }

    private Snapshot replace(Snapshot snapshot, FrozenKey.Kind kind, int slot, byte[] payload) {
        List<PageSnapshot> pages = kind == FrozenKey.Kind.ITEM ? snapshot.items() : snapshot.fluids();
        int index = slot / ResourceTable.PAGE_SIZE;
        PageSnapshot page = pages.get(index);
        byte[][] keys = page.keys().clone(); keys[slot % ResourceTable.PAGE_SIZE] = payload;
        PageSnapshot changed = new PageSnapshot(page.index(), page.keyRevision(), page.amountRevision(), keys,
                page.generations(), page.orders(), page.amounts());
        List<PageSnapshot> replaced = new ArrayList<>(pages); replaced.set(index, changed);
        return new Snapshot(snapshot.world(), snapshot.disk(), snapshot.owner(), snapshot.limits(),
                snapshot.itemPages(), snapshot.fluidPages(), kind == FrozenKey.Kind.ITEM ? replaced : snapshot.items(),
                kind == FrozenKey.Kind.FLUID ? replaced : snapshot.fluids(), snapshot.coreMagic());
    }

    private Fixture missingDisk(FrozenKey.Kind kind, int capacity, int payloadLimit, int extraBytes) throws Exception {
        DiskFileStore files = new DiskFileStore(directory);
        UnifiedDiskCore core = new UnifiedDiskCore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new UnifiedDiskCore.Limits(capacity, capacity, FrozenKey.MAX_BYTES, payloadLimit));
        FrozenKey missing = key(kind, true), healthy = key(kind, false);
        core.insert(missing, 17, true);
        if (capacity > 1) core.insert(healthy, 31, true);
        FrozenKey.Kind otherKind = kind == FrozenKey.Kind.ITEM ? FrozenKey.Kind.FLUID : FrozenKey.Kind.ITEM;
        core.insert(key(otherKind, false), 91, true);
        CompoundTag raw = decode(missing.payload());
        raw.putString(kind == FrozenKey.Kind.ITEM ? "id" : "FluidName", "removed_mod:resource");
        if (extraBytes > 0) raw.getCompound(kind == FrozenKey.Kind.ITEM ? "tag" : "Tag").putByteArray("large", new byte[extraBytes]);
        if (kind == FrozenKey.Kind.ITEM) {
            CompoundTag caps = new CompoundTag(); caps.putString("removed_mod:capability", "原始能力数据");
            raw.put("ForgeCaps", caps);
        }
        byte[] payload = encode(raw);
        DiskFileStore.Saved saved = files.save(replaceFirst(Snapshot.freeze(core), kind, payload), null);
        return new Fixture(files, core, saved, healthy, payload);
    }

    private UnifiedDiskCore load(Fixture fixture) throws IOException {
        return fixture.files.load(fixture.original.worldId, fixture.original.diskId);
    }

    private void assertPreserved(ResourceTable table, Fixture fixture) {
        assertNull(table.key(0));
        assertEquals(17, table.amount(0));
        assertEquals(1, table.quarantinedSize());
        assertEquals(new ResourceLocation("removed_mod:resource"), table.quarantined(0).resource());
        PageSnapshot page = table.snapshot(true).get(0);
        assertArrayEquals(fixture.payload, page.keys()[0]);
        assertEquals(1, page.generations()[0]);
        assertEquals(1, page.orders()[0]);
    }

    @ParameterizedTest @EnumSource(FrozenKey.Kind.class)
    void missingEntriesSurviveAmountOnlySavesAndKeyRewrites(FrozenKey.Kind kind) throws Exception {
        Fixture fixture = missingDisk(kind, 4, 268435456, 0);
        UnifiedDiskCore core = load(fixture);
        ResourceTable table = core.table(kind);
        assertFalse(core.dirty());
        assertEquals(1, table.size()); assertEquals(2, table.occupiedSize());
        assertEquals(31, table.total()); assertEquals(48, table.storedTotal());
        assertEquals(1, table.first(fixture.healthy.type()));
        table.forEach((key, amount) -> { assertEquals(fixture.healthy, key); assertEquals(31, amount); });
        assertPreserved(table, fixture);
        assertEquals(0, core.extract(kind, 0, 17, false));
        assertEquals(0, core.extract(kind, 0, 17, true));
        byte[] exposed = table.quarantined(0).payload(); exposed[0] ^= 1;
        assertPreserved(table, fixture);
        assertEquals(fixture.payload.length + fixture.healthy.payloadBytes()
                + key(kind == FrozenKey.Kind.ITEM ? FrozenKey.Kind.FLUID : FrozenKey.Kind.ITEM, false).payloadBytes(),
                core.payloadBytes());

        core.insert(fixture.healthy, 4, true);
        Snapshot amountOnly = Snapshot.freeze(core);
        assertNull((kind == FrozenKey.Kind.ITEM ? amountOnly.items() : amountOnly.fluids()).get(0).keys());
        var saved = fixture.files.save(amountOnly, fixture.saved.manifest());
        assertEquals(0, saved.keySegmentsWritten());
        assertEquals(1, saved.amountPagesWritten());
        core = load(fixture); table = core.table(kind);
        assertEquals(35, table.amount(fixture.healthy));
        assertPreserved(table, fixture);

        core.extract(kind, table.exactSlot(fixture.healthy), 35, true);
        assertEquals(9, core.insert(fixture.healthy, 9, true));
        assertNotEquals(0, table.exactSlot(fixture.healthy));
        var rewritten = fixture.files.save(Snapshot.freeze(core), saved.manifest());
        assertTrue(rewritten.keySegmentsWritten() > 0);
        core = load(fixture);
        assertEquals(9, core.table(kind).total());
        assertPreserved(core.table(kind), fixture);
    }

    @ParameterizedTest @EnumSource(FrozenKey.Kind.class)
    void quarantinedSlotsStillConsumeCapacityAndOtherInventoryRemainsUsable(FrozenKey.Kind kind) throws Exception {
        Fixture fixture = missingDisk(kind, 1, 268435456, 0);
        UnifiedDiskCore core = load(fixture);
        assertFalse(core.table(kind).canCreate());
        assertEquals(0, core.insert(fixture.healthy, 1, false));
        assertEquals(0, core.insert(fixture.healthy, 1, true));
        FrozenKey.Kind otherKind = kind == FrozenKey.Kind.ITEM ? FrozenKey.Kind.FLUID : FrozenKey.Kind.ITEM;
        assertEquals(91, core.table(otherKind).total());
        assertEquals(5, core.extract(otherKind, 0, 5, true));
        fixture.files.save(Snapshot.freeze(core), fixture.saved.manifest());
        assertPreserved(load(fixture).table(kind), fixture);
        UnifiedDiskSummary summary = UnifiedDiskSummary.from(core);
        assertEquals(17, kind == FrozenKey.Kind.ITEM ? summary.items() : summary.fluids());
        assertEquals(1, kind == FrozenKey.Kind.ITEM ? summary.itemTypes() : summary.fluidTypes());
    }

    @ParameterizedTest @EnumSource(FrozenKey.Kind.class)
    void quarantinedPayloadStillConsumesSharedByteBudget(FrozenKey.Kind kind) throws Exception {
        Fixture fixture = missingDisk(kind, 4, 1048576, 700000);
        UnifiedDiskCore core = load(fixture);
        FrozenKey large;
        if (kind == FrozenKey.Kind.ITEM) {
            ItemStack stack = fixture.healthy.itemStack(1);
            stack.getOrCreateTag().putByteArray("large", new byte[400000]); large = FrozenKey.item(stack);
        } else {
            FluidStack stack = fixture.healthy.fluidStack(1);
            stack.getTag().putByteArray("large", new byte[400000]); large = FrozenKey.fluid(stack);
        }
        assertEquals(0, core.insert(large, 1, false));
        assertEquals(0, core.insert(large, 1, true));
        assertFalse(core.dirty());
        assertPreserved(core.table(kind), fixture);
    }

    @ParameterizedTest @EnumSource(FrozenKey.Kind.class)
    void restoredRegistrationRecoversExactPayloadCountSlotGenerationAndOrder(FrozenKey.Kind kind) throws Exception {
        DiskFileStore files = new DiskFileStore(directory);
        UnifiedDiskCore original = UnifiedDiskCoreTest.core(4);
        FrozenKey missing = key(kind, true), healthy = key(kind, false);
        original.insert(missing, 17, true); original.insert(healthy, 31, true);
        var saved = files.save(Snapshot.freeze(original), null);
        byte[] payload = missing.payload();
        String id = kind == FrozenKey.Kind.ITEM ? "minecraft:stone" : "minecraft:lava";
        // 只模拟注册项暂时缺失；关闭模拟后，重载走真实 NBT 和注册表恢复路径。
        try (MockedStatic<FrozenKey> keys = mockStatic(FrozenKey.class, CALLS_REAL_METHODS)) {
            keys.when(() -> FrozenKey.load(kind, payload))
                    .thenThrow(new FrozenKey.MissingResourceException(kind, new ResourceLocation(id)));
            UnifiedDiskCore quarantined = files.load(original.worldId, original.diskId);
            assertEquals(1, quarantined.table(kind).quarantinedSize());
            quarantined.extract(kind, 1, 31, true);
            quarantined.insert(healthy, 7, true);
            saved = files.save(Snapshot.freeze(quarantined), saved.manifest());
            assertTrue(saved.keySegmentsWritten() > 0);
        }
        UnifiedDiskCore restored = files.load(original.worldId, original.diskId);
        ResourceTable table = restored.table(kind);
        assertEquals(0, table.quarantinedSize());
        assertEquals(2, table.size()); assertEquals(24, table.total());
        assertEquals(0, table.exactSlot(missing)); assertEquals(17, table.amount(missing));
        assertArrayEquals(payload, table.key(0).payload());
        PageSnapshot page = table.snapshot(true).get(0);
        assertEquals(1, page.generations()[0]); assertEquals(1, page.orders()[0]);
        assertEquals(3, page.orders()[table.exactSlot(healthy)]);
        assertEquals(5, restored.extract(kind, 0, 5, true));
        files.save(Snapshot.freeze(restored), saved.manifest());
        assertEquals(12, files.load(original.worldId, original.diskId).table(kind).amount(missing));
    }

    @Test void malformedPayloadsStillRejectTheWholeDiskAndKeepCheckpoint() throws Exception {
        DiskFileStore files = new DiskFileStore(directory);
        for (String problem : List.of("invalid_id", "invalid_amount", "invalid_nbt")) {
            UnifiedDiskCore core = UnifiedDiskCoreTest.core(4);
            FrozenKey stone = key(FrozenKey.Kind.ITEM, true); core.insert(stone, 17, true);
            CompoundTag raw = decode(stone.payload());
            raw.putString("id", problem.equals("invalid_id") ? "bad id" : "removed_mod:resource");
            if (problem.equals("invalid_amount")) raw.putByte("Count", (byte) 2);
            byte[] payload = problem.equals("invalid_nbt") ? new byte[]{10, 0} : encode(raw);
            files.save(replaceFirst(Snapshot.freeze(core), FrozenKey.Kind.ITEM, payload), null);
            Path manifest = directory.resolve(core.diskId.toString()).resolve("manifest.bin");
            byte[] before = Files.readAllBytes(manifest);
            assertThrows(IOException.class, () -> files.load(core.worldId, core.diskId), problem);
            assertArrayEquals(before, Files.readAllBytes(manifest));
        }
    }

    @Test void bothInventoriesCanBeEntirelyQuarantinedWithoutBecomingUnavailable() throws Exception {
        DiskFileStore files = new DiskFileStore(directory);
        UnifiedDiskCore original = UnifiedDiskCoreTest.core(1);
        FrozenKey item = key(FrozenKey.Kind.ITEM, true), fluid = key(FrozenKey.Kind.FLUID, true);
        original.insert(item, 17, true); original.insert(fluid, 23, true);
        CompoundTag itemTag = decode(item.payload()), fluidTag = decode(fluid.payload());
        itemTag.putString("id", "removed_mod:item"); fluidTag.putString("FluidName", "removed_mod:fluid");
        byte[] itemPayload = encode(itemTag), fluidPayload = encode(fluidTag);
        Snapshot snapshot = replaceFirst(Snapshot.freeze(original), FrozenKey.Kind.ITEM, itemPayload);
        var saved = files.save(replaceFirst(snapshot, FrozenKey.Kind.FLUID, fluidPayload), null);
        UnifiedDiskCore loaded = files.load(original.worldId, original.diskId);
        assertEquals(0, loaded.items.total()); assertEquals(0, loaded.fluids.total());
        assertEquals(1, loaded.items.quarantinedSize()); assertEquals(1, loaded.fluids.quarantinedSize());
        assertEquals(new UnifiedDiskSummary(17, 23, new long[3], 1, 1, 1, 1),
                UnifiedDiskSummary.from(loaded));
        assertFalse(loaded.dirty());
        assertEquals(0, files.save(Snapshot.freeze(loaded), saved.manifest()).keySegmentsWritten());
        assertArrayEquals(itemPayload, loaded.items.snapshot(true).get(0).keys()[0]);
        assertArrayEquals(fluidPayload, loaded.fluids.snapshot(true).get(0).keys()[0]);
    }

    @Test void quarantineOnLaterPageSurvivesExpansionAndKeepsHighestInsertionOrderReserved() throws Exception {
        DiskFileStore files = new DiskFileStore(directory);
        UnifiedDiskCore original = UnifiedDiskCoreTest.core(515);
        for (int index = 0; index < 514; index++) original.insert(UnifiedDiskCoreTest.variant(index), 17, true);
        CompoundTag raw = decode(original.items.key(513).payload()); raw.putString("id", "removed_mod:last");
        byte[] payload = encode(raw);
        var saved = files.save(replace(Snapshot.freeze(original), FrozenKey.Kind.ITEM, 513, payload), null);
        UnifiedDiskCore expanded = files.load(original.worldId, original.diskId, 1024, 1024);
        assertEquals(513, expanded.items.size()); assertEquals(1, expanded.items.quarantinedSize());
        assertFalse(expanded.dirty());
        var expansion = files.save(Snapshot.freeze(expanded), saved.manifest());
        assertEquals(0, expansion.keySegmentsWritten());
        expanded.extract(FrozenKey.Kind.ITEM, 512, 17, true);
        FrozenKey next = UnifiedDiskCoreTest.variant(999);
        assertEquals(7, expanded.insert(next, 7, true));
        assertEquals(512, expanded.items.exactSlot(next));
        files.save(Snapshot.freeze(expanded), expansion.manifest());
        UnifiedDiskCore reloaded = files.load(original.worldId, original.diskId);
        assertEquals(17, reloaded.items.amount(513));
        assertArrayEquals(payload, reloaded.items.quarantined(513).payload());
        PageSnapshot page = reloaded.items.snapshot(true).get(1);
        assertEquals(514, page.orders()[1]); assertEquals(1, page.generations()[1]);
        assertEquals(515, page.orders()[0]); assertEquals(2, page.generations()[0]);
        assertEquals(1024, reloaded.limits.items());
    }

    @ParameterizedTest @EnumSource(FrozenKey.Kind.class)
    void corruptPagesStillRejectDisksContainingMissingResources(FrozenKey.Kind kind) throws Exception {
        Fixture fixture = missingDisk(kind, 4, 268435456, 0);
        var pages = kind == FrozenKey.Kind.ITEM ? fixture.saved.manifest().items() : fixture.saved.manifest().fluids();
        Path page = directory.resolve(fixture.original.diskId.toString()).resolve(pages.get(0).amounts());
        byte[] bytes = Files.readAllBytes(page); bytes[30] ^= 1; Files.write(page, bytes);
        assertThrows(IOException.class, () -> load(fixture));
    }
}
