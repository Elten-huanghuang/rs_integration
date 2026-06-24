package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.world.item.crafting.Ingredient;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Reflection toolbox — replaces scattered {@code getMethod/getField/invoke} patterns.
 * Every method handles expected failures internally via {@code LOGGER.debug}, so
 * callers don't need to wrap every call in try-catch.
 */
public final class Reflect {

    private static final Logger LOG = RSIntegrationMod.LOGGER;
    private static final String TAG = "[RSI-Reflect]";

    private Reflect() {}

    // ── Class loading ────────────────────────────────────────────

    public static Optional<Class<?>> forName(String className) {
        try {
            return Optional.of(Class.forName(className));
        } catch (ClassNotFoundException e) {
            LOG.debug("{} Class not found: {}", TAG, className);
            return Optional.empty();
        }
    }

    // ── Method invocation ────────────────────────────────────────

    /** Invoke a no-arg method by name. */
    public static <T> Optional<T> invoke(Object obj, String methodName) {
        return invokeExact(obj, methodName, new Class<?>[0]);
    }

    /** Invoke a method, auto-detecting parameter types from the args. */
    public static <T> Optional<T> invoke(Object obj, String methodName, Object... args) {
        Class<?>[] paramTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            paramTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
        }
        return invokeExact(obj, methodName, paramTypes, args);
    }

    /**
     * Invoke a method with explicit parameter types.
     * Use this when the method is overloaded or has primitive parameters.
     */
    @SuppressWarnings("unchecked")
    public static <T> Optional<T> invokeExact(Object obj, String methodName,
                                               Class<?>[] paramTypes, Object... args) {
        try {
            Method m = findMethod(obj.getClass(), methodName, paramTypes);
            if (m == null) {
                LOG.debug("{} Method not found: {}.{}", TAG, obj.getClass().getName(), methodName);
                return Optional.empty();
            }
            return Optional.ofNullable((T) m.invoke(obj, args));
        } catch (Exception e) {
            LOG.debug("{} invoke failed: {}.{} — {}", TAG, obj.getClass().getName(), methodName, e.toString());
            return Optional.empty();
        }
    }

    // ── Field access ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static <T> Optional<T> getField(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName).orElse(null);
            if (f == null) {
                LOG.debug("{} Field not found: {}.{}", TAG, obj.getClass().getName(), fieldName);
                return Optional.empty();
            }
            f.setAccessible(true);
            return Optional.ofNullable((T) f.get(obj));
        } catch (Exception e) {
            LOG.debug("{} getField failed: {}.{} — {}", TAG, obj.getClass().getName(), fieldName, e.toString());
            return Optional.empty();
        }
    }

    public static OptionalInt getIntField(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName).orElse(null);
            if (f == null) return OptionalInt.empty();
            f.setAccessible(true);
            return OptionalInt.of(f.getInt(obj));
        } catch (Exception e) {
            LOG.debug("{} getIntField failed: {}.{} — {}", TAG, obj.getClass().getName(), fieldName, e.toString());
            return OptionalInt.empty();
        }
    }

    public static void setField(Object obj, String fieldName, @Nullable Object value) {
        try {
            Field f = findField(obj.getClass(), fieldName).orElse(null);
            if (f == null) {
                LOG.debug("{} setField: field not found {}.{}", TAG, obj.getClass().getName(), fieldName);
                return;
            }
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Exception e) {
            LOG.debug("{} setField failed: {}.{} — {}", TAG, obj.getClass().getName(), fieldName, e.toString());
        }
    }

    // ── Field discovery ──────────────────────────────────────────

    /** Walk the class hierarchy to find a declared field by name. */
    public static Optional<Field> findField(Class<?> clazz, String name) {
        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            try {
                return Optional.of(scan.getDeclaredField(name));
            } catch (NoSuchFieldException e) {
                scan = scan.getSuperclass();
            }
        }
        LOG.debug("{} Field not found in hierarchy: {}.{}", TAG, clazz.getName(), name);
        return Optional.empty();
    }

    // ── Method discovery ───────────────────────────────────────────

    /** Walk the class hierarchy to find a declared method by name and parameter types. */
    public static Method findMethod(Class<?> clazz, String name, Class<?>[] paramTypes) {
        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            try {
                Method m = scan.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // Try with auto-unboxing tolerance: if a param is Integer.class, also try int.class
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        Class<?>[] relaxed = relaxParamTypes(paramTypes, attempt == 1);
                        if (relaxed != paramTypes) {
                            Method m = scan.getDeclaredMethod(name, relaxed);
                            m.setAccessible(true);
                            return m;
                        }
                    } catch (NoSuchMethodException ignored) {}
                }
                scan = scan.getSuperclass();
            }
        }
        return null;
    }

    /**
     * When {@code unwrap} is false, try primitive→boxed widening.
     * When {@code unwrap} is true, try boxed→primitive narrowing.
     * Returns the original array if no changes needed.
     */
    private static Class<?>[] relaxParamTypes(Class<?>[] types, boolean unwrap) {
        Class<?>[] result = null;
        for (int i = 0; i < types.length; i++) {
            Class<?> alt = unwrap ? unwrap(types[i]) : wrap(types[i]);
            if (alt != types[i]) {
                if (result == null) result = types.clone();
                result[i] = alt;
            }
        }
        return result != null ? result : types;
    }

    private static Class<?> wrap(Class<?> c) {
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == boolean.class) return Boolean.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        if (c == byte.class) return Byte.class;
        if (c == short.class) return Short.class;
        if (c == char.class) return Character.class;
        return c;
    }

    private static Class<?> unwrap(Class<?> c) {
        if (c == Integer.class) return int.class;
        if (c == Long.class) return long.class;
        if (c == Boolean.class) return boolean.class;
        if (c == Float.class) return float.class;
        if (c == Double.class) return double.class;
        if (c == Byte.class) return byte.class;
        if (c == Short.class) return short.class;
        if (c == Character.class) return char.class;
        return c;
    }
}
