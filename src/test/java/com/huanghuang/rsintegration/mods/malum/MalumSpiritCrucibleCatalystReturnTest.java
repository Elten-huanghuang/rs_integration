package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

class MalumSpiritCrucibleCatalystReturnTest extends BootstrapTest {

    @Test
    void graphTakesTheActualCatalystWithNbtInsteadOfReturningItToStorage() throws Exception {
        MalumSpiritCrucibleBatchDelegate delegate = catalystDelegate(InputConsumption.catalyst());
        ItemStack catalyst = new ItemStack(Items.STICK);
        catalyst.getOrCreateTag().putInt("blood", 125);
        ItemStackHandler inventory = new ItemStackHandler(1);
        inventory.setStackInSlot(0, catalyst);
        set(delegate, "invCatalyst", inventory);
        CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class);
        delegate.setStorageEndpoint(endpoint);
        set(delegate, "catalystReturnEndpoint", endpoint);
        ServerPlayer player = mock(ServerPlayer.class);

        List<ItemStack> recovered = delegate.collectReusableMaterials(player);
        delegate.onBatchFinished(player);
        delegate.releaseReusableMaterials(player);

        assertEquals(1, recovered.size());
        assertTrue(ItemStack.isSameItemSameTags(catalyst, recovered.get(0)));
        assertTrue(inventory.getStackInSlot(0).isEmpty());
        assertTrue(delegate.collectReusableMaterials(player).isEmpty());
        verifyNoInteractions(endpoint);
    }

    @Test
    void returnsRemainingDurabilityAndUnusedReplacementStockOnlyOnce() throws Exception {
        MalumSpiritCrucibleBatchDelegate delegate = catalystDelegate(InputConsumption.durability(1));
        ItemStack catalyst = new ItemStack(Items.IRON_PICKAXE);
        catalyst.setDamageValue(12);
        ItemStackHandler inventory = new ItemStackHandler(1);
        inventory.setStackInSlot(0, catalyst);
        set(delegate, "invCatalyst", inventory);
        set(delegate, "catalystStock", new ItemStack(Items.IRON_PICKAXE));
        ServerPlayer player = mock(ServerPlayer.class);

        List<ItemStack> recovered = delegate.collectReusableMaterials(player);

        assertEquals(2, recovered.size());
        assertEquals(12, recovered.get(0).getDamageValue());
        assertEquals(0, recovered.get(1).getDamageValue());
        assertTrue(inventory.getStackInSlot(0).isEmpty());
        assertTrue(delegate.collectReusableMaterials(player).isEmpty());
    }

    @Test
    void missingPhysicalCatalystDoesNotProduceATemplateReturn() throws Exception {
        MalumSpiritCrucibleBatchDelegate delegate = catalystDelegate(InputConsumption.catalyst());
        set(delegate, "invCatalyst", new ItemStackHandler(1));

        assertTrue(delegate.collectReusableMaterials(mock(ServerPlayer.class)).isEmpty());
    }

    @Test
    void consumedInputIsLeftForNormalMachineCleanup() throws Exception {
        MalumSpiritCrucibleBatchDelegate delegate = catalystDelegate(InputConsumption.consumed());
        ItemStackHandler inventory = new ItemStackHandler(1);
        inventory.setStackInSlot(0, new ItemStack(Items.STICK));
        set(delegate, "invCatalyst", inventory);

        assertTrue(delegate.collectReusableMaterials(mock(ServerPlayer.class)).isEmpty());
        assertEquals(1, inventory.getStackInSlot(0).getCount());
    }

    @Test
    void replacementStockMovesToTheNextOperationWithoutLosingItsNbt() throws Exception {
        MalumSpiritCrucibleBatchDelegate previous = catalystDelegate(InputConsumption.durability(1));
        MalumSpiritCrucibleBatchDelegate next = catalystDelegate(InputConsumption.durability(1));
        ItemStackHandler inventory = new ItemStackHandler(1);
        ItemStack installed = new ItemStack(Items.IRON_PICKAXE);
        installed.setDamageValue(12);
        inventory.setStackInSlot(0, installed);
        Object machine = new Object();
        for (MalumSpiritCrucibleBatchDelegate delegate : List.of(previous, next)) {
            set(delegate, "crucibleBE", machine);
            set(delegate, "invCatalyst", inventory);
        }
        ItemStack stock = new ItemStack(Items.IRON_PICKAXE);
        stock.getOrCreateTag().putString("owner", "replacement");
        set(previous, "catalystStock", stock);
        set(previous, "catalystStockInitialized", true);

        assertTrue(previous.transferReusableMaterialsTo(next));
        assertTrue(previous.collectReusableMaterials(mock(ServerPlayer.class)).isEmpty());
        assertEquals(1, inventory.getStackInSlot(0).getCount());
        List<ItemStack> returned = next.collectReusableMaterials(mock(ServerPlayer.class));

        assertEquals(2, returned.size());
        assertEquals(12, returned.get(0).getDamageValue());
        assertEquals("replacement", returned.get(1).getTag().getString("owner"));
        assertTrue(previous.collectReusableMaterials(mock(ServerPlayer.class)).isEmpty());
        assertTrue(next.collectReusableMaterials(mock(ServerPlayer.class)).isEmpty());
    }

    @Test
    void differentMachineCannotClaimTheReplacementStock() throws Exception {
        MalumSpiritCrucibleBatchDelegate previous = catalystDelegate(InputConsumption.catalyst());
        MalumSpiritCrucibleBatchDelegate next = catalystDelegate(InputConsumption.catalyst());
        set(previous, "crucibleBE", new Object());
        set(next, "crucibleBE", new Object());
        set(previous, "invCatalyst", new ItemStackHandler(1));
        set(next, "invCatalyst", new ItemStackHandler(1));
        set(previous, "catalystStock", new ItemStack(Items.STICK));

        assertFalse(previous.transferReusableMaterialsTo(next));
        assertEquals(1, previous.collectReusableMaterials(mock(ServerPlayer.class)).size());
        assertTrue(next.collectReusableMaterials(mock(ServerPlayer.class)).isEmpty());
    }

    private static MalumSpiritCrucibleBatchDelegate catalystDelegate(InputConsumption consumption) {
        MalumSpiritCrucibleBatchDelegate delegate = spy(new MalumSpiritCrucibleBatchDelegate());
        doReturn(List.of(new IngredientSpec(Ingredient.of(Items.STICK), 1, consumption)))
                .when(delegate).getRequiredMaterials();
        return delegate;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = MalumSpiritCrucibleBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
