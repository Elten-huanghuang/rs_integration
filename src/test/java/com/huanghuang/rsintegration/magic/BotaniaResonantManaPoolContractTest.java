package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.util.LazyOptional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vazkii.botania.api.BotaniaForgeCapabilities;
import vazkii.botania.api.block.Wandable;
import vazkii.botania.api.mana.ManaReceiver;
import vazkii.botania.api.mana.spark.SparkAttachable;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotaniaResonantManaPoolContractTest extends BootstrapTest {
    @BeforeAll
    static void initializeBotaniaCapabilities() {
        try (MockedStatic<CapabilityManager> manager = mockStatic(CapabilityManager.class)) {
            manager.when(() -> CapabilityManager.get(any(CapabilityToken.class)))
                    .thenAnswer(call -> mock(Capability.class));
            Class.forName("vazkii.botania.api.BotaniaForgeCapabilities");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("无法初始化 Botania 测试能力", exception);
        }
    }

    @Test
    void signedManaReceiverCallsSupportBothBurstInputAndSparkOutput() {
        BotaniaResonantManaPoolBlockEntity pool = pool();

        pool.receiveMana(40);
        pool.receiveMana(-60);

        verify(pool).insert(40);
        verify(pool).extract(60);
    }

    @Test
    void unboundPoolDoesNotAdvertiseAnUnavailableInventory() {
        BotaniaResonantManaPoolBlockEntity pool = new BotaniaResonantManaPoolBlockEntity(
                BlockEntityType.CHEST, BlockPos.ZERO, Blocks.CHEST.defaultBlockState());

        assertFalse(pool.isBound(), "测试池子必须处于未绑定状态");
        assertFalse(pool.canReceiveManaFromBursts(),
                "未绑定时没有可写入的 RS 库存，不能向 Botania 宣称可接收");
        assertEquals(0, pool.getAvailableSpaceForMana());
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

    @Test
    void exposesBotaniaForgeCapabilitiesAndRevivesThemAfterInvalidation() {
        BotaniaResonantManaPoolBlockEntity pool = new BotaniaResonantManaPoolBlockEntity(
                BlockEntityType.CHEST, BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        LazyOptional<ManaReceiver> receiver = pool.getCapability(
                BotaniaForgeCapabilities.MANA_RECEIVER, null);

        assertSame(pool, receiver.resolve().orElseThrow());
        assertSame(pool, pool.getCapability(BotaniaForgeCapabilities.SPARK_ATTACHABLE, null)
                .resolve().orElseThrow());
        assertSame(pool, pool.getCapability(BotaniaForgeCapabilities.WANDABLE, null)
                .resolve().orElseThrow());

        pool.invalidateCaps();
        assertFalse(receiver.isPresent());
        pool.reviveCaps();

        assertSame(pool, pool.getCapability(BotaniaForgeCapabilities.MANA_RECEIVER, null)
                .resolve().orElseThrow());
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
