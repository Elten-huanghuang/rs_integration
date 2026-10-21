package com.huanghuang.rsintegration.mods.goety.magic;

import com.huanghuang.rsintegration.magic.MagicResourceInteraction;
import com.huanghuang.rsintegration.magic.MagicGridItemTransfer;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.storage.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.apiimpl.util.QuantityFormatter;
import com.wintercogs.beyonddimensions.api.storage.handler.impl.AbstractUnorderedStackHandler;
import com.wintercogs.beyonddimensions.api.storage.handler.impl.UnorderedStackHandlerRemoveZero;
import com.wintercogs.beyonddimensions.api.storage.key.StackKeyRegistry;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModList;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class GoetySoulStorageTest extends BootstrapTest {
    @Test
    void totemTransfersRespectDiskAndTotemLimits() {
        UnifiedDiskCore disk = new UnifiedDiskCore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new UnifiedDiskCore.Limits(8, 8, 1048576, 268435456));
        ItemStack totem = totem(Integer.MAX_VALUE, 1_000);
        long accepted = disk.insertGoetySouls(GoetySoulTotem.souls(totem), true);
        GoetySoulTotem.set(totem, GoetySoulTotem.souls(totem) - Math.toIntExact(accepted));
        assertEquals(1_000, accepted);
        assertEquals(0, GoetySoulTotem.souls(totem));
        assertEquals(1_000, disk.goetySouls());

        ItemStack target = totem(10, 7);
        int requested = (int) Math.min(100, GoetySoulTotem.space(target));
        long withdrawn = disk.extractGoetySouls(requested, true);
        int inserted = GoetySoulTotem.insert(target, Math.toIntExact(withdrawn), true);
        disk.insertGoetySouls(withdrawn - inserted, true);
        assertEquals(3, inserted);
        assertEquals(10, GoetySoulTotem.souls(target));
        assertEquals(997, disk.goetySouls());
        assertEquals(Items.TOTEM_OF_UNDYING, totem.getItem());
    }

    @Test
    void unifiedDiskSupportsAmountsBeyondSignedInteger() {
        UnifiedDiskCore disk = new UnifiedDiskCore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new UnifiedDiskCore.Limits(8, 8, 1048576, 268435456));
        long aboveInt = Integer.MAX_VALUE + 123L;
        assertEquals(aboveInt, disk.insertGoetySouls(aboveInt, true));
        assertEquals(aboveInt, disk.goetySouls());
    }

    @Test
    void beyondDimensionsUsesTheSameSoulKey() {
        ModList mods = mock(ModList.class);
        when(mods.isLoaded("goety")).thenReturn(true);
        when(mods.isLoaded("beyonddimensions")).thenReturn(true);
        try (MockedStatic<ModList> loading = mockStatic(ModList.class)) {
            loading.when(ModList::get).thenReturn(mods);
            GoetySoulKeys.register();
            assertEquals(GoetySoulStackKey.INSTANCE, StackKeyRegistry.getType(GoetySoulStackKey.ID));
            var storage = new UnorderedStackHandlerRemoveZero(
                    AbstractUnorderedStackHandler.UiTimestampPolicy.NONE);
            ItemStack source = totem(100, 40);
            assertEquals(40, BeyondDimensionsSoulTransfer.deposit(storage, source, 100, true));
            assertEquals(40, storage.getStackByKey(GoetySoulStackKey.INSTANCE).amount());

            ItemStack target = totem(100, 0);
            assertEquals(25, BeyondDimensionsSoulTransfer.withdraw(storage, target, 25, false));
            assertEquals(0, GoetySoulTotem.souls(target));
            assertEquals(25, BeyondDimensionsSoulTransfer.withdraw(storage, target, 25, true));
            assertEquals(25, GoetySoulTotem.souls(target));
            assertEquals(15, storage.getStackByKey(GoetySoulStackKey.INSTANCE).amount());
        }
    }

    @Test
    void beyondDimensionsUsesRsRightClickToDeposit() {
        assertFalse(MagicResourceInteraction.depositsFromHeldItem(0));
        assertTrue(MagicResourceInteraction.depositsFromHeldItem(1));
    }

    @Test
    void rsSoulQuantityUsesThousandsNotBuckets() {
        assertEquals("40K", new QuantityFormatter().formatWithUnits(40_000));
    }

    @Test
    void directDiskInsertionSupportsArcaOverflow() {
        UnifiedDiskCore disk = new UnifiedDiskCore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new UnifiedDiskCore.Limits(8, 8, 1048576, 268435456));
        assertEquals(500, disk.insertGoetySouls(500, true));
        assertEquals(500, disk.goetySouls());
    }

    @Test
    void genericGridTransferFindsSoulTotemThroughOptionalBridge() {
        ItemStack totem = totem(100, 25);
        ModList mods = mock(ModList.class);
        when(mods.isLoaded("goety")).thenReturn(true);
        ForgeConfigSpec.BooleanValue previousGoety = RSIntegrationConfig.ENABLE_GOETY;
        RSIntegrationConfig.ENABLE_GOETY = null;

        try (MockedStatic<ModList> loading = mockStatic(ModList.class)) {
            loading.when(ModList::get).thenReturn(mods);
            assertEquals(MagicResourceKind.SOUL, MagicGridItemTransfer.kindOf(totem));
            assertEquals(25, MagicGridItemTransfer.read(MagicResourceKind.SOUL, totem));
            assertEquals(75, MagicGridItemTransfer.space(MagicResourceKind.SOUL, totem));
            assertEquals(-15, MagicGridItemTransfer.write(MagicResourceKind.SOUL, totem, 10));
            assertEquals(10, GoetySoulTotem.souls(totem));
        } finally {
            RSIntegrationConfig.ENABLE_GOETY = previousGoety;
        }
    }

    private static ItemStack totem(int capacity, int souls) {
        ItemStack stack = GoetySoulTotem.markTestTotem(new ItemStack(Items.TOTEM_OF_UNDYING));
        stack.getOrCreateTag().putInt(GoetySoulTotem.MAX_SOULS, capacity);
        stack.getOrCreateTag().putInt(GoetySoulTotem.SOULS, souls);
        return stack;
    }
}
