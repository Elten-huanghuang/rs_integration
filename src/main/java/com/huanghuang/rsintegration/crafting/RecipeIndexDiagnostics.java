package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 记录配方目录中无法预先分片的单次第三方慢调用。 */
public final class RecipeIndexDiagnostics {
    public static final long SLOW_NANOS = 20_000_000L;
    static final int MAX_DETAILS = 128;
    private static final Map<Key, Timing> SLOW = new LinkedHashMap<>();
    private static long calls;
    private static long suppressed;
    private static Key worst;
    private static long worstNanos;
    private static boolean limitReported;

    private RecipeIndexDiagnostics() {}

    public static synchronized void record(String stage, long elapsedNanos,
                                           Object recipeId, Object type,
                                           Class<?> recipeClass,
                                           int slots, long candidates) {
        if (elapsedNanos < SLOW_NANOS) return;
        calls++;
        Key key = new Key(stage, String.valueOf(recipeId), String.valueOf(type),
                recipeClass == null ? "unknown" : recipeClass.getName());
        if (elapsedNanos > worstNanos) {
            worstNanos = elapsedNanos;
            worst = key;
        }
        Timing timing = SLOW.get(key);
        if (timing == null && SLOW.size() < MAX_DETAILS) {
            timing = new Timing();
            SLOW.put(key, timing);
        }
        if (timing == null || timing.count > 0L) {
            suppressed++;
            if (timing == null && !limitReported) {
                limitReported = true;
                RSIntegrationMod.LOGGER.warn(
                        "[RecipeIndexDiagnostics] detail limit reached max={}; "
                                + "further slow samples remain in the summary",
                        MAX_DETAILS);
            }
            return;
        }
        timing.count++;
        RSIntegrationMod.LOGGER.warn(
                "[RecipeIndexDiagnostics] slow stage={} recipe={} type={} class={} "
                        + "elapsedMs={} slots={} candidates={} thread={}",
                key.stage(), key.recipe(), key.type(), key.recipeClass(),
                String.format(Locale.ROOT, "%.2f", elapsedNanos / 1_000_000.0),
                slots, candidates, Thread.currentThread().getName());
    }

    public static synchronized void summarize(String reason) {
        if (calls == 0L) return;
        Key currentWorst = worst;
        RSIntegrationMod.LOGGER.info(
                "[RecipeIndexDiagnostics] summary reason={} calls={} logged={} suppressed={} "
                        + "maxMs={} worstStage={} worstRecipe={} worstType={} worstClass={}",
                reason, calls, SLOW.size(), suppressed,
                String.format(Locale.ROOT, "%.2f", worstNanos / 1_000_000.0),
                currentWorst == null ? "" : currentWorst.stage(),
                currentWorst == null ? "" : currentWorst.recipe(),
                currentWorst == null ? "" : currentWorst.type(),
                currentWorst == null ? "" : currentWorst.recipeClass());
    }

    public static synchronized void reset() {
        SLOW.clear();
        calls = 0L;
        suppressed = 0L;
        worst = null;
        worstNanos = 0L;
        limitReported = false;
    }

    private record Key(String stage, String recipe, String type, String recipeClass) {}

    private static final class Timing {
        private long count;
    }
}
