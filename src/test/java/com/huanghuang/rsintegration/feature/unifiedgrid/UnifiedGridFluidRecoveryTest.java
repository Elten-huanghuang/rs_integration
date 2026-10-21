package com.huanghuang.rsintegration.feature.unifiedgrid;

import com.huanghuang.rsintegration.mods.ironsspellbooks.FluidRefundItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnifiedGridFluidRecoveryTest extends BootstrapTest {
    @Test
    void reloadKeepsOwnerAndRawFluidUntilClaimed() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UnifiedGridFluidRecovery original = new UnifiedGridFluidRecovery();
        original.retainRaw(owner, water(1000), "v1|minecraft:overworld@1,2,3");
        original.retainRaw(other, water(200), null);
        UnifiedGridFluidRecovery loaded = new UnifiedGridFluidRecovery(original.save(new CompoundTag()));

        ServerPlayer player = player(owner);
        Inventory inventory = player.getInventory();
        try (MockedStatic<FluidRefundItem> items = mockStatic(FluidRefundItem.class)) {
            items.when(() -> FluidRefundItem.create(any(CompoundTag.class),
                    eq("v1|minecraft:overworld@1,2,3")))
                    .thenReturn(new ItemStack(Items.PAPER));
            assertEquals(1, loaded.claim(player));
        }
        verify(inventory).add(any(ItemStack.class));
        ListTag pending = loaded.save(new CompoundTag()).getList("pending", Tag.TAG_COMPOUND);
        assertEquals(1, pending.size());
        assertEquals(other, pending.getCompound(0).getUUID("owner"));
        assertTrue(loaded.isDirty());
    }

    @Test
    void missingFluidRegistryIsKeptAsRawDataForTheRefundItem() {
        UUID owner = UUID.randomUUID();
        UnifiedGridFluidRecovery recovery = new UnifiedGridFluidRecovery();
        CompoundTag missing = new CompoundTag();
        missing.putString("FluidName", "missing_mod:stored_fluid");
        missing.putInt("Amount", 250);
        recovery.retainRaw(owner, missing, null);
        UnifiedGridFluidRecovery restarted = new UnifiedGridFluidRecovery(recovery.save(new CompoundTag()));
        assertEquals(missing, restarted.save(new CompoundTag())
                .getList("pending", Tag.TAG_COMPOUND).getCompound(0).getCompound("fluid"));
    }

    @Test
    void batchRefundsMergeWithoutLosingAmountOrTags() {
        UUID owner = UUID.randomUUID();
        UnifiedGridFluidRecovery recovery = new UnifiedGridFluidRecovery();
        FluidStack tagged = new FluidStack(Fluids.WATER, 250);
        tagged.getOrCreateTag().putString("variant", "original");
        CompoundTag original = tagged.writeToNBT(new CompoundTag());
        for (int i = 0; i < 1000; i++) {
            assertEquals(i == 0, recovery.retainRaw(owner, original,
                    "v1|minecraft:overworld@1,2,3"));
        }
        ListTag entries = recovery.save(new CompoundTag()).getList("pending", Tag.TAG_COMPOUND);
        assertEquals(1, entries.size());
        assertEquals(250_000, entries.getCompound(0).getCompound("fluid").getInt("Amount"));
        assertEquals("original", entries.getCompound(0).getCompound("fluid")
                .getCompound("Tag").getString("variant"));
    }

    @Test
    void mergedAmountsSplitAtIntegerLimit() {
        UUID owner = UUID.randomUUID();
        UnifiedGridFluidRecovery recovery = new UnifiedGridFluidRecovery();
        recovery.retain(owner, new FluidStack(Fluids.WATER, Integer.MAX_VALUE - 100));
        recovery.retain(owner, new FluidStack(Fluids.WATER, 250));
        ListTag saved = recovery.save(new CompoundTag()).getList("pending", Tag.TAG_COMPOUND);
        assertEquals(2, saved.size());
        assertEquals(Integer.MAX_VALUE, saved.getCompound(0).getCompound("fluid").getInt("Amount"));
        assertEquals(150, saved.getCompound(1).getCompound("fluid").getInt("Amount"));
    }

    @Test
    void manualClaimIsBoundedAndLeavesRemainingRefundsInTheSave() {
        UUID owner = UUID.randomUUID();
        UnifiedGridFluidRecovery recovery = new UnifiedGridFluidRecovery();
        for (int i = 0; i < 40; i++) {
            CompoundTag distinct = water(100);
            distinct.putInt("variant", i);
            recovery.retainRaw(owner, distinct, null);
        }
        try (MockedStatic<FluidRefundItem> items = mockStatic(FluidRefundItem.class)) {
            items.when(() -> FluidRefundItem.create(any(CompoundTag.class), isNull()))
                    .thenAnswer(ignored -> new ItemStack(Items.PAPER));
            assertEquals(32, recovery.claim(player(owner)));
        }
        assertEquals(8, recovery.save(new CompoundTag()).getList("pending", Tag.TAG_COMPOUND).size());
    }

    @Test
    void fullInventoryKeepsAllPendingRefunds() {
        UUID owner = UUID.randomUUID();
        UnifiedGridFluidRecovery recovery = new UnifiedGridFluidRecovery();
        recovery.retain(owner, new FluidStack(Fluids.WATER, 1000));
        ServerPlayer player = player(owner);
        when(player.getInventory().getFreeSlot()).thenReturn(-1);
        assertEquals(0, recovery.claim(player));
        assertFalse(recovery.save(new CompoundTag()).getList("pending", Tag.TAG_COMPOUND).isEmpty());
    }

    private static ServerPlayer player(UUID owner) {
        ServerPlayer player = mock(ServerPlayer.class);
        Inventory inventory = mock(Inventory.class);
        when(player.getUUID()).thenReturn(owner);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getFreeSlot()).thenReturn(0);
        when(inventory.add(any(ItemStack.class))).thenReturn(true);
        return player;
    }

    private static CompoundTag water(int amount) {
        return new FluidStack(Fluids.WATER, amount).writeToNBT(new CompoundTag());
    }
}
