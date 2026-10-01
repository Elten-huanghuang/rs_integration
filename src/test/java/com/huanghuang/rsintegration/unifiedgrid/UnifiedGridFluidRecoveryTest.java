package com.huanghuang.rsintegration.unifiedgrid;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnifiedGridFluidRecoveryTest extends BootstrapTest {
    @Test void reloadKeepsOwnershipAndPartialRemainderUntilNetworkAcceptsIt() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UnifiedGridFluidRecovery original = new UnifiedGridFluidRecovery();
        original.retain(owner, water(1000));
        original.retain(other, water(200));
        UnifiedGridFluidRecovery loaded = new UnifiedGridFluidRecovery(original.save(new CompoundTag()));
        INetwork network = mock(INetwork.class);
        when(network.insertFluid(any(), eq(1000), eq(Action.PERFORM))).thenReturn(water(400));
        loaded.retry(owner, network);
        verify(network, times(1)).insertFluid(any(), eq(1000), eq(Action.PERFORM));
        CompoundTag saved = loaded.save(new CompoundTag());
        assertEquals(400, amount(saved, 0));
        assertEquals(other, saved.getList("pending", Tag.TAG_COMPOUND).getCompound(1).getUUID("owner"));
        assertTrue(loaded.isDirty());

        UnifiedGridFluidRecovery restarted = new UnifiedGridFluidRecovery(saved);
        when(network.insertFluid(any(), eq(400), eq(Action.PERFORM))).thenReturn(FluidStack.EMPTY);
        restarted.retry(owner, network);
        saved = restarted.save(new CompoundTag());
        assertEquals(1, saved.getList("pending", Tag.TAG_COMPOUND).size());
        assertEquals(200, amount(saved, 0));
        assertEquals(other, saved.getList("pending", Tag.TAG_COMPOUND).getCompound(0).getUUID("owner"));
    }

    @Test void rejectedOrMissingFluidKeepsOriginalDataAcrossSaves() {
        UUID owner = UUID.randomUUID();
        UnifiedGridFluidRecovery recovery = new UnifiedGridFluidRecovery();
        recovery.retain(owner, water(1000));
        INetwork network = mock(INetwork.class);
        when(network.insertFluid(any(), anyInt(), eq(Action.PERFORM))).thenReturn(water(1000));
        CompoundTag original = recovery.save(new CompoundTag());
        recovery.retry(owner, network);
        assertEquals(original, recovery.save(new CompoundTag()));

        CompoundTag missing = original.copy();
        missing.getList("pending", Tag.TAG_COMPOUND).getCompound(0)
                .getCompound("fluid").putString("FluidName", "missing_mod:stored_fluid");
        UnifiedGridFluidRecovery loaded = new UnifiedGridFluidRecovery(missing);
        clearInvocations(network);
        loaded.retry(owner, network);
        verifyNoInteractions(network);
        assertEquals(missing, loaded.save(new CompoundTag()));
    }

    @Test void retriesAreBoundedPerTick() {
        UUID owner = UUID.randomUUID();
        UnifiedGridFluidRecovery recovery = new UnifiedGridFluidRecovery();
        for (int i = 0; i < 12; i++) recovery.retain(owner, water(1000));
        INetwork network = mock(INetwork.class);
        when(network.insertFluid(any(), anyInt(), eq(Action.PERFORM))).thenReturn(FluidStack.EMPTY);
        recovery.retry(owner, network);
        verify(network, times(8)).insertFluid(any(), eq(1000), eq(Action.PERFORM));
        assertEquals(4, recovery.save(new CompoundTag()).getList("pending", Tag.TAG_COMPOUND).size());
    }

    private static int amount(CompoundTag saved, int index) {
        return FluidStack.loadFluidStackFromNBT(saved.getList("pending", Tag.TAG_COMPOUND)
                .getCompound(index).getCompound("fluid")).getAmount();
    }

    private static FluidStack water(int amount) { return new FluidStack(Fluids.WATER, amount); }
}
