package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchDelegateStartContractTest {

    @Test
    void interfaceExposesOneAbstractStartEntry() {
        Method start = Arrays.stream(IBatchDelegate.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("startOperation"))
                .findFirst()
                .orElseThrow();

        assertTrue(Modifier.isAbstract(start.getModifiers()));
        assertTrue(Arrays.stream(IBatchDelegate.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().startsWith("tryStart")
                        || method.getName().equals("supportsConcurrentNodeExecution")));
        assertEquals(1, Arrays.stream(IBatchDelegate.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("startOperation"))
                .count());
    }

    @Test
    void abstractDelegateOwnsTheCompatibilityStartHooks() throws Exception {
        Class<?> type = AbstractBatchDelegate.class;
        assertTrue(Modifier.isPublic(
                type.getMethod("startBufferedOperation", ServerPlayer.class,
                        InputBufferPlan.class, ExtractionLedger.class)
                        .getModifiers()));
        assertTrue(Modifier.isPublic(
                type.getMethod("startPreparedOperation", ServerPlayer.class,
                        List.class, ExtractionLedger.class)
                        .getModifiers()));
        assertTrue(Modifier.isPublic(
                type.getMethod("startSingleOperation", ServerPlayer.class)
                        .getModifiers()));
    }
}
