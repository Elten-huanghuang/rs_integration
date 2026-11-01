package com.huanghuang.rsintegration.mods.goety.magic;

import com.huanghuang.rsintegration.reflection.probes.GoetyReflection;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoetyBrazierNativeSoulSourceTest extends BootstrapTest {
    private Class<?> previousCandleType;
    private final ServerLevel level = mock(ServerLevel.class);
    private final BlockEntity machine = mock(BlockEntity.class);
    private final Candle candle = mock(Candle.class);
    private final BlockPos candlePos = BlockPos.ZERO.above();

    @BeforeEach
    void setup() {
        previousCandleType = GoetyReflection.soulCandlestickBEClass;
        GoetyReflection.soulCandlestickBEClass = Candle.class;
        when(machine.getLevel()).thenReturn(level);
        when(machine.getBlockPos()).thenReturn(BlockPos.ZERO);
        when(level.isLoaded(any(BlockPos.class))).thenReturn(true);
        when(level.getBlockEntity(candlePos)).thenReturn(candle);
    }

    @AfterEach
    void restoreReflection() {
        GoetyReflection.soulCandlestickBEClass = previousCandleType;
    }

    @Test
    void firstScanAtWorldTickZeroFindsExistingCandles() {
        when(level.getGameTime()).thenReturn(0L);
        var source = new GoetyBrazierNativeSoulSource(null, machine);
        assertEquals(List.of(candle), source.candles());
    }

    @Test
    void candleCacheRefreshesAfterFortyTicks() {
        var source = new GoetyBrazierNativeSoulSource(null, machine);
        when(level.getGameTime()).thenReturn(0L);
        assertEquals(List.of(candle), source.candles());
        when(level.getBlockEntity(candlePos)).thenReturn(null);
        when(level.getGameTime()).thenReturn(39L);
        assertEquals(List.of(candle), source.candles());
        when(level.getGameTime()).thenReturn(40L);
        assertTrue(source.candles().isEmpty());
    }

    @Test
    void unloadedPositionsAreNotQueried() {
        when(level.isLoaded(candlePos)).thenReturn(false);
        var source = new GoetyBrazierNativeSoulSource(null, machine);
        assertTrue(source.candles().isEmpty());
        verify(level, never()).getBlockEntity(candlePos);
    }

    private static final class Candle extends BlockEntity {
        private Candle() {
            super(null, BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        }
    }
}
