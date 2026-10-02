package com.huanghuang.rsintegration.mods.eidolon;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EidolonWaterSupplyTest extends BootstrapTest {
    @Test
    void dryHeatedCrucibleIsSelectableWithoutMutatingItsTank() {
        TestCrucible crucible = crucibleAbove(Blocks.FIRE.defaultBlockState());
        assertTrue(EidolonWaterSupply.isHeated(crucible));
        assertEquals(0, crucible.tank.getFluidAmount());
        assertEquals(0, crucible.changes);
    }

    @Test
    void coldCrucibleRemainsUnavailableEvenIfItsOldBoilingFlagIsTrue() {
        TestCrucible crucible = crucibleAbove(Blocks.STONE.defaultBlockState());
        crucible.boiling = true;
        assertFalse(EidolonWaterSupply.isHeated(crucible));
        assertEquals(0, crucible.tank.getFluidAmount());
    }

    @Test
    void partialWaterIsToppedUpToTheNativeFullBucketThreshold() {
        TestCrucible crucible = new TestCrucible();
        crucible.tank.fill(water(750), IFluidHandler.FluidAction.EXECUTE);
        assertFalse(crucible.hasWater);
        assertTrue(EidolonWaterSupply.ensureWater(crucible.tank, 250, null, null, crucible::setChanged));
        assertEquals(1000, crucible.tank.getFluidAmount());
        assertTrue(crucible.hasWater);
        assertEquals(1, crucible.changes);
    }

    @Test
    void existingFullBucketDoesNotRequireAStorageEndpoint() {
        TestCrucible crucible = new TestCrucible();
        crucible.tank.fill(water(1000), IFluidHandler.FluidAction.EXECUTE);
        assertTrue(EidolonWaterSupply.ensureWater(crucible.tank, 1000, null, null, crucible::setChanged));
        assertEquals(1000, crucible.tank.getFluidAmount());
        assertEquals(0, crucible.changes);
    }

    @Test
    void incompatibleExistingFluidIsNotDiscarded() {
        TestCrucible crucible = new TestCrucible();
        crucible.tank.fill(new FluidStack(Fluids.LAVA, 500), IFluidHandler.FluidAction.EXECUTE);
        assertFalse(EidolonWaterSupply.ensureWater(crucible.tank, 1000, null, null, crucible::setChanged));
        assertEquals(Fluids.LAVA, crucible.tank.getFluid().getFluid());
        assertEquals(500, crucible.tank.getFluidAmount());
        assertEquals(0, crucible.changes);
    }

    @Test
    void disabledFreeWaterCannotRefillWithoutRsWater() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        config.set("autoCrafting.freeWaterMachines", List.of());
        TestCrucible crucible = new TestCrucible();
        crucible.tank.fill(water(750), IFluidHandler.FluidAction.EXECUTE);
        try {
            RSIntegrationConfig.SERVER_SPEC.setConfig(config);
            assertFalse(EidolonWaterSupply.ensureWater(crucible.tank, 250, null, null, crucible::setChanged));
            assertEquals(750, crucible.tank.getFluidAmount());
            assertFalse(crucible.hasWater);
        } finally {
            RSIntegrationConfig.SERVER_SPEC.setConfig(null);
        }
    }

    private static TestCrucible crucibleAbove(BlockState below) {
        TestCrucible crucible = new TestCrucible();
        Level level = mock(Level.class);
        when(level.hasChunkAt(BlockPos.ZERO.below())).thenReturn(true);
        when(level.getBlockState(BlockPos.ZERO.below())).thenReturn(below);
        crucible.setLevel(level);
        return crucible;
    }

    private static FluidStack water(int amount) {
        return new FluidStack(Fluids.WATER, amount);
    }

    public static class TestCrucible extends BlockEntity {
        public static final List<Predicate<BlockState>> HOT_BLOCKS = List.of(state -> state.is(Blocks.FIRE));
        public boolean hasWater;
        public boolean boiling;
        int changes;
        final FluidTank tank = new FluidTank(1000) {
            @Override
            protected void onContentsChanged() {
                hasWater = getFluidAmount() == 1000;
            }
        };

        TestCrucible() {
            super(BlockEntityType.SIGN, BlockPos.ZERO, Blocks.OAK_SIGN.defaultBlockState());
        }

        @Override
        public void setChanged() {
            changes++;
        }
    }
}
