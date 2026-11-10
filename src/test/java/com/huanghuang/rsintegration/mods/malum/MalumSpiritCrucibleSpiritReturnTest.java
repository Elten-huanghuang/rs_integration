package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MalumSpiritCrucibleSpiritReturnTest extends BootstrapTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bonusOutputReturnsOnlyTheRemainingSpiritsOnce(boolean sharedLedger) throws Exception {
        MalumSpiritCrucibleBatchDelegate delegate = delegate();
        ItemStackHandler spirits = new ItemStackHandler(4);
        ItemStack first = new ItemStack(Items.AMETHYST_SHARD, 3);
        first.getOrCreateTag().putString("spirit", "earthen");
        ItemStack second = new ItemStack(Items.QUARTZ, 6);
        second.getOrCreateTag().putString("spirit", "arcane");
        spirits.setStackInSlot(0, first.copy());
        spirits.setStackInSlot(2, second.copy());
        set(delegate, "invSpirits", spirits);
        setSharedLedger(delegate, sharedLedger);
        set(delegate, "bufferedOperations", 8);
        ServerPlayer player = mock(ServerPlayer.class);
        List<ItemStack> returned = new ArrayList<>();
        CraftStorageEndpoint endpoint = acceptingEndpoint(player, returned);
        delegate.setStorageEndpoint(endpoint);

        delegate.onBatchFinished(player);
        delegate.onBatchFinished(player);
        delegate.releaseReusableMaterials(player);

        assertEquals(2, returned.size());
        assertEquals(3, returned.get(0).getCount());
        assertEquals(6, returned.get(1).getCount());
        assertTrue(ItemStack.isSameItemSameTags(first, returned.get(0)));
        assertTrue(ItemStack.isSameItemSameTags(second, returned.get(1)));
        for (int slot = 0; slot < spirits.getSlots(); slot++) {
            assertTrue(spirits.getStackInSlot(slot).isEmpty());
        }
    }

    @Test
    void offlineCleanupKeepsSpiritsAndCanRetryWithTheOriginalEndpoint() throws Exception {
        MalumSpiritCrucibleBatchDelegate delegate = delegate();
        ItemStackHandler spirits = new ItemStackHandler(4);
        ItemStack remaining = new ItemStack(Items.AMETHYST_SHARD, 2);
        spirits.setStackInSlot(1, remaining.copy());
        set(delegate, "invSpirits", spirits);
        setSharedLedger(delegate, true);
        ServerPlayer player = mock(ServerPlayer.class);
        List<ItemStack> returned = new ArrayList<>();
        CraftStorageEndpoint endpoint = acceptingEndpoint(player, returned);
        delegate.setStorageEndpoint(endpoint);
        set(delegate, "catalystReturnEndpoint", endpoint);

        delegate.onBatchFinished(null);

        assertEquals(2, spirits.getStackInSlot(1).getCount());
        assertTrue(ItemStack.isSameItemSameTags(remaining, spirits.getStackInSlot(1)));
        verifyNoInteractions(endpoint);

        delegate.onBatchFinished(player);
        delegate.onBatchFinished(player);

        assertEquals(1, returned.size());
        assertEquals(2, returned.get(0).getCount());
        assertTrue(spirits.getStackInSlot(1).isEmpty());
    }

    private static MalumSpiritCrucibleBatchDelegate delegate() {
        MalumSpiritCrucibleBatchDelegate delegate = spy(new MalumSpiritCrucibleBatchDelegate());
        doReturn(List.of(new IngredientSpec(
                Ingredient.of(Items.STICK), 1, InputConsumption.catalyst())))
                .when(delegate).getRequiredMaterials();
        return delegate;
    }

    private static CraftStorageEndpoint acceptingEndpoint(
            ServerPlayer player, List<ItemStack> returned) {
        CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class);
        when(endpoint.insert(eq(player), any(ItemStack.class), eq(false)))
                .thenAnswer(call -> {
                    ItemStack stack = call.getArgument(1);
                    returned.add(stack.copy());
                    return StorageOperationResult.inserted(
                            StorageOperationMode.PERFORM, stack, ItemStack.EMPTY);
                });
        return endpoint;
    }

    private static void setSharedLedger(
            MalumSpiritCrucibleBatchDelegate delegate, boolean sharedLedger) throws Exception {
        Field field = MalumSpiritCrucibleBatchDelegate.class.getSuperclass()
                .getDeclaredField("usingSharedLedger");
        field.setAccessible(true);
        field.set(delegate, sharedLedger);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = MalumSpiritCrucibleBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
