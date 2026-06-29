package com.huanghuang.rsintegration.registry;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.VersionRange;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import org.apache.maven.artifact.versioning.ComparableVersion;

import javax.annotation.Nullable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Registers version-aware delegate classes per {@link ModType}.
 *
 * <p>When a mod changes its internal API between versions, register both
 * delegate variants here and the registry will select the correct one based
 * on the runtime mod version.  If no version-specific entry matches, the
 * default delegate from {@link ModType#createDelegate()} is used.</p>
 *
 * <p>Registration is thread-safe and should happen during mod init.</p>
 */
public final class ModVersionDelegateRegistry {

    private record Entry(String modId, Class<? extends IBatchDelegate> delegateClass, String minVersion) {}

    private static final Map<ModType, Map<String, Entry>> REGISTRY = new ConcurrentHashMap<>();

    private ModVersionDelegateRegistry() {}

    /**
     * Register a version-specific delegate.
     *
     * @param modType        the ModType this delegate handles
     * @param modId          the mod providing the API (e.g. "goety")
     * @param minVersion     minimum mod version for this delegate
     * @param delegateClass  the delegate implementation class
     */
    public static void register(ModType modType, String modId, String minVersion,
                                 Class<? extends IBatchDelegate> delegateClass) {
        REGISTRY.computeIfAbsent(modType, k -> new ConcurrentHashMap<>())
                .put(modId, new Entry(modId, delegateClass, minVersion));
        RSIntegrationMod.LOGGER.debug("[RSI-VersionReg] Registered {} for {} >= {}",
                delegateClass.getSimpleName(), modType.id(), minVersion);
    }

    /**
     * Resolve the best delegate class for a given ModType.
     * Returns null if no version-specific registration exists — caller
     * should fall back to {@link ModType#createDelegate()}.
     */
    @Nullable
    public static Class<? extends IBatchDelegate> resolve(ModType modType) {
        Map<String, Entry> entries = REGISTRY.get(modType);
        if (entries == null || entries.isEmpty()) return null;

        // Find the best match: highest version threshold that is satisfied
        Entry best = null;
        for (Entry entry : entries.values()) {
            if (VersionRange.isAtLeast(entry.modId, entry.minVersion)) {
                if (best == null || new ComparableVersion(entry.minVersion).compareTo(
                        new ComparableVersion(best.minVersion)) > 0) {
                    best = entry;
                }
            }
        }
        return best != null ? best.delegateClass : null;
    }

    /** Get the number of registered entries (for diagnostics). */
    public static int size() {
        int count = 0;
        for (Map<String, Entry> m : REGISTRY.values()) count += m.size();
        return count;
    }
}
