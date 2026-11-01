package com.huanghuang.rsintegration.mods.goety.magic;

import com.huanghuang.rsintegration.magic.MagicResourceNetworkPool;
import com.huanghuang.rsintegration.mixin.goety.SoulCandlestickSoulSourceMixin;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SoulCandlestickSoulSourceMixinTest {
    @Test
    void virtualSoulsNeverPretendThatAPhysicalCageExists() {
        for (var method : SoulCandlestickSoulSourceMixin.class.getDeclaredMethods()) {
            var inject = method.getAnnotation(Inject.class);
            if (inject != null) assertFalse(Arrays.asList(inject.method()).contains("checkCage"),
                    "原生 tick 在 checkCage 后会直接读取实体笼，不能把虚拟来源伪装成实体笼");
        }
    }

    @Test
    void uncagedVirtualCandleCancelsNativeDrainEvenAfterBudgetIsExhausted() throws Exception {
        var candle = new SoulCandlestickSoulSourceMixin() {};
        var pool = mock(MagicResourceNetworkPool.class);
        when(pool.remaining()).thenReturn(20L);
        when(pool.withdraw(20L)).thenReturn(20L);
        var coordinator = GoetySoulSourceCoordinator.create(20, pool, mock(GoetyNativeSoulSource.class));
        assertNotNull(coordinator);
        var owner = new Object();
        GoetyVirtualSoulAccess.attach(owner, coordinator, List.of(candle));
        try {
            var read = SoulCandlestickSoulSourceMixin.class.getDeclaredMethod(
                    "rsi$readUnifiedSouls", CallbackInfoReturnable.class);
            read.setAccessible(true);
            var souls = new CallbackInfoReturnable<Integer>("getSouls", true);
            read.invoke(candle, souls);
            assertEquals(20, souls.getReturnValueI());
            assertTrue(souls.isCancelled());

            var drain = SoulCandlestickSoulSourceMixin.class.getDeclaredMethod(
                    "rsi$consumeUnifiedSouls", int.class, BlockPos.class, CallbackInfo.class);
            drain.setAccessible(true);
            var first = new CallbackInfo("drainSouls", true);
            drain.invoke(candle, 20, BlockPos.ZERO, first);
            assertTrue(first.isCancelled());
            assertEquals(0, coordinator.available());
            var exhausted = new CallbackInfo("drainSouls", true);
            drain.invoke(candle, 1, BlockPos.ZERO, exhausted);
            assertTrue(exhausted.isCancelled());

            GoetyVirtualSoulAccess.detach(owner);
            var physical = new CallbackInfo("drainSouls", true);
            drain.invoke(candle, 1, BlockPos.ZERO, physical);
            assertFalse(physical.isCancelled());
        } finally {
            GoetyVirtualSoulAccess.detach(owner);
            coordinator.close();
        }
    }
}
