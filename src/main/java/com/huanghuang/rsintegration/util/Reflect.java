package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.world.item.crafting.Ingredient;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection toolbox — replaces scattered {@code getMethod/getField/invoke} patterns.
 * Every method handles expected failures internally via {@code LOGGER.debug}, so
 * callers don't need to wrap every call in try-catch.
 */
public final class Reflect {

    private static final Logger LOG = RSIntegrationMod.LOGGER;
    private static final String TAG = "[RSI-Reflect]";

    private static final ConcurrentHashMap<String, Optional<Field>> fieldCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Optional<Method>> methodCache = new ConcurrentHashMap<>();

    private Reflect() {}

    // ── Method discovery (throwing variant) ────────────────────

    /**
     * Like {@link #findMethod(Class, String, Class[])} but throws
     * {@link NoSuchMethodException} instead of returning null.
     */
    public static Method getMethodOrThrow(Class<?> clazz, String mcp, String srg, Class<?>... params)
            throws NoSuchMethodException {
        var found = findMethod(clazz, mcp, params);
        if (found != null) return found;
        found = findMethod(clazz, srg, params);
        if (found != null) return found;
        throw new NoSuchMethodException(clazz.getName() + "." + mcp + "/" + srg);
    }

    // ── Holder value extraction ─────────────────────────────────

    /**
     * Extract the wrapped value from a {@code net.minecraft.core.Holder}
     * (typically {@code Holder$Reference}). Tries known field names, then
     * falls back to scanning declared fields by type.
     */
    public static Object extractHolderValue(Object holder) {
        if (holder == null) return null;
        // Try known field names first
        for (String name : new String[]{"value", "f_205752_", "delegate", "wrapped"}) {
            try {
                var f = holder.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object raw = f.get(holder);
                if (raw instanceof java.util.function.Supplier<?> s) {
                    return s.get();
                }
                return raw;
            } catch (Exception e) {
                LOG.debug("{} reflection probe failed", TAG, e);
            }
        }
        // Brute-force: return first non-synthetic Object field
        for (var f : holder.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (f.isSynthetic()) continue;
            if (f.getType() == Object.class) {
                f.setAccessible(true);
                try {
                    Object raw = f.get(holder);
                    if (raw instanceof java.util.function.Supplier<?> s) {
                        return s.get();
                    }
                    return raw;
                } catch (Exception e) {
                    LOG.debug("{} reflection probe failed", TAG, e);
                }
            }
        }
        return null;
    }

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

    /** Invoke a no-arg method, trying MCP name first then SRG fallback. */
    public static <T> Optional<T> invoke(Object obj, String mcpName, String srgName) {
        Optional<T> result = invokeExact(obj, mcpName, new Class<?>[0]);
        if (result.isPresent()) return result;
        return invokeExact(obj, srgName, new Class<?>[0]);
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
            LOG.debug("{} invoke failed: {}.{}", TAG, obj.getClass().getName(), methodName, e);
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
            LOG.debug("{} getField failed: {}.{}", TAG, obj.getClass().getName(), fieldName, e);
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
            LOG.debug("{} getIntField failed: {}.{}", TAG, obj.getClass().getName(), fieldName, e);
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
            LOG.debug("{} setField failed: {}.{}", TAG, obj.getClass().getName(), fieldName, e);
        }
    }

    // ── Field discovery ──────────────────────────────────────────

    /** Walk the class hierarchy to find a declared field by name. Positive results are cached. */
    public static Optional<Field> findField(Class<?> clazz, String name) {
        String key = clazz.getName() + "." + name;
        var cached = fieldCache.get(key);
        if (cached != null) return cached;
        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            try {
                Field f = scan.getDeclaredField(name);
                f.setAccessible(true);
                fieldCache.put(key, Optional.of(f));
                return Optional.of(f);
            } catch (NoSuchFieldException e) {
                scan = scan.getSuperclass();
            }
        }
        fieldCache.put(key, Optional.empty());
        LOG.debug("{} Field not found in hierarchy: {}.{}", TAG, clazz.getName(), name);
        return Optional.empty();
    }

    // ── Method discovery ───────────────────────────────────────────

    /** Walk the class hierarchy to find a declared method by name and parameter types. Results are cached including negatives. */
    @Nullable
    public static Method findMethod(Class<?> clazz, String name, Class<?>[] paramTypes) {
        String key = methodKey(clazz, name, paramTypes);
        {
            var cached = methodCache.get(key);
            if (cached != null) return cached.orElse(null);
        }

        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            try {
                Method m = scan.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                methodCache.put(key, Optional.of(m));
                return m;
            } catch (NoSuchMethodException e) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        Class<?>[] relaxed = relaxParamTypes(paramTypes, attempt == 1);
                        if (relaxed != paramTypes) {
                            Method m = scan.getDeclaredMethod(name, relaxed);
                            m.setAccessible(true);
                            methodCache.put(key, Optional.of(m));
                            return m;
                        }
                    } catch (NoSuchMethodException ex) {
                        LOG.debug("{} reflection probe failed", TAG, ex);
                    }
                }
                scan = scan.getSuperclass();
            }
        }

        // Fallback: scan declared methods for assignable match (handles
        // subclass→superclass parameter widening, e.g. passing
        // AlchemyTabletBlockEntity where BlockEntity is declared).
        Method found = findAssignable(clazz, name, paramTypes);
        if (found != null) {
            methodCache.put(key, Optional.of(found));
            return found;
        }

        methodCache.put(key, Optional.empty());
        LOG.debug("{} Method not found in hierarchy: {}.{}({})",
                TAG, clazz.getName(), name, paramTypes.length);
        return null;
    }

    /**
     * Scan all declared methods (including inherited) for one with the same
     * name and parameter count where every actual parameter type is assignable
     * to the declared type (with primitive↔boxed bridging).
     */
    private static Method findAssignable(Class<?> clazz, String name, Class<?>[] paramTypes) {
        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            for (Method m : scan.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] declared = m.getParameterTypes();
                if (declared.length != paramTypes.length) continue;
                if (paramsAssignable(paramTypes, declared)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            scan = scan.getSuperclass();
        }
        return null;
    }

    private static boolean paramsAssignable(Class<?>[] actual, Class<?>[] declared) {
        for (int i = 0; i < actual.length; i++) {
            if (!paramAssignable(actual[i], declared[i])) return false;
        }
        return true;
    }

    private static boolean paramAssignable(Class<?> actual, Class<?> declared) {
        if (declared.isAssignableFrom(actual)) return true;
        // Boxed actual → primitive declared (e.g. Double → double)
        if (declared.isPrimitive() && unwrap(actual) == declared) return true;
        // Primitive actual → boxed declared (e.g. double → Double)
        if (actual.isPrimitive() && wrap(actual) == declared) return true;
        return false;
    }

    private static String methodKey(Class<?> clazz, String name, Class<?>[] paramTypes) {
        StringBuilder sb = new StringBuilder(clazz.getName()).append('.').append(name).append('(');
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(paramTypes[i].getName());
        }
        return sb.append(')').toString();
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
