package com.huanghuang.rsintegration.feature.resonance.api;

import com.huanghuang.rsintegration.api.ResourceMatchMode;
import com.huanghuang.rsintegration.api.resonance.ResonanceDiagnosticCode;
import com.huanghuang.rsintegration.api.resonance.ResonanceEntry;
import com.huanghuang.rsintegration.api.resonance.ResonanceExtraction;
import com.huanghuang.rsintegration.api.resonance.ResonanceInsertion;
import com.huanghuang.rsintegration.api.resonance.ResonanceResult;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultResonanceInventoryTest extends BootstrapTest {
    @Test
    void snapshotSupportsIdentityModesAndExactTransactionalExtraction() {
        ItemStack water = potion("water", null);
        ItemStack purerWater = potion("water", "Pure");
        FakeView view = new FakeView(List.of(
                new Stored(0, water),
                new Stored(1, purerWater)
        ));
        DefaultResonanceInventory inventory = inventory(view);

        assertEquals(2, inventory.find(
                potion(null, null),
                ResourceMatchMode.ANY_IDENTITY
        ).size());
        assertEquals(1, inventory.find(
                water,
                ResourceMatchMode.EXACT_IDENTITY
        ).size());
        assertEquals(2, inventory.find(
                water,
                ResourceMatchMode.PARTIAL_IDENTITY
        ).size());

        ResonanceEntry exact = inventory.find(
                water,
                ResourceMatchMode.EXACT_IDENTITY
        ).get(0);
        ResonanceResult<ResonanceExtraction> simulated =
                inventory.extract(exact.entryId(), 1, true);
        assertTrue(simulated.successful());
        assertEquals(0L, view.revisionChanges);
        assertEquals(1, view.stored(0).getCount());

        ResonanceResult<ResonanceExtraction> extracted =
                inventory.extract(exact.entryId(), 1, false);
        assertTrue(extracted.successful());
        assertEquals(1L, view.revisionChanges);
        assertTrue(view.stored(0).isEmpty());

        ResonanceResult<ResonanceExtraction> stale =
                inventory.extract(exact.entryId(), 1, true);
        assertFalse(stale.successful());
        assertEquals(
                ResonanceDiagnosticCode.STALE_SNAPSHOT,
                stale.diagnostics().get(0).code()
        );
    }

    @Test
    void insertionReturnsBackendRemainderAndUpdatesRevision() {
        FakeView view = new FakeView(List.of());
        DefaultResonanceInventory inventory = inventory(view);
        ItemStack inserted = new ItemStack(Items.DIAMOND, 4);

        ResonanceResult<ResonanceInsertion> simulated =
                inventory.insert(inserted, true);
        assertTrue(simulated.successful());
        assertTrue(simulated.value().remainder().isEmpty());
        assertEquals(0L, view.revisionChanges);

        ResonanceResult<ResonanceInsertion> result =
                inventory.insert(inserted, false);
        assertTrue(result.successful());
        assertTrue(result.value().remainder().isEmpty());
        assertEquals(4, view.insertedCount);
        assertEquals(1L, view.revisionChanges);
    }

    @Test
    void backendFailureReturnsStableDiagnosticInsteadOfLeakingException() {
        FakeView view = new FakeView(List.of(
                new Stored(0, new ItemStack(Items.DIAMOND))
        ));
        DefaultResonanceInventory inventory = inventory(view);
        ResonanceEntry entry = inventory.entries().get(0);
        view.extractionFailure = new IllegalStateException("backend failed");

        ResonanceResult<ResonanceExtraction> result =
                inventory.extract(entry.entryId(), 1, true);

        assertFalse(result.successful());
        assertEquals(
                ResonanceDiagnosticCode.INTERNAL_ERROR,
                result.diagnostics().get(0).code()
        );
    }

    @Test
    void wrongThreadDoesNotPretendTheSnapshotIsStale() {
        FakeView view = new FakeView(List.of(
                new Stored(0, new ItemStack(Items.DIAMOND))
        ));
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(false);
        DefaultResonanceInventory inventory = inventory(view, server);
        ResonanceEntry entry = inventory.entries().get(0);

        ResonanceResult<ResonanceExtraction> result =
                inventory.extract(entry.entryId(), 1, true);

        assertFalse(result.successful());
        assertEquals(
                ResonanceDiagnosticCode.MAIN_THREAD_REQUIRED,
                result.diagnostics().get(0).code()
        );
    }

    private static DefaultResonanceInventory inventory(FakeView view) {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        return inventory(view, server);
    }

    private static DefaultResonanceInventory inventory(
            FakeView view,
            MinecraftServer server
    ) {
        ServerPlayer player = mock(ServerPlayer.class);
        setServer(player, server);
        when(player.isRemoved()).thenReturn(false);
        return new DefaultResonanceInventory(player, List.of(view));
    }

    private static ItemStack potion(
            String type,
            String purity
    ) {
        ItemStack stack = new ItemStack(Items.POTION);
        if (type == null) return stack;
        stack.getOrCreateTag().putString("Potion", type);
        if (purity != null) {
            stack.getOrCreateTag().putString("Purity", purity);
        }
        return stack;
    }

    private static void setServer(
            ServerPlayer player,
            MinecraftServer server
    ) {
        try {
            Field field = ServerPlayer.class.getField("server");
            field.setAccessible(true);
            field.set(player, server);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private record Stored(int slot, ItemStack stack) {
        private Stored {
            stack = stack.copy();
        }

        @Override
        public ItemStack stack() {
            return stack.copy();
        }
    }

    private static final class FakeView implements ResonanceStorageView {
        private final List<Stored> stacks = new ArrayList<>();
        private long revisionChanges;
        private int insertedCount;
        private RuntimeException extractionFailure;

        private FakeView(List<Stored> stacks) {
            this.stacks.addAll(stacks);
        }

        private ItemStack stored(int slot) {
            return stacks.stream()
                    .filter(stored -> stored.slot() == slot)
                    .map(Stored::stack)
                    .findFirst()
                    .orElse(ItemStack.EMPTY);
        }

        @Override
        public String backendId() {
            return "test";
        }

        @Override
        public List<StoredStack> storedStacks() {
            return stacks.stream()
                    .filter(stored -> !stored.stack().isEmpty())
                    .map(stored -> new StoredStack(
                            stored.slot(), stored.stack()))
                    .toList();
        }

        @Override
        public int getStored() {
            return stacks.stream()
                    .mapToInt(stored -> stored.stack().getCount())
                    .sum();
        }

        @Override
        public int getCapacity() {
            return 4096;
        }

        @Override
        public int abilityMask() {
            return 0;
        }

        @Override
        public boolean hasAbility(int ability) {
            return false;
        }

        @Override
        public boolean unlockAbility(int ability) {
            return false;
        }

        @Override
        public long contentRevision() {
            return revisionChanges;
        }

        @Override
        public SlotMutationResult reconcileSlotView(
                int slot,
                ItemStack previous,
                ItemStack requested
        ) {
            return SlotMutationResult.REJECTED;
        }

        @Override
        public ItemStack extractExactView(
                int slot,
                ItemStack template,
                int size,
                boolean simulate
        ) {
            if (extractionFailure != null) {
                throw extractionFailure;
            }
            for (int index = 0; index < stacks.size(); index++) {
                Stored stored = stacks.get(index);
                if (stored.slot() != slot
                        || !ItemStack.isSameItemSameTags(
                        stored.stack(), template)
                        || size > stored.stack().getCount()) {
                    continue;
                }
                ItemStack result = stored.stack().copyWithCount(size);
                if (!simulate) {
                    int remaining = stored.stack().getCount() - size;
                    stacks.set(index, new Stored(
                            slot,
                            remaining <= 0
                                    ? ItemStack.EMPTY
                                    : stored.stack().copyWithCount(remaining)
                    ));
                    revisionChanges++;
                }
                return result;
            }
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertView(
                int slot,
                ItemStack stack,
                int size,
                boolean simulate
        ) {
            return insertUnassigned(stack, size, simulate);
        }

        @Override
        public ItemStack insertUnassigned(
                ItemStack stack,
                int size,
                boolean simulate
        ) {
            int accepted = Math.min(size, getCapacity() - getStored());
            if (!simulate && accepted > 0) {
                insertedCount += accepted;
                stacks.add(new Stored(
                        100 + stacks.size(),
                        stack.copyWithCount(accepted)
                ));
                revisionChanges++;
            }
            return accepted >= stack.getCount()
                    ? ItemStack.EMPTY
                    : stack.copyWithCount(stack.getCount() - accepted);
        }
    }
}
