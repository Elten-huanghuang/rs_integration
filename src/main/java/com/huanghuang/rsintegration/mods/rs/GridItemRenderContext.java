package com.huanghuang.rsintegration.mods.rs;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.world.item.ItemStack;

/** Tracks dense RS grid item rendering without affecting other item views. */
public final class GridItemRenderContext {
    private static final String SLASHBLADE_ITEM_CLASS =
            "mods.flammpfeil.slashblade.item.ItemSlashBlade";
    private static final long METRICS_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);
    private static final ClassValue<Boolean> SLASHBLADE_ITEM_TYPES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                if (SLASHBLADE_ITEM_CLASS.equals(current.getName())) return true;
            }
            return false;
        }
    };

    private static long slashBladeDraws;
    private static long slashBladeDrawNanos;
    private static long slashBladeDrawMaxNanos;
    private static long lastMetricsLogNanos;

    private GridItemRenderContext() {}

    public static void begin(ItemStack stack) {
        State state = STATE.get();
        beginItem(state, stack);
    }

    public static void beginDenseList() {
        STATE.get().denseListDepth++;
    }

    public static void endDenseList() {
        State state = STATE.get();
        if (state.denseListDepth > 0) state.denseListDepth--;
    }

    public static boolean beginDenseListItem(ItemStack stack) {
        State state = STATE.get();
        if (state.denseListDepth <= 0) return false;
        beginItem(state, stack);
        return true;
    }

    private static void beginItem(State state, ItemStack stack) {
        if (state.itemDepth++ != 0) return;
        state.slashBlade = isSlashBlade(stack);
        state.startedNanos = state.slashBlade ? System.nanoTime() : 0L;
    }

    public static void end() {
        State state = STATE.get();
        if (state.itemDepth <= 0 || --state.itemDepth != 0) return;
        if (state.slashBlade) {
            long elapsed = Math.max(0L, System.nanoTime() - state.startedNanos);
            slashBladeDraws++;
            slashBladeDrawNanos += elapsed;
            slashBladeDrawMaxNanos = Math.max(slashBladeDrawMaxNanos, elapsed);
            maybeLogMetrics();
        }
        state.slashBlade = false;
        state.startedNanos = 0L;
    }

    public static boolean useLightweightSlashBladeRendering() {
        State state = STATE.get();
        return state.itemDepth > 0 && state.slashBlade
                && RSIntegrationConfig.LIGHTWEIGHT_SLASHBLADE_LIST_RENDERING.get();
    }

    public static String metricsSnapshot() {
        return "slashBladeDraw=" + slashBladeDraws + "/"
                + (slashBladeDraws == 0 ? 0 : slashBladeDrawNanos / slashBladeDraws / 1_000L)
                + "/" + slashBladeDrawMaxNanos / 1_000L + "us";
    }

    public static void resetMetrics() {
        slashBladeDraws = 0L;
        slashBladeDrawNanos = 0L;
        slashBladeDrawMaxNanos = 0L;
        lastMetricsLogNanos = 0L;
    }

    private static boolean isSlashBlade(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && SLASHBLADE_ITEM_TYPES.get(stack.getItem().getClass());
    }

    private static void maybeLogMetrics() {
        long now = System.nanoTime();
        if (lastMetricsLogNanos == 0L) {
            lastMetricsLogNanos = now;
            return;
        }
        if (now - lastMetricsLogNanos < METRICS_LOG_INTERVAL_NANOS) return;
        lastMetricsLogNanos = now;
        RSIntegrationMod.LOGGER.debug(
                "[RSI Dense Item Render] {} lightweight={}",
                metricsSnapshot(),
                RSIntegrationConfig.LIGHTWEIGHT_SLASHBLADE_LIST_RENDERING.get());
    }

    private static final class State {
        private int denseListDepth;
        private int itemDepth;
        private boolean slashBlade;
        private long startedNanos;
    }
}
