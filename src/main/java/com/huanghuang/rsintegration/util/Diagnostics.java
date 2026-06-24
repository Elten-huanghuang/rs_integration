package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Structured diagnostic event collector for key instrumentation points.
 *
 * <p>All methods are no-throw and check a master enabled flag before
 * recording any data. Events are kept in a bounded ring buffer so
 * memory is predictable.</p>
 */
public final class Diagnostics {

    private static final int MAX_EVENTS = 1024;
    private static final Deque<Event> events = new ConcurrentLinkedDeque<>();
    private static volatile boolean enabled = false;

    private Diagnostics() {}

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean v) {
        enabled = v;
        if (!v) events.clear();
    }

    // ── event types ───────────────────────────────────────────────

    public enum Category {
        INDEX_BUILD, RESOLVE_START, RESOLVE_END,
        CANDIDATE_SKIP, STEP_SELECTED,
        LEDGER_RESERVE, LEDGER_COMMIT, LEDGER_ROLLBACK,
        CHAIN_STATE, CHAIN_STEP_DONE, TIMER
    }

    public record Event(long timestampNanos, Category category, String detail,
                         @Nullable ResourceLocation recipeId, @Nullable ModType modType) {}

    // ── recording API ─────────────────────────────────────────────

    public static void record(Category cat, String detail) {
        record(cat, detail, null, null);
    }

    public static void record(Category cat, String detail,
                               @Nullable ResourceLocation recipeId,
                               @Nullable ModType modType) {
        if (!enabled) return;
        push(new Event(System.nanoTime(), cat, detail, recipeId, modType));
    }

    public static void recordTimer(String label, long durationNanos) {
        if (!enabled) return;
        String detail = label + " took " + Duration.ofNanos(durationNanos).toMillis() + "ms";
        push(new Event(System.nanoTime(), Category.TIMER, detail, null, null));
    }

    /** Start a named timer; returns a token to pass to {@link #stopTimer}. */
    public static long startTimer() {
        return enabled ? System.nanoTime() : 0L;
    }

    /** Record a named timer event if diagnostics are enabled. */
    public static long stopTimer(String label, long startNanos) {
        if (!enabled || startNanos == 0L) return 0L;
        long elapsed = System.nanoTime() - startNanos;
        recordTimer(label, elapsed);
        return elapsed;
    }

    // ── query ─────────────────────────────────────────────────────

    /** Return a snapshot of all recorded events (newest first). */
    public static List<Event> recentEvents() {
        return List.copyOf(events);
    }

    /** Return the last N events. */
    public static List<Event> recentEvents(int n) {
        List<Event> all = new ArrayList<>(events);
        if (all.size() <= n) return all;
        return all.subList(all.size() - n, all.size());
    }

    /** Dump recent events to the logger (info level). */
    public static void dumpToLog() {
        if (events.isEmpty()) {
            RSIntegrationMod.LOGGER.info("[RSI-Diag] No events recorded.");
            return;
        }
        RSIntegrationMod.LOGGER.info("[RSI-Diag] Dumping {} events:", events.size());
        for (Event e : events) {
            RSIntegrationMod.LOGGER.info("[RSI-Diag]   [{}] {} {}",
                    e.category, e.detail,
                    e.recipeId != null ? "recipe=" + e.recipeId : "");
        }
    }

    public static void clear() { events.clear(); }

    private static void push(Event e) {
        events.addLast(e);
        while (events.size() > MAX_EVENTS) events.removeFirst();
    }
}
