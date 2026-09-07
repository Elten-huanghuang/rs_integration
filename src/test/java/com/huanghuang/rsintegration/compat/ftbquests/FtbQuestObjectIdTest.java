package com.huanghuang.rsintegration.compat.ftbquests;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class FtbQuestObjectIdTest {
    @Test
    void readsInheritedFieldWhenGetterIsAbsent() {
        assertEquals(Long.MIN_VALUE, FtbQuestObjectId.getId(new LegacyTask(Long.MIN_VALUE)));
        assertEquals(0L, FtbQuestObjectId.getId(new LegacyTask(0L)));
    }

    @Test
    void prefersInheritedGetterOverField() {
        assertEquals(42L, FtbQuestObjectId.getId(new ModernTask(41L)));
    }

    @Test
    void preservesOverriddenGetter() {
        assertEquals(99L, FtbQuestObjectId.getId(new CustomTask(41L)));
    }

    @Test
    void doesNotRequireFieldWhenGetterExists() {
        assertEquals(Long.MAX_VALUE, FtbQuestObjectId.getId(new GetterOnly()));
    }

    @Test
    void doesNotFallBackWhenGetterThrows() {
        BrokenTask task = new BrokenTask();
        assertSame(task.failure, assertThrows(NoSuchMethodError.class,
                () -> FtbQuestObjectId.getId(task)));
    }

    public static class LegacyObject {
        public final long id;

        public LegacyObject(long id) {
            this.id = id;
        }
    }

    public static class LegacyTask extends LegacyObject {
        public LegacyTask(long id) {
            super(id);
        }
    }

    public static class ModernObject extends LegacyObject {
        public ModernObject(long id) {
            super(id);
        }

        public long getId() {
            return id + 1L;
        }
    }

    public static class ModernTask extends ModernObject {
        public ModernTask(long id) {
            super(id);
        }
    }

    public static class CustomTask extends ModernTask {
        public CustomTask(long id) {
            super(id);
        }

        @Override
        public long getId() {
            return 99L;
        }
    }

    public static class GetterOnly {
        public long getId() {
            return Long.MAX_VALUE;
        }
    }

    public static class BrokenTask extends LegacyObject {
        final NoSuchMethodError failure = new NoSuchMethodError("Getter dependency is missing");

        public BrokenTask() {
            super(41L);
        }

        public long getId() {
            throw failure;
        }
    }
}
