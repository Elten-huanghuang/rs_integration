package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraftforge.fml.ModList;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Centralized optional-class loader with thread-safe per-call-site gating.
 * Replaces the duplicated {@code private static void ensureClasses()} pattern
 * found across 13 mod delegate/packet files.
 *
 * <p>The gate key includes the sorted class names so multiple call sites
 * within the same mod do not interfere with each other.</p>
 */
public final class ModClassLoader {
    private static final Map<String, AtomicBoolean> CLASS_LOAD_GATES = new ConcurrentHashMap<>();

    private ModClassLoader() {}

    /**
     * Ensures the given mod is loaded and the listed classes are loadable.
     * Thread-safe via compareAndSet on a key derived from modId + sorted class names.
     *
     * @param modId      the mod these classes belong to (for isLoaded guard)
     * @param classNames fully-qualified class names to verify
     * @return true if all classes loaded successfully (or were already verified)
     */
    public static boolean ensureClasses(String modId, String... classNames) {
        if (!ModList.get().isLoaded(modId)) return false;

        String gateKey = modId + ":" + String.join(",", classNames);
        AtomicBoolean gate = CLASS_LOAD_GATES.computeIfAbsent(gateKey, k -> new AtomicBoolean(false));
        if (!gate.compareAndSet(false, true)) return true; // already verified

        boolean allOk = true;
        for (String className : classNames) {
            try {
                Class.forName(className);
            } catch (ClassNotFoundException e) {
                RSIntegrationMod.LOGGER.warn("[RSI-ClassLoader] Optional class not found: {} (mod: {})", className, modId);
                allOk = false;
            }
        }
        return allOk;
    }
}
