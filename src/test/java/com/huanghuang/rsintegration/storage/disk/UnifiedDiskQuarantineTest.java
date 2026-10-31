package com.huanghuang.rsintegration.storage.disk;

import com.huanghuang.rsintegration.storage.disk.core.FrozenKey;
import com.huanghuang.rsintegration.storage.disk.core.ResourceTable;
import com.huanghuang.rsintegration.storage.disk.core.ResourceTable.PageSnapshot;
import com.huanghuang.rsintegration.storage.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.storage.disk.core.UnifiedDiskSummary;
import com.huanghuang.rsintegration.storage.disk.persistence.DiskFileStore;
import com.huanghuang.rsintegration.storage.disk.persistence.DiskFileStore.Snapshot;
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

    /** 模拟模组升级后在恢复 Stack 时重写资源标签。 */
    private MockedStatic<?> changeRestoredIdentity(FrozenKey.Kind kind, boolean mutateInput) {
        if (kind == FrozenKey.Kind.ITEM) {
            MockedStatic<ItemStack> stacks = mockStatic(ItemStack.class, CALLS_REAL_METHODS);
            stacks.when(() -> ItemStack.of(any(CompoundTag.class))).thenAnswer(call -> {
                ItemStack restored = (ItemStack) call.callRealMethod();
                if (restored.hasTag() && "待恢复物品".equals(restored.getTag().getString("custom"))) {
                    if (!mutateInput) restored.setTag(restored.getTag().copy());
                    restored.getTag().putString("custom", "恢复时已变化");
                }
                return restored;
            });
            return stacks;
        }
        MockedStatic<FluidStack> stacks = mockStatic(FluidStack.class, CALLS_REAL_METHODS);
        stacks.when(() -> FluidStack.loadFluidStackFromNBT(any(CompoundTag.class))).thenAnswer(call -> {
            FluidStack restored = (FluidStack) call.callRealMethod();
            if (restored.hasTag() && restored.getTag().getInt("temperature") == 400) {
                if (!mutateInput) restored.setTag(restored.getTag().copy());
                restored.getTag().putInt("temperature", 999);
            }
            return restored;
        });
        return stacks;
    }

    @Test void changedCapabilitiesAreQuarantinedWithoutDisablingHealthyItemsOrFluids() throws Exception {
        DiskFileStore files = new DiskFileStore(directory);
        UnifiedDiskCore original = UnifiedDiskCoreTest.core(4);
        FrozenKey affected = key(FrozenKey.Kind.ITEM, true), healthy = key(FrozenKey.Kind.ITEM, false);
        original.insert(affected, 17, true);
        original.insert(healthy, 31, true);
        original.insert(key(FrozenKey.Kind.FLUID, false), 91, true);
        CompoundTag raw = decode(affected.payload());
        CompoundTag caps = new CompoundTag();
        caps.putString("changed_mod:capability", "不能丢弃的原始数据");
        raw.put("ForgeCaps", caps);
        byte[] payload = encode(raw);
        var saved = files.save(replaceFirst(Snapshot.freeze(original), FrozenKey.Kind.ITEM, payload), null);
        Path manifest = directory.resolve(original.diskId.toString()).resolve("manifest.bin");
        byte[] before = Files.readAllBytes(manifest);

        try (MockedStatic<ItemStack> stacks = mockStatic(ItemStack.class, CALLS_REAL_METHODS)) {
            stacks.when(() -> ItemStack.of(any(CompoundTag.class))).thenAnswer(call -> {
                // 模拟当前模组环境无法重新挂载原有能力，反序列化时丢弃 ForgeCaps。
                CompoundTag input = call.getArgument(0);
                input.remove("ForgeCaps");
                return call.callRealMethod();
            });
            UnifiedDiskCore loaded = files.load(original.worldId, original.diskId);

            assertNull(loaded.items.key(0));
            assertEquals(17, loaded.items.amount(0));
            assertEquals(1, loaded.items.quarantinedSize());
            assertEquals(new ResourceLocation("minecraft:stone"), loaded.items.quarantined(0).resource());
            assertArrayEquals(payload, loaded.items.quarantined(0).payload());
            assertEquals(31, loaded.items.amount(healthy));
            assertEquals(91, loaded.fluids.total());
            assertFalse(loaded.dirty());
            assertEquals(payload.length + healthy.payloadBytes()
                    + key(FrozenKey.Kind.FLUID, false).payloadBytes(), loaded.payloadBytes());
            assertArrayEquals(before, Files.readAllBytes(manifest));
            assertEquals(4, loaded.extract(FrozenKey.Kind.ITEM, loaded.items.exactSlot(healthy), 4, true));
            files.save(Snapshot.freeze(loaded), saved.manifest());
            UnifiedDiskCore reloaded = files.load(original.worldId, original.diskId);
            assertArrayEquals(payload, reloaded.items.quarantined(0).payload());
            assertEquals(17, reloaded.items.amount(0));
            assertEquals(27, reloaded.items.amount(healthy));
        }
    }

    @ParameterizedTest @EnumSource(FrozenKey.Kind.class)
    void changedIdentitiesPreserveSlotsAndRecoverAfterCompatibleDeserializationReturns(FrozenKey.Kind kind)
            throws Exception {
        DiskFileStore files = new DiskFileStore(directory);
        UnifiedDiskCore original = UnifiedDiskCoreTest.core(2);
        FrozenKey affected = key(kind, true), healthy = key(kind, false);
        long amount = Integer.MAX_VALUE + 17L;
        original.insert(affected, amount, true);
        original.insert(healthy, 31, true);
        var saved = files.save(Snapshot.freeze(original), null);
        try (var ignored = changeRestoredIdentity(kind, false)) {
            UnifiedDiskCore loaded = files.load(original.worldId, original.diskId);
            ResourceTable table = loaded.table(kind);
            assertEquals(1, table.size());
            assertEquals(1, table.quarantinedSize());
            assertFalse(table.canCreate());
            assertEquals(amount, table.amount(0));
            assertEquals(0, loaded.extract(kind, 0, 1, true));
            assertArrayEquals(affected.payload(), table.quarantined(0).payload());
            assertEquals(original.payloadBytes(), loaded.payloadBytes());
            assertEquals(31, table.amount(healthy));
            assertFalse(loaded.dirty());

            loaded.extract(kind, table.exactSlot(healthy), 31, true);
            loaded.insert(healthy, 7, true);
            var rewritten = files.save(Snapshot.freeze(loaded), saved.manifest());
            assertTrue(rewritten.keySegmentsWritten() > 0);
            UnifiedDiskCore reloaded = files.load(original.worldId, original.diskId);
            PageSnapshot page = reloaded.table(kind).snapshot(true).get(0);
            assertArrayEquals(affected.payload(), page.keys()[0]);
            assertEquals(1, page.generations()[0]);
            assertEquals(1, page.orders()[0]);
            assertEquals(amount, page.amounts()[0]);
        }
        UnifiedDiskCore restored = files.load(original.worldId, original.diskId);
        assertEquals(0, restored.table(kind).quarantinedSize());
        assertEquals(2, restored.table(kind).size());
        assertEquals(0, restored.table(kind).exactSlot(affected));
        assertEquals(amount, restored.table(kind).amount(affected));
        assertEquals(7, restored.table(kind).amount(healthy));
        assertArrayEquals(affected.payload(), restored.table(kind).key(0).payload());
    }

    @ParameterizedTest @EnumSource(FrozenKey.Kind.class)
    void deserializerMutationCannotRewriteTheIdentityUsedForValidation(FrozenKey.Kind kind) throws Exception {
        FrozenKey original = key(kind, true);
        byte[] payload = original.payload();
        try (var ignored = changeRestoredIdentity(kind, true)) {
            var failure = assertThrows(FrozenKey.IdentityChangedException.class, () -> FrozenKey.load(kind, payload));
            assertEquals(kind, failure.kind());
            assertEquals(original.resourceId(), failure.resource());
            assertTrue(failure.getMessage().contains(original.resourceId().toString()));
            assertTrue(failure.getMessage().contains(kind == FrozenKey.Kind.ITEM ? "tag" : "Tag"));
        }
        assertArrayEquals(payload, FrozenKey.load(kind, payload).payload());
    }

    @ParameterizedTest @EnumSource(FrozenKey.Kind.class)
    void extensionFieldsArePreservedInQuarantineAcrossSavesWithoutDisablingHealthyInventory(FrozenKey.Kind kind)
            throws Exception {
        DiskFileStore files = new DiskFileStore(directory);
        for (String field : List.of("ForgeData", "mod:component", "legacy_field")) {
            UnifiedDiskCore original = UnifiedDiskCoreTest.core(2);
            FrozenKey affected = key(kind, true), healthy = key(kind, false);
            long amount = Integer.MAX_VALUE + 17L;
            original.insert(affected, amount, true);
            original.insert(healthy, 31, true);
            FrozenKey.Kind otherKind = kind == FrozenKey.Kind.ITEM ? FrozenKey.Kind.FLUID : FrozenKey.Kind.ITEM;
            original.insert(key(otherKind, false), 91, true);
            CompoundTag raw = decode(affected.payload());
            if (field.equals("ForgeData")) {
                CompoundTag extension = new CompoundTag();
                extension.putString("mod:original", "必须保留的扩展数据");
                raw.put(field, extension);
            } else if (field.equals("mod:component")) {
                raw.putLong(field, Long.MAX_VALUE);
            } else {
                raw.putString(field, "旧版资源元数据");
            }
            byte[] payload = encode(raw);
            var saved = files.save(replaceFirst(Snapshot.freeze(original), kind, payload), null);
            Path manifest = directory.resolve(original.diskId.toString()).resolve("manifest.bin");
            byte[] before = Files.readAllBytes(manifest);

            var diagnostic = assertThrows(FrozenKey.IdentityChangedException.class, () -> FrozenKey.load(kind, payload));
            assertEquals(affected.resourceId(), diagnostic.resource());
            assertTrue(diagnostic.getMessage().contains(field));
            UnifiedDiskCore loaded = files.load(original.worldId, original.diskId);
            ResourceTable table = loaded.table(kind);
            assertNull(table.key(0));
            assertEquals(amount, table.amount(0));
            assertEquals(1, table.quarantinedSize());
            assertFalse(table.canCreate());
            assertEquals(0, loaded.extract(kind, 0, 1, true));
            assertEquals(31, table.amount(healthy));
            assertEquals(91, loaded.table(otherKind).total());
            assertArrayEquals(payload, table.quarantined(0).payload());
            assertEquals(original.payloadBytes() - affected.payloadBytes() + payload.length, loaded.payloadBytes());
            assertFalse(loaded.dirty());
            assertArrayEquals(before, Files.readAllBytes(manifest));

            loaded.extract(kind, table.exactSlot(healthy), 31, true);
            loaded.insert(healthy, 7, true);
            var rewritten = files.save(Snapshot.freeze(loaded), saved.manifest());
            assertTrue(rewritten.keySegmentsWritten() > 0);
            UnifiedDiskCore reloaded = files.load(original.worldId, original.diskId);
            PageSnapshot page = reloaded.table(kind).snapshot(true).get(0);
            assertArrayEquals(payload, page.keys()[0]);
            assertEquals(amount, page.amounts()[0]);
            assertEquals(1, page.generations()[0]);
            assertEquals(1, page.orders()[0]);
            assertEquals(7, reloaded.table(kind).amount(healthy));
        }
    }

    @ParameterizedTest @EnumSource(FrozenKey.Kind.class)
    void invalidPayloadFieldsStillRejectTheWholeDiskInsteadOfBeingQuarantined(FrozenKey.Kind kind) throws Exception {
        DiskFileStore files = new DiskFileStore(directory);
        for (String problem : List.of("tag_type", "caps_type", "amount_type", "nan", "missing_with_nan")) {
            UnifiedDiskCore core = UnifiedDiskCoreTest.core(2);
            FrozenKey original = key(kind, true);
            core.insert(original, 17, true);
            CompoundTag raw = decode(original.payload());
            String tagField = kind == FrozenKey.Kind.ITEM ? "tag" : "Tag";
            switch (problem) {
                case "tag_type" -> raw.putInt(tagField, 1);
                case "caps_type" -> raw.putInt("ForgeCaps", 1);
                case "amount_type" -> raw.putLong(kind == FrozenKey.Kind.ITEM ? "Count" : "Amount", 1L);
                case "nan", "missing_with_nan" -> {
                    raw.getCompound(tagField).putDouble("invalid", Double.NaN);
                    if (problem.equals("missing_with_nan")) {
                        raw.putString(kind == FrozenKey.Kind.ITEM ? "id" : "FluidName", "removed_mod:resource");
                    }
                }
            }
            files.save(replaceFirst(Snapshot.freeze(core), kind, encode(raw)), null);
            Path manifest = directory.resolve(core.diskId.toString()).resolve("manifest.bin");
            byte[] before = Files.readAllBytes(manifest);
            IOException failure = assertThrows(IOException.class, () -> files.load(core.worldId, core.diskId), problem);
            assertFalse(failure instanceof FrozenKey.UnavailableResourceException, problem);
            assertTrue(failure.getMessage().contains(raw.getString(kind == FrozenKey.Kind.ITEM ? "id" : "FluidName")),
                    failure.getMessage());
            assertTrue(failure.getMessage().contains("slot=0"), failure.getMessage());
            if (problem.equals("tag_type") || problem.equals("caps_type")) {
                assertTrue(failure.getMessage().contains(problem.equals("tag_type") ? tagField : "ForgeCaps"),
                        failure.getMessage());
            }
            assertArrayEquals(before, Files.readAllBytes(manifest));
        }
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
