package com.huanghuang.rsintegration.compat.ftbquests;
import java.lang.reflect.Field;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Reads quest, task and reward IDs across FTB Quests API versions. */
public final class FtbQuestObjectId {
    private static final MethodType READER_TYPE = MethodType.methodType(long.class, Object.class);
    private static final ClassValue<MethodHandle> READERS = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            try {
                MethodHandle reader;
                try {
                    reader = MethodHandles.publicLookup().unreflect(type.getMethod("getId"));
                } catch (NoSuchMethodException missingGetter) {
                    // 2001.4.10 exposes the inherited public id field without getId().
                    reader = MethodHandles.publicLookup().unreflectGetter(type.getField("id"));
                }
                return reader.asType(READER_TYPE);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Cannot resolve FTB quest object ID for "
                        + type.getName(), exception);
            }
        }
    };

    private FtbQuestObjectId() {}

    /** Object keeps the resolver loadable without the optional FTB Quests dependency. */
    public static long getId(Object questObject) {
        MethodHandle reader = READERS.get(questObject.getClass());
        try {
            return (long) reader.invokeExact(questObject);
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new IllegalStateException("Failed to read FTB quest object ID for "
                    + questObject.getClass().getName(), exception);
        }
    }
}
