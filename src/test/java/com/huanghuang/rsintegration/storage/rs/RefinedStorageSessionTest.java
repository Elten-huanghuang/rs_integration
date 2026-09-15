package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.storage.*;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefinedStorageSessionTest extends BootstrapTest {
    private ServerPlayer player;
    private FakeDriver driver;
    private RefinedStorageSession session;

    @BeforeEach
    void setUp() throws Exception {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        player = mock(ServerPlayer.class);
        var serverField = ServerPlayer.class.getField("server");
        serverField.setAccessible(true);
        serverField.set(player, server);
        driver = new FakeDriver();
        session = new RefinedStorageSession(driver,
                new StorageReference(RefinedStorageIds.BACKEND, "test"), (p, ref, stack) -> {});
    }

    @Test
    void consecutiveReadsSeeExternalChangesAndRecheckPermissionsWithoutTickAdvance() {
        driver.items.add(new ItemStack(Items.IRON_INGOT, 8));
        assertEquals(8, session.snapshotItems(player).snapshot().orElseThrow().items().get(0).amount());
        driver.items.get(0).setCount(3);
        assertEquals(3, session.snapshotItems(player).snapshot().orElseThrow().items().get(0).amount());
        driver.allowed = false;
        assertEquals(StorageSnapshotStatus.DENIED, session.snapshotItems(player).status());
        driver.allowed = true;
        driver.available = false;
        assertEquals(StorageSnapshotStatus.UNAVAILABLE, session.snapshotItems(player).status());
        assertEquals(2, driver.reads);
    }

    @Test
    void filteredPrecheckReadsAllRequestedVariantsAndStillRechecksPermissions() {
        driver.items.addAll(List.of(namedIron("first", 2), namedIron("second", 3),
                new ItemStack(Items.GOLD_INGOT, 9)));
        var types = Set.of(Items.IRON_INGOT);
        var result = session.snapshotItems(player, types).snapshot().orElseThrow();
        assertEquals(types, driver.lastTypes);
        assertEquals(2, result.items().size());
        driver.items.get(1).setCount(1);
        assertEquals(3, session.snapshotItems(player, types).snapshot().orElseThrow().items().stream()
                .mapToLong(StoredItem::amount).sum());
        driver.allowed = false;
        assertEquals(StorageSnapshotStatus.DENIED, session.snapshotItems(player, types).status());
        assertEquals(2, driver.reads);
    }

    @Test
    void filteredExtractionPreservesVariantOrderAndUsesFreshCounts() {
        ItemStack first = namedIron("first", 2);
        ItemStack second = namedIron("second", 4);
        ItemStack unrelated = new ItemStack(Items.BREAD);
        unrelated.getOrCreateTag().putDouble("invalid", Double.NaN);
        driver.items.addAll(List.of(first, unrelated, second));

        var result = session.extractMatching(player, Ingredient.of(Items.IRON_INGOT), 5, false);
        assertEquals(StorageOperationStatus.SUCCESS, result.status());
        assertEquals(Set.of(Items.IRON_INGOT), driver.lastTypes);
        assertEquals(List.of("first", "second"), result.extractedStacks().stream()
                .map(stack -> stack.getTag().getString("variant")).toList());
        assertEquals(List.of(2, 3), result.extractedStacks().stream().map(ItemStack::getCount).toList());
        assertEquals(1, second.getCount());

        var next = session.extractMatching(player, Ingredient.of(Items.IRON_INGOT), 2, false);
        assertEquals(StorageOperationStatus.PARTIAL, next.status());
        assertEquals(1, next.transferredAmount().orElseThrow());
        assertEquals(2, driver.reads);
    }

    @Test
    void customIngredientCanMatchOutsideDisplayItems() {
        driver.items.addAll(List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT, 2)));
        Ingredient custom = new Ingredient(Stream.of(new Ingredient.ItemValue(new ItemStack(Items.IRON_INGOT)))) {
            @Override public boolean test(ItemStack stack) { return stack.is(Items.GOLD_INGOT); }
        };
        var result = session.extractMatching(player, custom, 2, false);
        assertNull(driver.lastTypes);
        assertEquals(StorageOperationStatus.SUCCESS, result.status());
        assertTrue(result.extractedStacks().get(0).is(Items.GOLD_INGOT));
        assertEquals(1, driver.items.get(0).getCount());
    }

    @Test
    void strictAndPartialNbtStillUseTheOriginalPredicateAndSimulationDoesNotMutate() {
        ItemStack first = namedIron("first", 3);
        ItemStack second = namedIron("second", 4);
        driver.items.addAll(List.of(first, second));
        Ingredient strict = net.minecraftforge.common.crafting.StrictNBTIngredient.of(second);
        var simulated = session.extractMatching(player, strict, 2, true);
        assertEquals(2, simulated.transferredAmount().orElseThrow());
        assertEquals(4, second.getCount());
        var partial = net.minecraftforge.common.crafting.PartialNBTIngredient.of(Items.IRON_INGOT, second.getTag());
        assertEquals(2, session.extractMatching(player, partial, 2, false).transferredAmount().orElseThrow());
        assertEquals(3, first.getCount());
        assertEquals(2, second.getCount());
    }

    @Test
    void failureAfterOneVariantPreservesConfirmedExtractionAndNextReadIsFresh() {
        driver.items.addAll(List.of(namedIron("first", 2), namedIron("second", 3)));
        driver.failVariant = "second";
        var failed = session.extractMatching(player, Ingredient.of(Items.IRON_INGOT), 5, false);
        assertEquals(StorageOperationStatus.INDETERMINATE, failed.status());
        assertEquals(2, failed.extractedStacks().stream().mapToInt(ItemStack::getCount).sum());
        var fresh = session.snapshotItems(player).snapshot().orElseThrow();
        assertEquals(3, fresh.items().stream().mapToLong(StoredItem::amount).sum());
        assertEquals(2, driver.reads);
    }

    @Test
    void exactExtractionDoesNotBuildSnapshotAndDeniedMatchingDoesNotReadStorage() {
        driver.items.add(new ItemStack(Items.IRON_INGOT, 4));
        var key = session.itemKey(new ItemStack(Items.IRON_INGOT));
        assertEquals(2, session.extractExact(player, key, 2, false).transferredAmount().orElseThrow());
        assertEquals(0, driver.reads);
        driver.allowed = false;
        assertEquals(StorageOperationStatus.DENIED,
                session.extractMatching(player, Ingredient.of(Items.IRON_INGOT), 1, false).status());
        assertEquals(0, driver.reads);
    }

    private static ItemStack namedIron(String variant, int count) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT, count);
        stack.getOrCreateTag().putString("variant", variant);
        return stack;
    }

    private static final class FakeDriver implements RefinedStorageDriver {
        final List<ItemStack> items = new ArrayList<>();
        boolean available = true;
        boolean allowed = true;
        int reads;
        Set<Item> lastTypes;
        String failVariant;

        @Override public boolean isAvailable() { return available; }
        @Override public boolean hasPermission(ServerPlayer player, StoragePermission permission) { return allowed; }
        @Override public RefinedStorageSnapshotRead snapshotItems() { return snapshotItems(null); }
        @Override public RefinedStorageSnapshotRead snapshotItems(Set<Item> types) {
            reads++;
            lastTypes = types;
            return RefinedStorageSnapshotRead.available(items.stream()
                    .filter(stack -> types == null || types.contains(stack.getItem())).toList());
        }
        @Override public ItemStack extract(ItemStack template, int amount, boolean simulate) {
            if (!simulate && template.hasTag() && template.getTag().getString("variant").equals(failVariant)) {
                throw new IllegalStateException("native extraction failed");
            }
            for (ItemStack stored : items) {
                if (!stored.isEmpty() && ItemStack.isSameItemSameTags(stored, template)) {
                    ItemStack result = stored.copyWithCount(Math.min(amount, stored.getCount()));
                    if (!simulate) stored.shrink(result.getCount());
                    return result;
                }
            }
            return ItemStack.EMPTY;
        }
        @Override public ItemStack insert(ItemStack stack, boolean simulate) { return stack.copy(); }
        @Override public void recordInsertion(ServerPlayer player, ItemStack accepted) {}
    }
}
