package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotaniaResonantManaPoolContractTest extends BootstrapTest {
    @Test
    void signedManaReceiverCallsSupportBothBurstInputAndSparkOutput() {
        BotaniaResonantManaPoolBlockEntity pool = pool();

        pool.receiveMana(40);
        pool.receiveMana(-60);

        verify(pool).insert(40);
        verify(pool).extract(60);
    }

    @Test
    void negativeMinimumIntegerCannotOverflowIntoPositiveMana() {
        BotaniaResonantManaPoolBlockEntity pool = pool();

        pool.receiveMana(Integer.MIN_VALUE);

        verify(pool).extract(100);
    }

    @Test
    void nativeWandToggleEnablesSparkOutputAndPersists() {
        BotaniaResonantManaPoolBlockEntity pool = pool();
        pool.setLevel(mock(Level.class));

        pool.onUsedByWand(null, ItemStack.EMPTY, Direction.UP);

        assertTrue(pool.isOutputtingPower());
        CompoundTag saved = new CompoundTag();
        pool.saveAdditional(saved);
        BotaniaResonantManaPoolBlockEntity restored = pool();
        restored.load(saved);
        assertTrue(restored.isOutputtingPower());
    }

    private static BotaniaResonantManaPoolBlockEntity pool() {
        BotaniaResonantManaPoolBlockEntity pool = spy(new BotaniaResonantManaPoolBlockEntity(
                BlockEntityType.CHEST, BlockPos.ZERO, Blocks.CHEST.defaultBlockState()));
        doReturn(1_000).when(pool).resourceCapacity();
        doReturn(100).when(pool).getCurrentMana();
        doReturn(0).when(pool).insert(anyInt());
        doReturn(0).when(pool).extract(anyInt());
        return pool;
    }
}
