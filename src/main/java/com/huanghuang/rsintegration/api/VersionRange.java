package com.huanghuang.rsintegration.api;

import net.minecraftforge.fml.ModList;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Sniffs mod versions and answers threshold queries.
 *
 * <p>When a mod API changes between versions (e.g. Goety field renames,
 * FA ritual record additions), version-specific implementations can dispatch
 * against these stable threshold queries.</p>
 */
public final class VersionRange {
    private static final Logger LOGGER = LogManager.getLogger("RSI-Version");

    private VersionRange() {}

    /** Check whether a loaded mod is at or above a version threshold. */
    public static boolean isAtLeast(String modId, String threshold) {
        var container = ModList.get().getModContainerById(modId);
        if (container.isEmpty()) return false;
        String actual = String.valueOf(container.get().getModInfo().getVersion());
        try {
            return new ComparableVersion(actual).compareTo(new ComparableVersion(threshold)) >= 0;
        } catch (Exception e) {
            LOGGER.warn("Cannot parse version {} for mod {}", actual, modId, e);
            return false;
        }
    }

    /** Check whether a loaded mod is below a version threshold. */
    public static boolean isBelow(String modId, String threshold) {
        return !isAtLeast(modId, threshold);
    }

    /** Get the raw version string for a loaded mod, or "0" if not loaded. */
    public static String raw(String modId) {
        var container = ModList.get().getModContainerById(modId);
        return container.map(c -> String.valueOf(c.getModInfo().getVersion())).orElse("0");
    }
}
