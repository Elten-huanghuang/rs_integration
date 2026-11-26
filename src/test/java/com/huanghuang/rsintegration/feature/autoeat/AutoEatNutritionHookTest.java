package com.huanghuang.rsintegration.feature.autoeat;

import net.minecraftforge.fml.ModList;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class AutoEatNutritionHookTest {
    @Test
    void failedNutritionHookDoesNotPreventSubsequentUpdates() {
        var failed = MethodHandles.throwException(void.class, IllegalStateException.class)
                .bindTo(new IllegalStateException("未附加饮食能力"));
        var updated = new AtomicInteger();
        try (var mods = mockStatic(ModList.class)) {
            mods.when(ModList::get).thenReturn(mock(ModList.class));
            assertFalse(AutoEatEngine.runNutritionHook("SolCarrot addFood", () -> failed.invoke()));
            assertTrue(AutoEatEngine.runNutritionHook("Diet consume", updated::incrementAndGet));
        }
        assertEquals(1, updated.get());
    }

    @Test
    void fatalErrorsAreNotSwallowed() {
        var failed = MethodHandles.throwException(void.class, InternalError.class)
                .bindTo(new InternalError("测试致命错误"));
        try (var mods = mockStatic(ModList.class)) {
            mods.when(ModList::get).thenReturn(mock(ModList.class));
            assertThrows(InternalError.class, () ->
                    AutoEatEngine.runNutritionHook("Diet sync", () -> failed.invoke()));
        }
    }
}
