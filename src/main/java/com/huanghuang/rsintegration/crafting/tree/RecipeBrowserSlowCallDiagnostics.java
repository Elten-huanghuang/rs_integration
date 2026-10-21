package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 有界的 JEI/EMI 慢调用诊断。
 *
 * <p>仅在详细诊断开启时采样，并对“提供方 + 阶段 + 配方 + 实现细节”去重，
 * 避免第三方配方在每帧渲染时刷满日志。</p>
 */
public final class RecipeBrowserSlowCallDiagnostics {
    public static final long SLOW_CALL_NANOS = 20_000_000L;
    static final int MAX_LOGGED_KEYS = 128;

    private static final Set<String> LOGGED_KEYS = new LinkedHashSet<>();
    private static long suppressedCalls;

    private RecipeBrowserSlowCallDiagnostics() {}

    /** 返回 0 表示详细诊断关闭，调用方无需在快路径继续计时。 */
    public static long begin() {
        return RSIntegrationConfig.DIAGNOSTIC_VERBOSE_LOGGING.get()
                ? System.nanoTime() : 0L;
    }

    public static void finish(String provider, String stage,
                              @Nullable ResourceLocation recipeId,
                              @Nullable String detail, long startedNanos) {
        if (startedNanos == 0L) return;
        long elapsedNanos = System.nanoTime() - startedNanos;
        if (elapsedNanos < SLOW_CALL_NANOS) return;

        String recipe = recipeId == null ? "-" : recipeId.toString();
        String implementation = detail == null ? "-" : detail;
        String key = provider + '\0' + stage + '\0' + recipe + '\0' + implementation;
        boolean shouldLog = recordKey(key);
        if (!shouldLog) return;
        long suppressed = suppressedCount();
        RSIntegrationMod.LOGGER.warn(
                "[RSI-Perf] slow browser call provider={} stage={} recipe={} detail={} elapsedMs={} suppressed={}",
                provider, stage, recipe, implementation,
                elapsedNanos / 1_000_000L, suppressed);
    }

    public static synchronized void reset() {
        LOGGED_KEYS.clear();
        suppressedCalls = 0L;
    }

    public static synchronized String summary() {
        return "browserSlow=" + LOGGED_KEYS.size()
                + " suppressed=" + suppressedCalls
                + " thresholdMs=" + SLOW_CALL_NANOS / 1_000_000L;
    }

    static synchronized boolean claimKey(String key) {
        if (LOGGED_KEYS.contains(key)) return false;
        if (LOGGED_KEYS.size() >= MAX_LOGGED_KEYS) return false;
        LOGGED_KEYS.add(key);
        return true;
    }

    static synchronized boolean recordKey(String key) {
        if (claimKey(key)) return true;
        suppressedCalls++;
        return false;
    }

    static synchronized int loggedKeyCount() {
        return LOGGED_KEYS.size();
    }

    static synchronized long suppressedCount() {
        return suppressedCalls;
    }
}
