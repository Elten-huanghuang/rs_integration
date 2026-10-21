package com.huanghuang.rsintegration.crafting.endpoint;

import com.huanghuang.rsintegration.storage.disk.core.FrozenKey;
import com.huanghuang.rsintegration.storage.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.storage.disk.rs.UnifiedDiskManager;
import com.huanghuang.rsintegration.storage.disk.rs.UnifiedMountCoordinator;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyRsMatchingTest extends BootstrapTest {
    private INetwork network;
    private ServerPlayer player;
    private CraftStorageEndpoint endpoint;
    private ItemStack first;
    private ItemStack second;

    @BeforeEach
    void setUp() throws Exception {
        player = mock(ServerPlayer.class);
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        var field = ServerPlayer.class.getField("server");
        field.setAccessible(true);
        field.set(player, server);
        network = mock(INetwork.class, RETURNS_DEEP_STUBS);
        when(network.canRun()).thenReturn(true);
        when(network.getLevel().dimension()).thenReturn(Level.OVERWORLD);
        when(network.getPosition()).thenReturn(BlockPos.ZERO);
        when(network.getSecurityManager()).thenReturn(null);
        first = named("first", 2);
        second = named("second", 3);
        when(network.getItemStorageCache().getList().getStacks()).thenReturn(
                List.of(new StackListEntry<>(first), new StackListEntry<>(second)));
        when(network.extractItem(any(ItemStack.class), anyInt(), any(Action.class))).thenAnswer(call -> {
            ItemStack template = call.getArgument(0);
            int count = call.getArgument(1);
            ItemStack stored = ItemStack.isSameItemSameTags(template, first) ? first : second;
            int amount = Math.min(count, stored.getCount());
            return call.getArgument(2) == Action.SIMULATE
                    ? stored.copyWithCount(amount) : stored.split(amount);
        });
        endpoint = new LegacyRsCraftStorageEndpoint(network);
    }

    @Test
    void legacyMatchingPreservesNbtFragmentsAndRereadsExternalChanges() {
        var result = endpoint.extractMatching(player, Ingredient.of(Items.IRON_INGOT), 4, false);
        assertEquals(StorageOperationStatus.SUCCESS, result.status());
        assertEquals(List.of("first", "second"), result.extractedStacks().stream()
                .map(stack -> stack.getTag().getString("variant")).toList());
        assertEquals(List.of(2, 2), result.extractedStacks().stream().map(ItemStack::getCount).toList());
        second.setCount(5);
        assertEquals(5, endpoint.extractMatching(player, Ingredient.of(Items.IRON_INGOT), 5, false)
                .transferredAmount().orElseThrow());
    }

    @Test
    void legacyMatchingReturnsConfirmedFragmentsWhenLaterNativeExtractionThrows() {
        when(network.extractItem(argThat(stack -> stack != null && stack.hasTag()
                        && "second".equals(stack.getTag().getString("variant"))), anyInt(), eq(Action.PERFORM)))
                .thenThrow(new IllegalStateException("native failure"));
        var result = endpoint.extractMatching(player, Ingredient.of(Items.IRON_INGOT), 5, false);
        assertEquals(StorageOperationStatus.INDETERMINATE, result.status());
        assertEquals(2, result.extractedStacks().get(0).getCount());
        assertEquals("first", result.extractedStacks().get(0).getTag().getString("variant"));
        assertEquals(3, second.getCount());
    }

    @Test
    void legacySimulationPreservesInventory() {
        assertEquals(StorageOperationStatus.SUCCESS,
                endpoint.extractMatching(player, Ingredient.of(Items.IRON_INGOT), 5, true).status());
        assertEquals(2, first.getCount());
        assertEquals(3, second.getCount());
    }

    @Test
    void legacySnapshotSkipsNanFoodButKeepsOtherMaterials() {
        ItemStack bread = new ItemStack(Items.BREAD);
        CompoundTag dish = new CompoundTag();
        dish.putFloat("nutrition", Float.NaN);
        bread.getOrCreateTag().put("dish_attribute", dish);
        when(network.getItemStorageCache().getList().getStacks()).thenReturn(List.of(
                new StackListEntry<>(first), new StackListEntry<>(bread),
                new StackListEntry<>(second)));

        var result = endpoint.snapshot(player);

        assertTrue(result.successful());
        assertEquals(List.of("first", "second"), result.snapshot().orElseThrow().items().stream()
                .map(item -> item.stack().getTag().getString("variant")).toList());
        assertTrue(Float.isNaN(bread.getTag().getCompound("dish_attribute").getFloat("nutrition")));
    }

    @Test
    void legacySnapshotUsesExactUnifiedDiskAmountAboveInt() {
        ItemStack stone = new ItemStack(Items.STONE);
        long exact = 510_000_000_000L;
        when(network.getItemStorageCache().getList().getStacks()).thenReturn(
                List.of(new StackListEntry<>(stone)));
        UnifiedDiskCore disk = new UnifiedDiskCore(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(),
                new UnifiedDiskCore.Limits(8, 8, 1048576, 268435456));
        disk.insert(FrozenKey.item(stone), exact, true);
        UnifiedDiskManager manager = mock(UnifiedDiskManager.class);
        UnifiedMountCoordinator mounts = mock(UnifiedMountCoordinator.class);
        when(manager.mounts()).thenReturn(mounts);
        when(mounts.disksForNetwork(network)).thenReturn(List.of(disk));
        when(network.getLevel().getServer()).thenReturn(player.server);

        try (MockedStatic<UnifiedDiskManager> managers = mockStatic(UnifiedDiskManager.class)) {
            managers.when(() -> UnifiedDiskManager.existing(player.server)).thenReturn(manager);
            var result = endpoint.snapshot(player);
            assertEquals(exact, result.snapshot().orElseThrow().countExact(
                    endpoint.session().itemKey(stone)));
        }
    }

    private static ItemStack named(String variant, int count) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT, count);
        stack.getOrCreateTag().putString("variant", variant);
        return stack;
    }
}
