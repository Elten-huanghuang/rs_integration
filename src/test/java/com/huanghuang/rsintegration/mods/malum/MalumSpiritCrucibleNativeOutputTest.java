package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.batch.NativeItemOutput;
import com.huanghuang.rsintegration.crafting.batch.OperationLifecycle;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MalumSpiritCrucibleNativeOutputTest extends BootstrapTest {
    @Test
    void bonusOutputDoesNotCompleteBatchAndCancellationKeepsReceivedStacks() throws Exception {
        var delegate = new MalumSpiritCrucibleBatchDelegate();
        Recipe<?> recipe = mock(Recipe.class);
        Recipe<?> other = mock(Recipe.class);
        when(recipe.getId()).thenReturn(new ResourceLocation("test", "expected"));
        when(other.getId()).thenReturn(new ResourceLocation("test", "other"));
        set(delegate, "recipe", recipe);
        set(delegate, "expectedOutput", new ItemStack(Items.DIAMOND));
        set(delegate, "bufferedOperations", 2);
        var machine = mock(BlockEntity.class);
        set(delegate, "crucibleBE", machine);
        set(delegate, "craftStarted", true);
        ItemStack actual = new ItemStack(Items.DIAMOND);
        actual.getOrCreateTag().putString("runtime", "preserve");

        try (var session = TaskOutputReception.open()) {
            var lifecycle = new OperationLifecycle(delegate, session);
            delegate.bindProduction(session.writer());
            var outlet = new Outlet();
            delegate.armOutput(outlet);
            assertFalse(outlet.rsi$receiveNativeOutput(other, actual, true));
            assertFalse(outlet.rsi$receiveNativeOutput(recipe, actual, false));
            assertTrue(outlet.rsi$receiveNativeOutput(recipe, actual, true));
            assertTrue(outlet.rsi$receiveNativeOutput(recipe, actual, false));
            assertFalse(delegate.isMachineCraftFinished(null, machine));

            assertTrue(outlet.rsi$receiveNativeOutput(recipe, actual, true));
            assertTrue(outlet.rsi$receiveNativeOutput(recipe, actual, false));
            assertTrue(delegate.isMachineCraftFinished(null, machine));
            assertFalse(outlet.rsi$receiveNativeOutput(recipe, actual, true));
            assertFalse(outlet.rsi$receiveNativeOutput(recipe, actual, false));

            List<ItemStack> recovered = lifecycle.retireHolding();
            assertEquals(1, recovered.size());
            assertEquals(4, recovered.get(0).getCount());
            assertEquals(actual.getTag(), recovered.get(0).getTag());
            assertTrue(lifecycle.retireHolding().isEmpty());
            assertFalse(outlet.rsi$receiveNativeOutput(recipe, actual, true));
            delegate.releasePreparationResources();
            assertNull(outlet.rsi$getOutputReceiver());
        }
    }

    @Test
    void cancellationRecoversOnlyRemainingTaskSpirits() throws Exception {
        var delegate = new MalumSpiritCrucibleBatchDelegate();
        var machine = mock(BlockEntity.class);
        var spirits = new ItemStackHandler(4);
        var catalyst = new ItemStackHandler(1);
        set(delegate, "crucibleBE", machine);
        set(delegate, "invSpirits", spirits);
        set(delegate, "invCatalyst", catalyst);
        Field shared = delegate.getClass().getSuperclass().getDeclaredField("usingSharedLedger");
        shared.setAccessible(true);
        shared.setBoolean(delegate, true);

        delegate.installInputs(List.of(new ItemStack(Items.QUARTZ, 4),
                new ItemStack(Items.AMETHYST_SHARD)), ItemStack.EMPTY);
        spirits.getStackInSlot(0).shrink(2);
        var replacement = new ItemStack(Items.AMETHYST_SHARD);
        spirits.setStackInSlot(1, replacement);
        delegate.clearMachineState(machine, null);

        var recovered = delegate.failureRecoveredInputs();
        assertEquals(1, recovered.size());
        assertTrue(recovered.get(0).is(Items.QUARTZ));
        assertEquals(2, recovered.get(0).getCount());
        assertSame(replacement, spirits.getStackInSlot(1));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class Outlet implements NativeItemOutput {
        private Receiver receiver;
        @Override public Receiver rsi$getOutputReceiver() { return receiver; }
        @Override public void rsi$setOutputReceiver(Receiver receiver) { this.receiver = receiver; }
    }
}
