package com.huanghuang.rsintegration.mods.eidolon;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EidolonBatchDelegateBatchCapacityTest extends BootstrapTest {

    @Test
    void crucibleAndRitualUseOnePhysicalExecutionPerBatch() {
        EidolonBatchDelegate delegate = new EidolonBatchDelegate();

        assertEquals(1, delegate.prepareFlatBatch(6));

        setField(delegate, "isRitual", true);
        assertEquals(1, delegate.prepareFlatBatch(6));
    }

    @Test
    void worktableKeepsInstantBatching() {
        EidolonBatchDelegate delegate = new EidolonBatchDelegate();
        setField(delegate, "isWorktable", true);

        assertEquals(6, delegate.prepareFlatBatch(6));
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
