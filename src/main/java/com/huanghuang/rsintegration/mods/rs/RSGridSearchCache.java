package com.huanghuang.rsintegration.mods.rs;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.autoeat.client.PinyinUtil;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import com.refinedmods.refinedstorage.screen.grid.view.IGridView;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Incremental search index for Refined Storage's special grid filters.
 * Filter predicates only read completed data; tooltip and pinyin generation
 * never run from a full-grid filtering pass.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class RSGridSearchCache {
    private static final int[] MODES = {
            GridSearchQuery.TOOLTIP, GridSearchQuery.TAG, GridSearchQuery.MOD
    };
    private static final int PREWARM_MODES =
            GridSearchQuery.TOOLTIP | GridSearchQuery.TAG | GridSearchQuery.MOD;

    private static final class Entry {
        private int readyMask;
        private String tooltipSeed;
        private String tooltip;
        private String tags;
        private String mod;
        private String stableKey;
        private boolean diskLookupCounted;

        private boolean isReady(int mode) {
            return (readyMask & mode) != 0;
        }

        @Nullable
        private String value(int mode) {
            return switch (mode) {
                case GridSearchQuery.TOOLTIP -> tooltip;
                case GridSearchQuery.TAG -> tags;
                case GridSearchQuery.MOD -> mod;
                default -> null;
            };
        }

    }

    private record MatchResult(Set<UUID> ids, long indexVersion, boolean complete) {}

    private record TooltipTicket(long generation, long taskId) {}

    private record TooltipCompletion(
            UUID id, String stableKey, long generation, long taskId,
            String text, long buildNanos) {}

    private record PrewarmTooltipCompletion(
            String stableKey, long generation, String text, long buildNanos) {}

    private record ModCompletion(long session, String key, String text, long buildNanos) {}

    private record CandidateIndexCompletion(
            long session, int mode, SubstringCandidateIndex index, long version,
            int entries, long copyNanos, long buildNanos,
            @Nullable RuntimeException failure) {}

    private record DiskLoadCompletion(String context, GridSearchDiskStore.LoadResult result,
                                      long elapsedNanos) {}

    private record DiskSaveCompletion(String context, int entries, long elapsedNanos,
                                      IOException failure) {}

    private enum DiskState { UNINITIALIZED, LOADING, READY, DISABLED }

    private static final class MatchJob {
        private final List<GridSearchQuery.Term> terms;
        private int termIndex;
        private GridSearchQuery.Term currentTerm;
        private List<UUID> candidates = List.of();
        private int candidateIndex;
        private Set<UUID> matches = Set.of();
        private long startedNanos;
        private long sourceVersion;
        private String candidateSource = "none";
        private SolCarrotSearchStatus.Query solCarrotQuery =
                SolCarrotSearchStatus.Query.NONE;

        private MatchJob(List<GridSearchQuery.Term> terms) {
            this.terms = terms;
        }

        private boolean isComplete() {
            return termIndex >= terms.size() && currentTerm == null;
        }
    }

    private static final Map<UUID, Entry> CACHE = new HashMap<>();
    private static final LinkedHashMap<String, String> STABLE_TOOLTIP_CACHE =
            new LinkedHashMap<>(4_096, 0.75F, true);
    private static final Map<String, String> STABLE_TAG_CACHE = new HashMap<>();
    private static final Map<String, String> MOD_TEXT_CACHE = new HashMap<>();
    private static final Map<UUID, IGridStack> PRESENT_STACKS = new LinkedHashMap<>();
    private static final StableCandidateBindings CANDIDATE_BINDINGS =
            new StableCandidateBindings();
    private static final TransientEmptySnapshotGuard EMPTY_SNAPSHOT_GUARD =
            new TransientEmptySnapshotGuard();
    private static final List<ArrayDeque<UUID>> PENDING = List.of(
            new ArrayDeque<>(), new ArrayDeque<>(), new ArrayDeque<>());
    private static final List<Set<UUID>> QUEUED = List.of(
            new HashSet<>(), new HashSet<>(), new HashSet<>());
    private static final LinkedHashMap<GridSearchQuery.Term, MatchResult> MATCH_CACHE =
            new LinkedHashMap<>(32, 0.75F, true);
    private static final Set<GridSearchQuery.Term> JEI_SEEDED_TERMS = new HashSet<>();
    private static final List<Map<String, String>> CANDIDATE_TEXTS = List.of(
            new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
    private static final long[] CANDIDATE_TEXT_VERSIONS = new long[MODES.length];
    private static final long[] CANDIDATE_PUBLISHED_VERSIONS = new long[MODES.length];
    private static final SubstringCandidateIndex[] CANDIDATE_INDEXES = {
            new SubstringCandidateIndex(),
            new SubstringCandidateIndex(),
            new SubstringCandidateIndex()
    };
    private static final Map<UUID, TooltipTicket> IN_FLIGHT_TOOLTIPS = new HashMap<>();
    private static final ConcurrentLinkedQueue<TooltipCompletion> TOOLTIP_COMPLETIONS =
            new ConcurrentLinkedQueue<>();
    private static final Set<String> PREWARM_TOOLTIPS_IN_FLIGHT = new HashSet<>();
    private static final ConcurrentLinkedQueue<PrewarmTooltipCompletion>
            PREWARM_TOOLTIP_COMPLETIONS = new ConcurrentLinkedQueue<>();
    private static final Map<String, List<UUID>> MOD_WAITERS = new HashMap<>();
    private static final ConcurrentLinkedQueue<ModCompletion> MOD_COMPLETIONS =
            new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<DiskLoadCompletion> DISK_LOAD_COMPLETIONS =
            new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<DiskSaveCompletion> DISK_SAVE_COMPLETIONS =
            new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<CandidateIndexCompletion>
            CANDIDATE_INDEX_COMPLETIONS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger TOOLTIP_WORKER_IDS = new AtomicInteger();
    private static final ThreadPoolExecutor TOOLTIP_EXECUTOR = new ThreadPoolExecutor(
            RSIntegrationConfig.DEFAULT_GRID_SEARCH_PINYIN_WORKERS,
            RSIntegrationConfig.DEFAULT_GRID_SEARCH_PINYIN_WORKERS,
            30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable,
                        "RSI-Grid-Pinyin-" + TOOLTIP_WORKER_IDS.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
    private static final ThreadPoolExecutor DISK_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(2),
            runnable -> {
                Thread thread = new Thread(runnable, "RSI-Grid-Search-Disk");
                thread.setDaemon(true);
                return thread;
            });
    private static final ThreadPoolExecutor CANDIDATE_INDEX_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1),
            runnable -> {
                Thread thread = new Thread(runnable, "RSI-Grid-Candidate-Index");
                thread.setDaemon(true);
                return thread;
            });

    private static final long[] INDEX_BUILDS = new long[MODES.length];
    private static final long[] INDEX_BUILD_NANOS = new long[MODES.length];
    private static final long[] INDEX_BUILD_MAX_NANOS = new long[MODES.length];
    private static final long[] INDEX_VERSIONS = new long[MODES.length];
    private static long matchBuilds;
    private static long matchNanos;
    private static long matchMaxNanos;
    private static long candidateLookups;
    private static long candidateTotal;
    private static long candidateMax;
    private static long candidateIndexBuilds;
    private static long candidateIndexNanos;
    private static long candidateIndexMaxNanos;
    private static long candidateIndexCopyNanos;
    private static long candidateIndexCopyMaxNanos;
    private static long candidateSnapshotSyncs;
    private static long candidateTransientEmptySnapshots;
    private static long candidateDeferredEmptySnapshots;
    private static int lastOldStableKeys;
    private static int lastNewStableKeys;
    private static int lastRetainedStableKeys;
    private static int lastRemovedStableKeys;
    private static int lastAddedStableKeys;
    private static int lastFallbackUuidKeys;
    private static final long[] CANDIDATE_INDEX_MODE_BUILDS = new long[MODES.length];
    private static final int[] CANDIDATE_INDEX_ENTRIES = new int[MODES.length];
    private static long directCandidateScans;
    private static long invertedCandidateScans;
    private static long prefixCandidateScans;
    private static long tooltipCaptures;
    private static long tooltipCaptureNanos;
    private static long tooltipCaptureMaxNanos;
    private static long publishedMatchRevision;
    private static long renderedMatchRevision;
    private static long lastPartialRefreshNanos;
    private static long partialRefreshes;
    private static long firstResultCount;
    private static long firstResultNanos;
    private static long firstResultMaxNanos;
    private static long diskHits;
    private static long diskMisses;
    private static long diskLoadNanos;
    private static long diskSaveNanos;
    private static long diskLoadedEntries;
    private static long diskSavedEntries;
    private static long jeiSeedBuilds;
    private static long jeiSeedNanos;
    private static long jeiSeedMatches;
    private static long prewarmTooltipBuilds;
    private static long prewarmTooltipNanos;
    private static long prewarmTooltipHits;
    private static boolean currentQueryRendered;

    private static GridScreen currentScreen;
    private static IGridView currentView;
    private static GridScreen deferredEmptyScreen;
    private static IGridView deferredEmptyView;
    private static int requestedModes = PREWARM_MODES;
    private static int queueCursor;
    private static long tooltipGeneration;
    private static long nextTooltipTaskId;
    private static long sessionGeneration;
    private static long candidateIndexSession;
    private static final long[] CANDIDATE_INDEX_DUE_NANOS = new long[MODES.length];
    private static final boolean[] CANDIDATE_INDEX_BUILD_IN_FLIGHT =
            new boolean[MODES.length];
    private static long observedSolCarrotRevision = Long.MIN_VALUE;
    private static boolean lastAdvancedTooltipState;
    private static String lastLanguage = "";
    private static String modFingerprint;
    private static String diskContext;
    private static DiskState diskState = DiskState.UNINITIALIZED;
    private static boolean diskDirty;
    private static boolean diskSaveInFlight;
    private static long diskSaveDueNanos;

    private static List<ItemStack> prewarmStacks = List.of();
    private static int prewarmCursor;
    private static int prewarmTotal;
    private static long prewarmGeneration;
    private static String prewarmContext;
    private static boolean prewarmInitialized;
    private static boolean prewarmEnumerated;
    private static boolean cataloguePrewarmCompletionLogged;
    private static boolean gridIndexCompletionLogged;

    private static String deferredQueryText;
    private static GridSearchQuery deferredQuery;
    private static long deferredQueryChangedNanos;
    private static MatchJob matchJob;

    private static final ThreadLocal<StringBuilder> SHARED_BUILDER =
            ThreadLocal.withInitial(() -> new StringBuilder(512));

    private RSGridSearchCache() {}

    @SubscribeEvent
    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        invalidateAll();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        ensureDiskCache(minecraft);
        drainDiskCompletions();
        drainCandidateIndexCompletions();
        maybeScheduleDiskSave(false);
        if (minecraft.player == null || minecraft.level == null) return;

        applyDeferredEmptyReset(minecraft);

        refreshTooltipState(minecraft);
        refreshSolCarrotState(minecraft);
        configureTooltipExecutor();
        drainTooltipCompletions();
        drainPrewarmTooltipCompletions();
        drainModCompletions();
        maybeScheduleCandidateIndexBuild();

        boolean gridOpen = minecraft.screen instanceof GridScreen;
        boolean active = gridOpen && deferredQuery != null;
        long budgetMicros = active
                ? RSIntegrationConfig.GRID_SEARCH_ACTIVE_BUDGET_MICROS.get()
                : RSIntegrationConfig.GRID_SEARCH_IDLE_BUDGET_MICROS.get();
        long tickStartedNanos = System.nanoTime();
        long budgetNanos = budgetMicros * 1_000L;
        long deadlineNanos = tickStartedNanos + budgetNanos;

        if (!gridOpen) {
            detachView();
            long prewarmDeadline = hasPendingIndexWork()
                    ? tickStartedNanos + budgetNanos / 2L : deadlineNanos;
            processGlobalPrewarm(minecraft, prewarmDeadline);
            processPending(idlePreferredModes(), deadlineNanos);
            maybeLogPrewarmCompletion();
            return;
        }

        GridScreen screen = (GridScreen) minecraft.screen;
        bindView(screen, screen.getView());

        int preferredModes = active ? deferredQuery.requiredModes() : idlePreferredModes();
        long matchDeadline = tickStartedNanos + budgetNanos * 2L / 3L;
        if (active && debounceElapsed()) {
            trySeedFromSynchronizedJei(deferredQueryText, deferredQuery);
            ensureMatchJob(deferredQuery);
            processMatchJob(matchDeadline);
        }
        processPending(preferredModes, deadlineNanos);
        if (!active && System.nanoTime() < deadlineNanos) {
            processGlobalPrewarm(minecraft, deadlineNanos);
        }
        maybeLogPrewarmCompletion();

        if (deferredQuery != null && isQueryConverged(deferredQuery)
                && publishedMatchRevision == renderedMatchRevision) {
            clearDeferredRequest();
        }

        if (deferredQuery != null && hasPublishedResults(deferredQuery)
                && shouldRefreshPartialResults()) {
            String expectedQuery = deferredQueryText;
            IGridView view = currentView;
            if (view != null && currentScreen != null
                    && expectedQuery.equals(currentScreen.getSearchFieldText())) {
                renderedMatchRevision = publishedMatchRevision;
                lastPartialRefreshNanos = System.nanoTime();
                long sortStarted = System.nanoTime();
                view.forceSort();
                long sortMicros = (System.nanoTime() - sortStarted) / 1_000L;
                RSIntegrationMod.LOGGER.debug(
                        "[RSI Grid Search] query={} sort={}us {}",
                        expectedQuery, sortMicros, metricsSnapshot());
            } else {
                clearDeferredRequest();
            }
        }
    }

    /**
     * Called before RS performs a full filter and sort. Returns false until the
     * first result set is available; later partial sets remain visible while the
     * index continues to fill in.
     */
    public static boolean beforeForceSort(GridScreen screen, IGridView view) {
        bindView(screen, view);
        String queryText = screen.getSearchFieldText();
        GridSearchQuery query = GridSearchQuery.parse(queryText);
        if (query.requiredModes() == 0) {
            clearDeferredRequest();
            return true;
        }

        requestModes(query.requiredModes());
        if (!queryText.equals(deferredQueryText)) {
            deferredQueryChangedNanos = System.nanoTime();
            matchJob = null;
            renderedMatchRevision = publishedMatchRevision;
            currentQueryRendered = false;
        }
        deferredQueryText = queryText;
        deferredQuery = query;
        trySeedFromSynchronizedJei(queryText, query);
        if (hasPublishedResults(query)) {
            renderedMatchRevision = publishedMatchRevision;
            recordRenderedResults();
            if (isQueryConverged(query)) clearDeferredRequest();
            return true;
        }
        return false;
    }

    /** Refreshes the current stack snapshot after GridViewImpl.setStacks. */
    public static void onGridReset(GridScreen screen, IGridView view) {
        currentScreen = screen;
        currentView = view;
        if (deferTransientEmptyReset(screen, view)) return;
        clearDeferredEmptyReset();
        synchronizePresentStacks(view);
        scheduleCurrentQuery();
    }

    /** Applies an RS grid add/remove delta without rescanning the whole grid. */
    public static void onGridDelta(GridScreen screen, IGridView view, IGridStack changed) {
        bindView(screen, view);
        UUID id = changed.getId();
        IGridStack actual = view.get(id);
        if (deferredEmptyView == view && actual != null) {
            clearDeferredEmptyReset();
            synchronizePresentStacks(view);
            scheduleCurrentQuery();
            return;
        }
        boolean wasPresent = PRESENT_STACKS.containsKey(id);

        if (actual == null) {
            if (wasPresent) {
                PRESENT_STACKS.remove(id);
                CACHE.remove(id);
                removeCandidateKeyMapping(id, true);
                removeQueued(id);
                invalidateMatches();
                scheduleCurrentQuery();
            }
            return;
        }

        PRESENT_STACKS.put(id, actual);
        String previousKey = CANDIDATE_BINDINGS.key(id);
        String actualKey = candidateKey(actual);
        boolean identityChanged = wasPresent && !actualKey.equals(previousKey);
        if (!wasPresent || identityChanged) {
            if (identityChanged) {
                removeCandidateKeyMapping(id, true);
                CACHE.remove(id);
            }
            addCandidateKeyMapping(id, actualKey);
            invalidateMatches();
            enqueueMissing(actual, requestedModes);
            scheduleCurrentQuery();
        }
    }

    public static boolean matchesTooltip(UUID id, String query) {
        return matches(GridSearchQuery.TOOLTIP, id, query);
    }

    public static boolean matchesTags(UUID id, String query) {
        return matches(GridSearchQuery.TAG, id, query);
    }

    public static boolean matchesMod(UUID id, String query) {
        return matches(GridSearchQuery.MOD, id, query);
    }

    private static boolean matches(int mode, UUID id, String query) {
        if (query == null || query.isEmpty()) return true;
        MatchResult result = MATCH_CACHE.get(new GridSearchQuery.Term(
                mode, query.toLowerCase(Locale.ROOT)));
        return result != null && result.ids().contains(id);
    }

    public static void invalidateAll() {
        maybeScheduleDiskSave(true);
        CACHE.clear();
        MOD_TEXT_CACHE.clear();
        MOD_WAITERS.clear();
        MOD_COMPLETIONS.clear();
        PRESENT_STACKS.clear();
        CANDIDATE_BINDINGS.clear();
        clearDeferredEmptyReset();
        clearQueues();
        MATCH_CACHE.clear();
        resetCandidateIndex();
        currentScreen = null;
        currentView = null;
        requestedModes = PREWARM_MODES;
        tooltipGeneration++;
        sessionGeneration++;
        IN_FLIGHT_TOOLTIPS.clear();
        TOOLTIP_COMPLETIONS.clear();
        TOOLTIP_EXECUTOR.getQueue().clear();
        resetGlobalPrewarm();
        clearDeferredRequest();
        resetMetrics();
        for (int index = 0; index < INDEX_VERSIONS.length; index++) {
            INDEX_VERSIONS[index] = 0L;
        }
        publishedMatchRevision = 0L;
        renderedMatchRevision = 0L;
        lastPartialRefreshNanos = 0L;
        SolCarrotSearchStatus.clear();
        observedSolCarrotRevision = Long.MIN_VALUE;
        GridItemRenderContext.resetMetrics();
    }

    public static String metricsSnapshot() {
        return "entries=" + PRESENT_STACKS.size()
                + " ready=" + readyCount(GridSearchQuery.TOOLTIP)
                + "/" + readyCount(GridSearchQuery.TAG)
                + "/" + readyCount(GridSearchQuery.MOD)
                + " pending=" + PENDING.get(0).size()
                + "/" + PENDING.get(1).size()
                + "/" + PENDING.get(2).size()
                + " indexAvg=" + averageMicros(0)
                + "/" + averageMicros(1)
                + "/" + averageMicros(2) + "us"
                + " indexMax=" + INDEX_BUILD_MAX_NANOS[0] / 1_000L
                + "/" + INDEX_BUILD_MAX_NANOS[1] / 1_000L
                + "/" + INDEX_BUILD_MAX_NANOS[2] / 1_000L + "us"
                + " tooltipCaptureAvg=" + (tooltipCaptures == 0
                ? 0 : tooltipCaptureNanos / tooltipCaptures / 1_000L)
                + "us tooltipCaptureMax=" + tooltipCaptureMaxNanos / 1_000L + "us"
                + " matchAvg=" + (matchBuilds == 0 ? 0 : matchNanos / matchBuilds / 1_000L)
                + "us matchMax=" + matchMaxNanos / 1_000L
                + "us candidates=" + candidateLookups + "/"
                + (candidateLookups == 0 ? 0 : candidateTotal / candidateLookups)
                + "/" + candidateMax
                + " candidateIndex=" + candidateIndexBuilds + "/"
                + (candidateIndexBuilds == 0 ? 0
                : candidateIndexNanos / candidateIndexBuilds / 1_000L) + "/"
                + candidateIndexMaxNanos / 1_000L + "us"
                + " copy=" + (candidateIndexBuilds == 0 ? 0
                : candidateIndexCopyNanos / candidateIndexBuilds / 1_000L) + "/"
                + candidateIndexCopyMaxNanos / 1_000L + "us"
                + " modeBuilds=" + CANDIDATE_INDEX_MODE_BUILDS[0]
                + "," + CANDIDATE_INDEX_MODE_BUILDS[1]
                + "," + CANDIDATE_INDEX_MODE_BUILDS[2]
                + " entries=" + CANDIDATE_INDEX_ENTRIES[0]
                + "," + CANDIDATE_INDEX_ENTRIES[1]
                + "," + CANDIDATE_INDEX_ENTRIES[2]
                + " inflight=" + CANDIDATE_INDEX_BUILD_IN_FLIGHT[0]
                + "," + CANDIDATE_INDEX_BUILD_IN_FLIGHT[1]
                + "," + CANDIDATE_INDEX_BUILD_IN_FLIGHT[2]
                + " queue=" + CANDIDATE_INDEX_EXECUTOR.getQueue().size()
                + "/" + CANDIDATE_INDEX_COMPLETIONS.size()
                + " versions=" + CANDIDATE_PUBLISHED_VERSIONS[0]
                + "," + CANDIDATE_PUBLISHED_VERSIONS[1]
                + "," + CANDIDATE_PUBLISHED_VERSIONS[2]
                + "/" + CANDIDATE_TEXT_VERSIONS[0]
                + "," + CANDIDATE_TEXT_VERSIONS[1]
                + "," + CANDIDATE_TEXT_VERSIONS[2]
                + " candidatePath=" + invertedCandidateScans + "/"
                + directCandidateScans + "/" + prefixCandidateScans
                + " stableOverlap=" + lastOldStableKeys + "/"
                + lastNewStableKeys + "/" + lastRetainedStableKeys + "/"
                + lastRemovedStableKeys + "/" + lastAddedStableKeys
                + " fallbackUuid=" + lastFallbackUuidKeys
                + " snapshotSyncs=" + candidateSnapshotSyncs
                + " emptyAfterPopulated=" + candidateTransientEmptySnapshots
                + " emptyDeferred=" + candidateDeferredEmptySnapshots
                + " partialRefreshes=" + partialRefreshes
                + " firstResultAvg=" + (firstResultCount == 0
                ? 0 : firstResultNanos / firstResultCount / 1_000L)
                + "us firstResultMax=" + firstResultMaxNanos / 1_000L
                + "us disk=" + diskState.name().toLowerCase(Locale.ROOT)
                + " hits=" + diskHits + " misses=" + diskMisses
                + " entries=" + STABLE_TOOLTIP_CACHE.size()
                + " load=" + diskLoadedEntries + "/" + diskLoadNanos / 1_000L + "us"
                + " save=" + diskSavedEntries + "/" + diskSaveNanos / 1_000L + "us"
                + " jeiSeed=" + jeiSeedBuilds + "/" + jeiSeedMatches
                + "/" + jeiSeedNanos / 1_000L + "us"
                + " prewarm=" + prewarmCursor + "/" + prewarmTotal
                + " inflight=" + PREWARM_TOOLTIPS_IN_FLIGHT.size()
                + " hits=" + prewarmTooltipHits
                + " builds=" + prewarmTooltipBuilds
                + "/" + prewarmTooltipNanos / 1_000L + "us"
                + " " + GridItemRenderContext.metricsSnapshot()
                + " syncBuilds=0";
    }

    private static void bindView(GridScreen screen, IGridView view) {
        if (screen == currentScreen && view == currentView) return;
        currentScreen = screen;
        currentView = view;
        if (deferTransientEmptyReset(screen, view)) return;
        clearDeferredEmptyReset();
        synchronizePresentStacks(view);
    }

    private static void synchronizePresentStacks(IGridView view) {
        PRESENT_STACKS.clear();
        Map<UUID, String> replacementBindings = new HashMap<>();
        int fallbackUuidKeys = 0;
        for (IGridStack stack : view.getAllStacks()) {
            PRESENT_STACKS.put(stack.getId(), stack);
            String stableKey = stableStackKey(stack);
            if (stableKey == null) fallbackUuidKeys++;
            replacementBindings.put(stack.getId(), stableKey != null
                    ? stableKey : "grid|" + stack.getId());
        }
        StableCandidateBindings.Replacement replacement =
                CANDIDATE_BINDINGS.replace(replacementBindings);
        for (String removedKey : replacement.removedKeys()) {
            removeCandidateTexts(removedKey);
        }
        candidateSnapshotSyncs++;
        if (replacement.oldKeys() > 0 && replacement.newKeys() == 0) {
            candidateTransientEmptySnapshots++;
        }
        lastOldStableKeys = replacement.oldKeys();
        lastNewStableKeys = replacement.newKeys();
        lastRetainedStableKeys = replacement.retainedKeys();
        lastRemovedStableKeys = replacement.removedKeys().size();
        lastAddedStableKeys = replacement.addedKeys().size();
        lastFallbackUuidKeys = fallbackUuidKeys;
        RSIntegrationMod.LOGGER.debug(
                "[RSI Grid Search] grid snapshot overlap: old={} new={} retained={} removed={} added={} fallbackUuid={} stacks={}",
                lastOldStableKeys, lastNewStableKeys, lastRetainedStableKeys,
                lastRemovedStableKeys, lastAddedStableKeys, lastFallbackUuidKeys,
                PRESENT_STACKS.size());
        if (lastOldStableKeys > 0 && lastNewStableKeys > 0
                && lastRetainedStableKeys == 0) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI Grid Search] disjoint stable-key samples: old={} new={}",
                    sampleStableKeys(replacement.removedKeys()),
                    sampleStableKeys(replacement.addedKeys()));
        }
        CACHE.keySet().retainAll(PRESENT_STACKS.keySet());
        clearQueues();
        invalidateMatches();
        for (IGridStack stack : PRESENT_STACKS.values()) {
            enqueueMissing(stack, requestedModes);
        }
    }

    private static void detachView() {
        if (currentView == null && currentScreen == null) return;
        clearDeferredEmptyReset();
        currentView = null;
        currentScreen = null;
        clearDeferredRequest();
    }

    private static void applyDeferredEmptyReset(Minecraft minecraft) {
        if (deferredEmptyView == null) return;
        if (!(minecraft.screen instanceof GridScreen screen)
                || screen != deferredEmptyScreen
                || screen.getView() != deferredEmptyView) {
            clearDeferredEmptyReset();
            return;
        }
        if (!deferredEmptyView.getAllStacks().isEmpty()) {
            IGridView readyView = deferredEmptyView;
            clearDeferredEmptyReset();
            synchronizePresentStacks(readyView);
            scheduleCurrentQuery();
            return;
        }
        if (shouldRetainEmptySnapshot(deferredEmptyView)) return;
        IGridView emptyView = deferredEmptyView;
        clearDeferredEmptyReset();
        synchronizePresentStacks(emptyView);
        scheduleCurrentQuery();
    }

    private static boolean deferTransientEmptyReset(GridScreen screen, IGridView view) {
        if (!shouldRetainEmptySnapshot(view)) return false;
        boolean newlyDeferred = deferredEmptyView != view;
        deferredEmptyScreen = screen;
        deferredEmptyView = view;
        if (newlyDeferred) {
            candidateDeferredEmptySnapshots++;
            RSIntegrationMod.LOGGER.debug(
                    "[RSI Grid Search] deferred transient empty grid snapshot: retainedStacks={} grace={}ms",
                    PRESENT_STACKS.size(),
                    RSIntegrationConfig.GRID_SEARCH_EMPTY_SNAPSHOT_GRACE_MS.get());
        }
        return true;
    }

    private static boolean shouldRetainEmptySnapshot(IGridView view) {
        long graceNanos = RSIntegrationConfig.GRID_SEARCH_EMPTY_SNAPSHOT_GRACE_MS.get()
                * 1_000_000L;
        return EMPTY_SNAPSHOT_GUARD.shouldRetain(
                view, view.getAllStacks().isEmpty(), !PRESENT_STACKS.isEmpty(),
                System.nanoTime(), graceNanos);
    }

    private static void clearDeferredEmptyReset() {
        deferredEmptyScreen = null;
        deferredEmptyView = null;
        EMPTY_SNAPSHOT_GUARD.reset();
    }

    private static void refreshTooltipState(Minecraft minecraft) {
        boolean advanced = minecraft.options.advancedItemTooltips;
        String language = minecraft.getLanguageManager().getSelected();
        if (advanced == lastAdvancedTooltipState
                && language.equals(lastLanguage)) {
            return;
        }

        lastAdvancedTooltipState = advanced;
        lastLanguage = language;
        invalidateMode(GridSearchQuery.TOOLTIP);
        scheduleCurrentQuery();
    }

    private static void refreshSolCarrotState(Minecraft minecraft) {
        SolCarrotSearchStatus.refresh(minecraft.player);
        long revision = SolCarrotSearchStatus.revision();
        if (revision == observedSolCarrotRevision) return;
        observedSolCarrotRevision = revision;
        MATCH_CACHE.entrySet().removeIf(entry -> isSolCarrotTerm(entry.getKey()));
        if (matchJob != null && matchJob.terms.stream().anyMatch(
                RSGridSearchCache::isSolCarrotTerm)) {
            matchJob = null;
        }
        publishedMatchRevision++;
        scheduleCurrentQuery();
    }

    private static void invalidateMode(int mode) {
        int index = modeIndex(mode);
        PENDING.get(index).clear();
        QUEUED.get(index).clear();
        for (Entry entry : CACHE.values()) {
            entry.readyMask &= ~mode;
            if (mode == GridSearchQuery.TOOLTIP) {
                entry.tooltipSeed = null;
                entry.tooltip = null;
                entry.diskLookupCounted = false;
            }
            if (mode == GridSearchQuery.TAG) entry.tags = null;
            if (mode == GridSearchQuery.MOD) entry.mod = null;
        }
        if (mode == GridSearchQuery.TOOLTIP) {
            tooltipGeneration++;
            IN_FLIGHT_TOOLTIPS.clear();
            TOOLTIP_COMPLETIONS.clear();
        }
        MATCH_CACHE.entrySet().removeIf(entry -> entry.getKey().mode() == mode);
        JEI_SEEDED_TERMS.removeIf(term -> term.mode() == mode);
        clearCandidateMode(mode);
        if (matchJob != null && matchJob.terms.stream().anyMatch(term -> term.mode() == mode)) {
            matchJob = null;
        }
        if ((requestedModes & mode) != 0) {
            for (IGridStack stack : PRESENT_STACKS.values()) enqueueMissing(stack, mode);
        }
    }

    private static void requestModes(int modes) {
        int added = modes & ~requestedModes;
        requestedModes |= modes;
        if (added == 0) return;
        for (IGridStack stack : PRESENT_STACKS.values()) enqueueMissing(stack, added);
    }

    private static void enqueueMissing(IGridStack stack, int modes) {
        if (modes == 0) return;
        if (CANDIDATE_BINDINGS.key(stack.getId()) == null) {
            addCandidateKeyMapping(stack.getId(), candidateKey(stack));
        }
        Entry entry = CACHE.computeIfAbsent(stack.getId(), ignored -> new Entry());
        if (entry.stableKey == null) entry.stableKey = stableStackKey(stack);
        if ((modes & GridSearchQuery.TOOLTIP) != 0 && entry.tooltipSeed == null) {
            entry.tooltipSeed = normalize(stack.getName());
            String candidateKey = CANDIDATE_BINDINGS.key(stack.getId());
            if (candidateKey != null && !CANDIDATE_TEXTS
                    .get(modeIndex(GridSearchQuery.TOOLTIP)).containsKey(candidateKey)) {
                updateCandidateText(stack.getId(), GridSearchQuery.TOOLTIP, entry.tooltipSeed);
            }
        }
        if ((modes & GridSearchQuery.TOOLTIP) != 0) {
            hydrateTooltipFromDisk(stack.getId(), entry);
        }
        if ((modes & GridSearchQuery.TAG) != 0 && !entry.isReady(GridSearchQuery.TAG)
                && entry.stableKey != null) {
            String cachedTags = STABLE_TAG_CACHE.get(entry.stableKey);
            if (cachedTags != null) {
                entry.tags = cachedTags;
                markReady(stack.getId(), entry, GridSearchQuery.TAG);
            }
        }
        if ((modes & GridSearchQuery.MOD) != 0 && !entry.isReady(GridSearchQuery.MOD)) {
            String cachedMod = MOD_TEXT_CACHE.get(modKey(stack.getModId(), stack.getModName()));
            if (cachedMod != null) {
                entry.mod = cachedMod;
                markReady(stack.getId(), entry, GridSearchQuery.MOD);
            }
        }
        for (int mode : MODES) {
            if ((modes & mode) == 0 || entry.isReady(mode)) continue;
            int index = modeIndex(mode);
            if (QUEUED.get(index).add(stack.getId())) {
                PENDING.get(index).offer(stack.getId());
            }
        }
    }

    private static void processPending(int preferredModes, long deadlineNanos) {
        boolean processedAny = false;
        while (!processedAny || System.nanoTime() < deadlineNanos) {
            int mode = nextPendingMode(preferredModes);
            if (mode == 0) return;
            int index = modeIndex(mode);
            UUID id = PENDING.get(index).poll();
            if (id == null) continue;
            QUEUED.get(index).remove(id);

            IGridStack stack = PRESENT_STACKS.get(id);
            Entry entry = CACHE.get(id);
            if (stack != null && entry != null && !entry.isReady(mode)) {
                if (mode == GridSearchQuery.TOOLTIP) {
                    submitTooltipBuild(id, stack);
                } else if (mode == GridSearchQuery.MOD) {
                    submitModBuild(id, entry, stack);
                } else {
                    long started = System.nanoTime();
                    build(id, entry, stack, mode);
                    recordIndexBuild(index, System.nanoTime() - started);
                }
            }
            processedAny = true;
            if (System.nanoTime() >= deadlineNanos) return;
        }
    }

    private static int nextPendingMode(int preferredModes) {
        for (int offset = 0; offset < MODES.length; offset++) {
            int index = (queueCursor + offset) % MODES.length;
            int mode = MODES[index];
            if (mode == GridSearchQuery.TOOLTIP && diskState == DiskState.LOADING) continue;
            if ((preferredModes & mode) != 0 && !PENDING.get(index).isEmpty()) {
                queueCursor = (index + 1) % MODES.length;
                return mode;
            }
        }
        return 0;
    }

    private static int idlePreferredModes() {
        if (!PENDING.get(modeIndex(GridSearchQuery.MOD)).isEmpty()) {
            return GridSearchQuery.MOD;
        }
        if (!PENDING.get(modeIndex(GridSearchQuery.TAG)).isEmpty()) {
            return GridSearchQuery.TAG;
        }
        return GridSearchQuery.TOOLTIP;
    }

    private static boolean hasPendingIndexWork() {
        for (ArrayDeque<UUID> queue : PENDING) {
            if (!queue.isEmpty()) return true;
        }
        return false;
    }

    private static void build(UUID id, Entry entry, IGridStack stack, int mode) {
        switch (mode) {
            case GridSearchQuery.TAG -> entry.tags = buildTags(stack);
            default -> throw new IllegalArgumentException("Unknown grid search mode: " + mode);
        }
        markReady(id, entry, mode);
    }

    private static void submitTooltipBuild(UUID id, IGridStack stack) {
        if (IN_FLIGHT_TOOLTIPS.containsKey(id)) return;
        Entry entry = CACHE.get(id);
        if (entry != null) {
            hydrateTooltipFromDisk(id, entry);
            if (entry.isReady(GridSearchQuery.TOOLTIP)) return;
        }
        long generation = tooltipGeneration;
        long taskId = ++nextTooltipTaskId;
        long captureStarted = System.nanoTime();
        List<String> lines = captureTooltipLines(stack);
        long captureElapsed = System.nanoTime() - captureStarted;
        tooltipCaptures++;
        tooltipCaptureNanos += captureElapsed;
        tooltipCaptureMaxNanos = Math.max(tooltipCaptureMaxNanos, captureElapsed);
        IN_FLIGHT_TOOLTIPS.put(id, new TooltipTicket(generation, taskId));
        String stableKey = entry == null ? null : entry.stableKey;
        try {
            TOOLTIP_EXECUTOR.execute(() -> {
                long started = System.nanoTime();
                String text = buildTooltipText(lines);
                TOOLTIP_COMPLETIONS.offer(new TooltipCompletion(
                        id, stableKey, generation, taskId, text, System.nanoTime() - started));
            });
        } catch (RejectedExecutionException ignored) {
            IN_FLIGHT_TOOLTIPS.remove(id);
            enqueueMissing(stack, GridSearchQuery.TOOLTIP);
        }
    }

    private static void drainTooltipCompletions() {
        TooltipCompletion completion;
        while ((completion = TOOLTIP_COMPLETIONS.poll()) != null) {
            TooltipTicket ticket = IN_FLIGHT_TOOLTIPS.get(completion.id());
            if (ticket == null
                    || ticket.generation() != completion.generation()
                    || ticket.taskId() != completion.taskId()
                    || completion.generation() != tooltipGeneration) {
                continue;
            }
            IN_FLIGHT_TOOLTIPS.remove(completion.id());
            rememberStableTooltip(completion.stableKey(), completion.text());
            Entry entry = CACHE.get(completion.id());
            if (entry == null || !PRESENT_STACKS.containsKey(completion.id())) continue;
            entry.tooltip = completion.text();
            markReady(completion.id(), entry, GridSearchQuery.TOOLTIP);
            recordIndexBuild(modeIndex(GridSearchQuery.TOOLTIP), completion.buildNanos());
        }
    }

    private static void submitModBuild(UUID id, Entry entry, IGridStack stack) {
        String modId = stack.getModId();
        String modName = stack.getModName();
        String key = modKey(modId, modName);
        String cached = MOD_TEXT_CACHE.get(key);
        if (cached != null) {
            entry.mod = cached;
            markReady(id, entry, GridSearchQuery.MOD);
            return;
        }

        List<UUID> waiters = MOD_WAITERS.get(key);
        if (waiters != null) {
            waiters.add(id);
            return;
        }

        List<UUID> firstWaiter = new ArrayList<>();
        firstWaiter.add(id);
        MOD_WAITERS.put(key, firstWaiter);
        long session = sessionGeneration;
        try {
            TOOLTIP_EXECUTOR.execute(() -> {
                long started = System.nanoTime();
                String text = buildModText(modId, modName);
                MOD_COMPLETIONS.offer(new ModCompletion(
                        session, key, text, System.nanoTime() - started));
            });
        } catch (RejectedExecutionException ignored) {
            MOD_WAITERS.remove(key);
            enqueueMissing(stack, GridSearchQuery.MOD);
        }
    }

    private static void drainModCompletions() {
        ModCompletion completion;
        while ((completion = MOD_COMPLETIONS.poll()) != null) {
            if (completion.session() != sessionGeneration) continue;
            List<UUID> waiters = MOD_WAITERS.remove(completion.key());
            if (waiters == null) continue;
            MOD_TEXT_CACHE.put(completion.key(), completion.text());
            for (UUID id : waiters) {
                Entry entry = CACHE.get(id);
                if (entry == null || !PRESENT_STACKS.containsKey(id)) continue;
                entry.mod = completion.text();
                markReady(id, entry, GridSearchQuery.MOD);
            }
            recordIndexBuild(modeIndex(GridSearchQuery.MOD), completion.buildNanos());
        }
    }

    private static void configureTooltipExecutor() {
        int workers = RSIntegrationConfig.GRID_SEARCH_PINYIN_WORKERS.get();
        int currentMaximum = TOOLTIP_EXECUTOR.getMaximumPoolSize();
        if (workers > currentMaximum) {
            TOOLTIP_EXECUTOR.setMaximumPoolSize(workers);
            TOOLTIP_EXECUTOR.setCorePoolSize(workers);
        } else if (workers < currentMaximum) {
            TOOLTIP_EXECUTOR.setCorePoolSize(workers);
            TOOLTIP_EXECUTOR.setMaximumPoolSize(workers);
        }
    }

    public static void onJeiRuntimeAvailable() {
        resetGlobalPrewarm();
    }

    public static void onJeiRuntimeUnavailable() {
        resetGlobalPrewarm();
    }

    private static void ensureGlobalPrewarm(Minecraft minecraft) {
        if (diskState != DiskState.READY && diskState != DiskState.DISABLED) return;
        var runtime = RSJeiPlugin.getRuntime();
        if (runtime == null || diskContext == null) return;
        if (prewarmInitialized && diskContext.equals(prewarmContext)) return;

        resetGlobalPrewarm();
        prewarmContext = diskContext;
        try {
            prewarmStacks = List.copyOf(runtime.getIngredientManager().getAllItemStacks());
        } catch (RuntimeException exception) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI Grid Search] JEI item catalogue is not ready for prewarm", exception);
            return;
        }
        prewarmTotal = prewarmStacks.size();
        prewarmInitialized = true;
        prewarmModTexts();
        if (prewarmTotal == 0) prewarmEnumerated = true;
    }

    private static void processGlobalPrewarm(Minecraft minecraft, long deadlineNanos) {
        ensureGlobalPrewarm(minecraft);
        if (!prewarmInitialized || prewarmEnumerated) return;

        boolean processedAny = false;
        while (prewarmCursor < prewarmTotal
                && (!processedAny || System.nanoTime() < deadlineNanos)) {
            ItemStack stack = prewarmStacks.get(prewarmCursor);
            String stableKey = stableIngredientKey(stack);
            if (stableKey == null) {
                prewarmCursor++;
                processedAny = true;
                continue;
            }

            if (!STABLE_TAG_CACHE.containsKey(stableKey)) {
                String tags = buildJeiTags(stack);
                if (tags != null) STABLE_TAG_CACHE.put(stableKey, tags);
            }

            if (STABLE_TOOLTIP_CACHE.containsKey(stableKey)) {
                prewarmTooltipHits++;
                prewarmCursor++;
                processedAny = true;
                continue;
            }
            if (PREWARM_TOOLTIPS_IN_FLIGHT.contains(stableKey)) {
                prewarmCursor++;
                processedAny = true;
                continue;
            }

            int maximumInFlight = Math.max(16,
                    RSIntegrationConfig.GRID_SEARCH_PINYIN_WORKERS.get() * 8);
            if (PREWARM_TOOLTIPS_IN_FLIGHT.size() >= maximumInFlight) return;

            List<String> lines = captureTooltipLines(stack);
            long generation = prewarmGeneration;
            PREWARM_TOOLTIPS_IN_FLIGHT.add(stableKey);
            prewarmCursor++;
            processedAny = true;
            try {
                TOOLTIP_EXECUTOR.execute(() -> {
                    long started = System.nanoTime();
                    String text = buildTooltipText(lines);
                    PREWARM_TOOLTIP_COMPLETIONS.offer(new PrewarmTooltipCompletion(
                            stableKey, generation, text, System.nanoTime() - started));
                });
            } catch (RejectedExecutionException ignored) {
                PREWARM_TOOLTIPS_IN_FLIGHT.remove(stableKey);
            }

            if (System.nanoTime() >= deadlineNanos) return;
        }

        if (prewarmCursor >= prewarmTotal) {
            prewarmEnumerated = true;
            prewarmStacks = List.of();
        }
    }

    private static void drainPrewarmTooltipCompletions() {
        PrewarmTooltipCompletion completion;
        while ((completion = PREWARM_TOOLTIP_COMPLETIONS.poll()) != null) {
            if (completion.generation() != prewarmGeneration) continue;
            PREWARM_TOOLTIPS_IN_FLIGHT.remove(completion.stableKey());
            rememberStableTooltip(completion.stableKey(), completion.text());
            prewarmTooltipBuilds++;
            prewarmTooltipNanos += completion.buildNanos();
        }
    }

    private static void prewarmModTexts() {
        long session = sessionGeneration;
        for (var mod : ModList.get().getMods()) {
            String modId = mod.getModId();
            String modName = mod.getDisplayName();
            String key = modKey(modId, modName);
            if (MOD_TEXT_CACHE.containsKey(key) || MOD_WAITERS.containsKey(key)) continue;
            MOD_WAITERS.put(key, new ArrayList<>());
            try {
                TOOLTIP_EXECUTOR.execute(() -> {
                    long started = System.nanoTime();
                    String text = buildModText(modId, modName);
                    MOD_COMPLETIONS.offer(new ModCompletion(
                            session, key, text, System.nanoTime() - started));
                });
            } catch (RejectedExecutionException ignored) {
                MOD_WAITERS.remove(key);
            }
        }
    }

    private static void maybeLogPrewarmCompletion() {
        boolean catalogueReady = prewarmEnumerated
                && PREWARM_TOOLTIPS_IN_FLIGHT.isEmpty()
                && PREWARM_TOOLTIP_COMPLETIONS.isEmpty()
                && MOD_WAITERS.isEmpty();
        if (catalogueReady && !cataloguePrewarmCompletionLogged) {
            cataloguePrewarmCompletionLogged = true;
            RSIntegrationMod.LOGGER.debug(
                    "[RSI Grid Search] JEI catalogue prewarm complete: items={} hits={} builds={} build={}us",
                    prewarmTotal, prewarmTooltipHits, prewarmTooltipBuilds,
                    prewarmTooltipNanos / 1_000L);
        }

        boolean gridIndexReady = currentView != null
                && !PRESENT_STACKS.isEmpty()
                && isSearchDataReady(PREWARM_MODES)
                && isCandidateIndexSettled(PREWARM_MODES);
        if (gridIndexReady && !gridIndexCompletionLogged) {
            gridIndexCompletionLogged = true;
            RSIntegrationMod.LOGGER.debug(
                    "[RSI Grid Search] RS grid index ready: {}",
                    metricsSnapshot());
        }
    }

    private static void resetGlobalPrewarm() {
        sessionGeneration++;
        MOD_TEXT_CACHE.clear();
        MOD_WAITERS.clear();
        MOD_COMPLETIONS.clear();
        prewarmGeneration++;
        prewarmStacks = List.of();
        prewarmCursor = 0;
        prewarmTotal = 0;
        prewarmContext = null;
        prewarmInitialized = false;
        prewarmEnumerated = false;
        cataloguePrewarmCompletionLogged = false;
        prewarmTooltipBuilds = 0;
        prewarmTooltipNanos = 0;
        prewarmTooltipHits = 0;
        PREWARM_TOOLTIPS_IN_FLIGHT.clear();
        PREWARM_TOOLTIP_COMPLETIONS.clear();
        STABLE_TAG_CACHE.clear();
    }

    private static void recordIndexBuild(int index, long elapsed) {
        INDEX_BUILDS[index]++;
        INDEX_BUILD_NANOS[index] += elapsed;
        INDEX_BUILD_MAX_NANOS[index] = Math.max(INDEX_BUILD_MAX_NANOS[index], elapsed);
    }

    private static void markReady(UUID id, Entry entry, int mode) {
        if (entry.isReady(mode)) return;
        entry.readyMask |= mode;
        String indexed = entry.value(mode);
        if (indexed != null) updateCandidateText(id, mode, indexed);
    }

    private static boolean isSearchDataReady(int modes) {
        for (UUID id : PRESENT_STACKS.keySet()) {
            Entry entry = CACHE.get(id);
            if (entry == null || (entry.readyMask & modes) != modes) return false;
        }
        return true;
    }

    private static boolean isCandidateIndexReady(int modes) {
        for (int mode : MODES) {
            int index = modeIndex(mode);
            if ((modes & mode) != 0
                    && CANDIDATE_PUBLISHED_VERSIONS[index]
                    != CANDIDATE_TEXT_VERSIONS[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCandidateIndexSettled(int modes) {
        if (!isCandidateIndexReady(modes) || !CANDIDATE_INDEX_COMPLETIONS.isEmpty()) {
            return false;
        }
        for (int index = 0; index < MODES.length; index++) {
            if ((modes & MODES[index]) != 0
                    && CANDIDATE_INDEX_BUILD_IN_FLIGHT[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasPublishedResults(GridSearchQuery query) {
        for (GridSearchQuery.Term term : query.terms()) {
            if (!term.text().isEmpty() && !MATCH_CACHE.containsKey(term)) return false;
        }
        return true;
    }

    private static boolean isQueryConverged(GridSearchQuery query) {
        for (GridSearchQuery.Term term : query.terms()) {
            if (term.text().isEmpty()) continue;
            if (!isSolCarrotTerm(term) && !isSearchDataReady(term.mode())) return false;
            MatchResult result = MATCH_CACHE.get(term);
            if (result == null || !result.complete()
                    || result.indexVersion() != versionForTerm(term)) return false;
        }
        return true;
    }

    private static boolean shouldRefreshPartialResults() {
        if (publishedMatchRevision == renderedMatchRevision) return false;
        long minimumNanos = RSIntegrationConfig.GRID_SEARCH_PARTIAL_REFRESH_MS.get()
                * 1_000_000L;
        return lastPartialRefreshNanos == 0L
                || System.nanoTime() - lastPartialRefreshNanos >= minimumNanos;
    }

    private static void recordRenderedResults() {
        partialRefreshes++;
        if (currentQueryRendered) return;
        currentQueryRendered = true;
        long elapsed = Math.max(0L, System.nanoTime() - deferredQueryChangedNanos);
        firstResultCount++;
        firstResultNanos += elapsed;
        firstResultMaxNanos = Math.max(firstResultMaxNanos, elapsed);
    }

    private static boolean debounceElapsed() {
        long debounceNanos = RSIntegrationConfig.GRID_SEARCH_DEBOUNCE_MS.get() * 1_000_000L;
        return System.nanoTime() - deferredQueryChangedNanos >= debounceNanos;
    }

    private static void ensureMatchJob(GridSearchQuery query) {
        List<GridSearchQuery.Term> missing = new ArrayList<>();
        for (GridSearchQuery.Term term : query.terms()) {
            if (term.text().isEmpty()) continue;
            MatchResult result = MATCH_CACHE.get(term);
            if (result == null || !result.complete()
                    || result.indexVersion() != versionForTerm(term)) {
                missing.add(term);
            }
        }
        if (missing.isEmpty()) {
            matchJob = null;
            return;
        }
        if (matchJob == null || !matchJob.terms.equals(missing)) {
            matchJob = new MatchJob(missing);
        }
    }

    private static void processMatchJob(long deadlineNanos) {
        if (matchJob == null) return;
        boolean processedAny = false;
        while (!processedAny || System.nanoTime() < deadlineNanos) {
            if (matchJob.currentTerm == null) {
                if (matchJob.termIndex >= matchJob.terms.size()) {
                    matchJob = null;
                    return;
                }
                beginCurrentTerm(matchJob);
            }

            if (matchJob.candidateIndex >= matchJob.candidates.size()) {
                completeCurrentTerm(matchJob);
                if (matchJob.isComplete()) {
                    matchJob = null;
                    return;
                }
                continue;
            }

            UUID id = matchJob.candidates.get(matchJob.candidateIndex++);
            Entry entry = CACHE.get(id);
            boolean matched;
            if (matchJob.solCarrotQuery != SolCarrotSearchStatus.Query.NONE) {
                IGridStack stack = PRESENT_STACKS.get(id);
                matched = stack != null && SolCarrotSearchStatus.matches(
                        matchJob.solCarrotQuery, stack.getIngredient());
            } else {
                String indexed = valueForMatch(entry, matchJob.currentTerm.mode());
                matched = indexed != null && indexed.contains(matchJob.currentTerm.text());
            }
            if (matched) {
                matchJob.matches.add(id);
            }
            processedAny = true;
            if (System.nanoTime() >= deadlineNanos) {
                publishCurrentTerm(matchJob, false);
                return;
            }
        }
    }

    private static void beginCurrentTerm(MatchJob job) {
        GridSearchQuery.Term term = job.terms.get(job.termIndex);
        SolCarrotSearchStatus.Query solCarrotQuery = solCarrotQuery(term);
        long currentVersion = versionForTerm(term);

        if (solCarrotQuery != SolCarrotSearchStatus.Query.NONE) {
            job.currentTerm = term;
            job.candidates = List.copyOf(PRESENT_STACKS.keySet());
            job.candidateIndex = 0;
            job.matches = new HashSet<>();
            job.startedNanos = System.nanoTime();
            job.sourceVersion = currentVersion;
            job.candidateSource = "sol_carrot";
            job.solCarrotQuery = solCarrotQuery;
            return;
        }

        MatchResult bestPrefix = null;
        int bestLength = -1;
        for (Map.Entry<GridSearchQuery.Term, MatchResult> cached : MATCH_CACHE.entrySet()) {
            GridSearchQuery.Term cachedTerm = cached.getKey();
            if (cachedTerm.mode() == term.mode()
                    && cached.getValue().indexVersion() == currentVersion
                    && cached.getValue().complete()
                    && term.text().startsWith(cachedTerm.text())
                    && cachedTerm.text().length() > bestLength) {
                bestPrefix = cached.getValue();
                bestLength = cachedTerm.text().length();
            }
        }

        int candidateModeIndex = modeIndex(term.mode());
        List<String> indexedKeys = CANDIDATE_INDEXES[candidateModeIndex]
                .candidates(term.mode(), term.text());
        candidateLookups++;
        candidateTotal += indexedKeys.size();
        candidateMax = Math.max(candidateMax, indexedKeys.size());
        List<UUID> candidates;
        String candidateSource;
        if (bestPrefix != null) {
            Set<UUID> prefixIds = bestPrefix.ids();
            candidates = readyCandidates(term.mode(), prefixIds);
            candidateSource = "prefix";
            prefixCandidateScans++;
        } else {
            boolean indexReady = isCandidateIndexReady(term.mode());
            int searchableEntries = indexReady
                    ? CANDIDATE_TEXTS.get(candidateModeIndex).size()
                    : matchableCount(term.mode());
            boolean useIndex = SubstringCandidateIndex.shouldUseIndex(
                    indexReady, indexedKeys.size(), searchableEntries,
                    RSIntegrationConfig.GRID_SEARCH_CANDIDATE_INDEX_MAX_PERCENT.get());
            if (useIndex) {
                candidates = readyCandidatesForKeys(term.mode(), indexedKeys);
                candidateSource = "inverted";
                invertedCandidateScans++;
            } else {
                candidates = readyCandidates(term.mode(), PRESENT_STACKS.keySet());
                candidateSource = indexReady ? "direct_dense" : "direct_warming";
                directCandidateScans++;
            }
        }
        job.currentTerm = term;
        job.candidates = candidates;
        job.candidateIndex = 0;
        job.matches = new HashSet<>();
        job.startedNanos = System.nanoTime();
        job.sourceVersion = currentVersion;
        job.candidateSource = candidateSource;
        job.solCarrotQuery = SolCarrotSearchStatus.Query.NONE;
    }

    private static void completeCurrentTerm(MatchJob job) {
        long elapsed = System.nanoTime() - job.startedNanos;
        matchBuilds++;
        matchNanos += elapsed;
        matchMaxNanos = Math.max(matchMaxNanos, elapsed);
        publishCurrentTerm(job, true);
        RSIntegrationMod.debug(
                "[RSI Grid Search] term mode={} text={} path={} candidates={} matches={} version={}",
                job.currentTerm.mode(), job.currentTerm.text(), job.candidateSource,
                job.candidates.size(), job.matches.size(), job.sourceVersion);
        job.termIndex++;
        job.currentTerm = null;
        job.candidates = List.of();
        job.matches = Set.of();
        job.candidateSource = "none";
        job.solCarrotQuery = SolCarrotSearchStatus.Query.NONE;
    }

    private static void publishCurrentTerm(MatchJob job, boolean complete) {
        MatchResult previous = MATCH_CACHE.get(job.currentTerm);
        Set<UUID> publishedIds = ProgressiveSearchResults.merge(
                previous == null ? null : previous.ids(),
                previous == null ? Long.MIN_VALUE : previous.indexVersion(),
                job.matches, job.sourceVersion, complete);
        if (!complete && previous != null && !previous.complete()
                && previous.indexVersion() == job.sourceVersion
                && previous.ids().equals(publishedIds)) {
            return;
        }
        MatchResult published = new MatchResult(
                publishedIds, job.sourceVersion, complete);
        MATCH_CACHE.put(job.currentTerm, published);
        trimMatchCache();
        if (previous == null || !previous.ids().equals(published.ids())) {
            publishedMatchRevision++;
        }
    }

    private static List<UUID> readyCandidates(int mode, Iterable<UUID> candidates) {
        List<UUID> ready = new ArrayList<>();
        for (UUID id : candidates) {
            Entry entry = CACHE.get(id);
            if (entry != null && (entry.isReady(mode)
                    || mode == GridSearchQuery.TOOLTIP && entry.tooltipSeed != null)) {
                ready.add(id);
            }
        }
        return ready;
    }

    private static List<UUID> readyCandidatesForKeys(
            int mode, Iterable<String> candidateKeys) {
        List<UUID> candidates = new ArrayList<>();
        for (String candidateKey : candidateKeys) {
            CANDIDATE_BINDINGS.appendIds(candidateKey, candidates);
        }
        return readyCandidates(mode, candidates);
    }

    private static int matchableCount(int mode) {
        int count = 0;
        for (UUID id : PRESENT_STACKS.keySet()) {
            Entry entry = CACHE.get(id);
            if (entry != null && (entry.isReady(mode)
                    || mode == GridSearchQuery.TOOLTIP && entry.tooltipSeed != null)) {
                count++;
            }
        }
        return count;
    }

    private static long indexVersion(int mode) {
        return INDEX_VERSIONS[modeIndex(mode)];
    }

    private static long versionForTerm(GridSearchQuery.Term term) {
        return isSolCarrotTerm(term)
                ? SolCarrotSearchStatus.revision() : indexVersion(term.mode());
    }

    private static boolean isSolCarrotTerm(GridSearchQuery.Term term) {
        return solCarrotQuery(term) != SolCarrotSearchStatus.Query.NONE;
    }

    private static SolCarrotSearchStatus.Query solCarrotQuery(GridSearchQuery.Term term) {
        return term.mode() == GridSearchQuery.TOOLTIP
                ? SolCarrotSearchStatus.classify(term.text())
                : SolCarrotSearchStatus.Query.NONE;
    }

    @Nullable
    private static String valueForMatch(@Nullable Entry entry, int mode) {
        if (entry == null) return null;
        String value = entry.value(mode);
        if (value != null || mode != GridSearchQuery.TOOLTIP) return value;
        return entry.tooltipSeed;
    }

    private static void trimMatchCache() {
        int maximum = RSIntegrationConfig.GRID_SEARCH_QUERY_CACHE_ENTRIES.get();
        while (MATCH_CACHE.size() > maximum) {
            GridSearchQuery.Term eldest = MATCH_CACHE.keySet().iterator().next();
            MATCH_CACHE.remove(eldest);
            JEI_SEEDED_TERMS.remove(eldest);
        }
    }

    private static void scheduleCurrentQuery() {
        if (currentScreen == null) return;
        String queryText = currentScreen.getSearchFieldText();
        GridSearchQuery query = GridSearchQuery.parse(queryText);
        if (query.requiredModes() == 0) return;
        requestModes(query.requiredModes());
        if (!queryText.equals(deferredQueryText)) {
            deferredQueryChangedNanos = System.nanoTime();
            currentQueryRendered = false;
        }
        deferredQueryText = queryText;
        deferredQuery = query;
        matchJob = null;
    }

    /** Keep the current candidate scan alive while a newer index version is published. */
    private static void wakeCurrentQueryAfterIndexUpdate() {
        if (currentScreen == null) return;
        String queryText = currentScreen.getSearchFieldText();
        GridSearchQuery query = GridSearchQuery.parse(queryText);
        if (query.requiredModes() == 0) return;
        requestModes(query.requiredModes());
        if (deferredQueryText != null && !queryText.equals(deferredQueryText)) {
            deferredQueryChangedNanos = System.nanoTime();
            currentQueryRendered = false;
            matchJob = null;
        }
        deferredQueryText = queryText;
        deferredQuery = query;
    }

    private static void clearDeferredRequest() {
        deferredQueryText = null;
        deferredQuery = null;
        matchJob = null;
    }

    private static void invalidateMatches() {
        MATCH_CACHE.clear();
        matchJob = null;
    }

    private static void updateCandidateText(UUID id, int mode, String text) {
        String candidateKey = CANDIDATE_BINDINGS.key(id);
        if (candidateKey == null) return;
        int index = modeIndex(mode);
        String previous = CANDIDATE_TEXTS.get(index).put(candidateKey, text);
        if (text.equals(previous)) return;
        markCandidateIndexDirty(index);
    }

    private static void removeCandidateTexts(String candidateKey) {
        for (int index = 0; index < MODES.length; index++) {
            if (CANDIDATE_TEXTS.get(index).remove(candidateKey) != null) {
                markCandidateIndexDirty(index);
            }
        }
    }

    private static void addCandidateKeyMapping(UUID id, String candidateKey) {
        String orphanedKey = CANDIDATE_BINDINGS.put(id, candidateKey);
        if (orphanedKey != null) removeCandidateTexts(orphanedKey);
    }

    private static void removeCandidateKeyMapping(UUID id, boolean removeTextsWhenOrphaned) {
        String orphanedKey = CANDIDATE_BINDINGS.remove(id);
        if (removeTextsWhenOrphaned && orphanedKey != null) {
            removeCandidateTexts(orphanedKey);
        }
    }

    private static void clearCandidateMode(int mode) {
        int index = modeIndex(mode);
        if (CANDIDATE_TEXTS.get(index).isEmpty()
                && CANDIDATE_PUBLISHED_VERSIONS[index]
                == CANDIDATE_TEXT_VERSIONS[index]) {
            return;
        }
        CANDIDATE_TEXTS.get(index).clear();
        markCandidateIndexDirty(index);
    }

    private static void markCandidateIndexDirty(int index) {
        CANDIDATE_TEXT_VERSIONS[index]++;
        // Use a trailing-edge debounce: a bulk tooltip refresh should publish
        // one index after the queue goes quiet, rather than rebuilding once per
        // batch while the same snapshot is still arriving.
        CANDIDATE_INDEX_DUE_NANOS[index] = System.nanoTime()
                + RSIntegrationConfig.GRID_SEARCH_CANDIDATE_REBUILD_DELAY_MS.get()
                * 1_000_000L;
        gridIndexCompletionLogged = false;
    }

    private static void resetCandidateIndex() {
        candidateIndexSession++;
        gridIndexCompletionLogged = false;
        CANDIDATE_INDEX_COMPLETIONS.clear();
        CANDIDATE_INDEX_EXECUTOR.getQueue().clear();
        JEI_SEEDED_TERMS.clear();
        for (int index = 0; index < MODES.length; index++) {
            CANDIDATE_INDEX_BUILD_IN_FLIGHT[index] = false;
            CANDIDATE_INDEX_DUE_NANOS[index] = 0L;
            CANDIDATE_INDEX_ENTRIES[index] = 0;
            CANDIDATE_INDEXES[index] = new SubstringCandidateIndex();
            CANDIDATE_TEXTS.get(index).clear();
            CANDIDATE_TEXT_VERSIONS[index] = 0L;
            CANDIDATE_PUBLISHED_VERSIONS[index] = 0L;
        }
    }

    private static void maybeScheduleCandidateIndexBuild() {
        if (hasCandidateIndexBuildInFlight() || isCandidateIndexReady(PREWARM_MODES)) return;
        long now = System.nanoTime();
        int index = nextCandidateIndexMode(now);
        if (index < 0) return;

        long copyStarted = now;
        Map<String, String> snapshot = new LinkedHashMap<>(CANDIDATE_TEXTS.get(index));
        long copyNanos = System.nanoTime() - copyStarted;
        long session = candidateIndexSession;
        int mode = MODES[index];
        long version = CANDIDATE_TEXT_VERSIONS[index];
        CANDIDATE_INDEX_BUILD_IN_FLIGHT[index] = true;
        CANDIDATE_INDEX_DUE_NANOS[index] = 0L;
        try {
            CANDIDATE_INDEX_EXECUTOR.execute(() -> {
                long started = System.nanoTime();
                SubstringCandidateIndex built = null;
                RuntimeException failure = null;
                try {
                    built = SubstringCandidateIndex.build(mode, snapshot);
                } catch (RuntimeException exception) {
                    failure = exception;
                }
                CANDIDATE_INDEX_COMPLETIONS.offer(new CandidateIndexCompletion(
                        session, mode, built, version, snapshot.size(), copyNanos,
                        System.nanoTime() - started, failure));
            });
        } catch (RejectedExecutionException exception) {
            CANDIDATE_INDEX_BUILD_IN_FLIGHT[index] = false;
            CANDIDATE_INDEX_DUE_NANOS[index] = System.nanoTime()
                    + RSIntegrationConfig.GRID_SEARCH_CANDIDATE_REBUILD_DELAY_MS.get()
                    * 1_000_000L;
        }
    }

    private static int nextCandidateIndexMode(long now) {
        boolean[] considered = new boolean[MODES.length];
        boolean preferredModeDirty = false;
        if (deferredQuery != null) {
            for (GridSearchQuery.Term term : deferredQuery.terms()) {
                int index = modeIndex(term.mode());
                considered[index] = true;
                preferredModeDirty |= CANDIDATE_PUBLISHED_VERSIONS[index]
                        != CANDIDATE_TEXT_VERSIONS[index];
                if (isCandidateIndexModeDue(index, now)) return index;
            }
        }
        if (preferredModeDirty) return -1;
        for (int index = 0; index < MODES.length; index++) {
            if (!considered[index] && isCandidateIndexModeDue(index, now)) return index;
        }
        return -1;
    }

    private static boolean isCandidateIndexModeDue(int index, long now) {
        if (CANDIDATE_PUBLISHED_VERSIONS[index]
                == CANDIDATE_TEXT_VERSIONS[index]) {
            return false;
        }
        long dueNanos = CANDIDATE_INDEX_DUE_NANOS[index];
        return dueNanos == 0L || now >= dueNanos;
    }

    private static boolean hasCandidateIndexBuildInFlight() {
        for (boolean inFlight : CANDIDATE_INDEX_BUILD_IN_FLIGHT) {
            if (inFlight) return true;
        }
        return false;
    }

    private static void drainCandidateIndexCompletions() {
        CandidateIndexCompletion completion;
        while ((completion = CANDIDATE_INDEX_COMPLETIONS.poll()) != null) {
            if (completion.session() != candidateIndexSession) continue;
            int index = modeIndex(completion.mode());
            CANDIDATE_INDEX_BUILD_IN_FLIGHT[index] = false;
            candidateIndexCopyNanos += completion.copyNanos();
            candidateIndexCopyMaxNanos = Math.max(
                    candidateIndexCopyMaxNanos, completion.copyNanos());
            if (completion.failure() != null || completion.index() == null) {
                CANDIDATE_INDEX_DUE_NANOS[index] = System.nanoTime()
                        + RSIntegrationConfig.GRID_SEARCH_CANDIDATE_REBUILD_DELAY_MS.get()
                        * 1_000_000L;
                RSIntegrationMod.LOGGER.warn(
                        "[RSI Grid Search] Failed to build candidate index for mode {}",
                        completion.mode(), completion.failure());
                continue;
            }

            CANDIDATE_INDEXES[index] = completion.index();
            CANDIDATE_INDEX_ENTRIES[index] = completion.entries();
            CANDIDATE_INDEX_MODE_BUILDS[index]++;
            candidateIndexBuilds++;
            candidateIndexNanos += completion.buildNanos();
            candidateIndexMaxNanos = Math.max(
                    candidateIndexMaxNanos, completion.buildNanos());
            boolean changed = CANDIDATE_PUBLISHED_VERSIONS[index]
                    != completion.version();
            if (changed) {
                CANDIDATE_PUBLISHED_VERSIONS[index] = completion.version();
                INDEX_VERSIONS[index]++;
            }
            if (CANDIDATE_PUBLISHED_VERSIONS[index]
                    != CANDIDATE_TEXT_VERSIONS[index]
                    && CANDIDATE_INDEX_DUE_NANOS[index] == 0L) {
                CANDIDATE_INDEX_DUE_NANOS[index] = System.nanoTime()
                        + RSIntegrationConfig.GRID_SEARCH_CANDIDATE_REBUILD_DELAY_MS.get()
                        * 1_000_000L;
            }
            if (changed) {
                invalidateCurrentMatchJobForMode(completion.mode());
            }
        }
    }

    private static void invalidateCurrentMatchJobForMode(int mode) {
        if (matchJob != null && matchJob.terms.stream()
                .anyMatch(term -> term.mode() == mode)) {
            matchJob = null;
        }
        if (deferredQuery != null && deferredQuery.terms().stream()
                .anyMatch(term -> term.mode() == mode)) {
            wakeCurrentQueryAfterIndexUpdate();
        }
    }

    private static void clearQueues() {
        for (ArrayDeque<UUID> queue : PENDING) queue.clear();
        for (Set<UUID> queued : QUEUED) queued.clear();
    }

    private static void removeQueued(UUID id) {
        IN_FLIGHT_TOOLTIPS.remove(id);
        for (int index = 0; index < MODES.length; index++) {
            if (QUEUED.get(index).remove(id)) PENDING.get(index).remove(id);
        }
    }

    private static int modeIndex(int mode) {
        return switch (mode) {
            case GridSearchQuery.TOOLTIP -> 0;
            case GridSearchQuery.TAG -> 1;
            case GridSearchQuery.MOD -> 2;
            default -> throw new IllegalArgumentException("Unknown grid search mode: " + mode);
        };
    }

    private static int readyCount(int mode) {
        int count = 0;
        for (UUID id : PRESENT_STACKS.keySet()) {
            Entry entry = CACHE.get(id);
            if (entry != null && entry.isReady(mode)) count++;
        }
        return count;
    }

    private static long averageMicros(int index) {
        return INDEX_BUILDS[index] == 0
                ? 0 : INDEX_BUILD_NANOS[index] / INDEX_BUILDS[index] / 1_000L;
    }

    private static void resetMetrics() {
        for (int index = 0; index < MODES.length; index++) {
            INDEX_BUILDS[index] = 0;
            INDEX_BUILD_NANOS[index] = 0;
            INDEX_BUILD_MAX_NANOS[index] = 0;
        }
        matchBuilds = 0;
        matchNanos = 0;
        matchMaxNanos = 0;
        candidateLookups = 0;
        candidateTotal = 0;
        candidateMax = 0;
        candidateIndexBuilds = 0;
        candidateIndexNanos = 0;
        candidateIndexMaxNanos = 0;
        candidateIndexCopyNanos = 0;
        candidateIndexCopyMaxNanos = 0;
        candidateSnapshotSyncs = 0;
        candidateTransientEmptySnapshots = 0;
        candidateDeferredEmptySnapshots = 0;
        lastOldStableKeys = 0;
        lastNewStableKeys = 0;
        lastRetainedStableKeys = 0;
        lastRemovedStableKeys = 0;
        lastAddedStableKeys = 0;
        lastFallbackUuidKeys = 0;
        for (int index = 0; index < MODES.length; index++) {
            CANDIDATE_INDEX_MODE_BUILDS[index] = 0L;
        }
        directCandidateScans = 0;
        invertedCandidateScans = 0;
        prefixCandidateScans = 0;
        tooltipCaptures = 0;
        tooltipCaptureNanos = 0;
        tooltipCaptureMaxNanos = 0;
        partialRefreshes = 0;
        firstResultCount = 0;
        firstResultNanos = 0;
        firstResultMaxNanos = 0;
        diskHits = 0;
        diskMisses = 0;
        jeiSeedBuilds = 0;
        jeiSeedNanos = 0;
        jeiSeedMatches = 0;
        prewarmTooltipBuilds = 0;
        prewarmTooltipNanos = 0;
        prewarmTooltipHits = 0;
        currentQueryRendered = false;
    }

    private static void trySeedFromSynchronizedJei(String queryText, GridSearchQuery query) {
        if (query.terms().size() != 1) return;
        GridSearchQuery.Term term = query.terms().get(0);
        if (term.mode() != GridSearchQuery.TOOLTIP) return;
        if (isSolCarrotTerm(term)) return;
        String normalizedQuery = queryText.trim().toLowerCase(Locale.ROOT);
        if (!normalizedQuery.equals("#" + term.text()) || MATCH_CACHE.containsKey(term)
                || JEI_SEEDED_TERMS.contains(term)) return;

        var runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) return;
        var filter = runtime.getIngredientFilter();
        if (!normalizedQuery.equals(filter.getFilterText().trim().toLowerCase(Locale.ROOT))) return;
        JEI_SEEDED_TERMS.add(term);

        long started = System.nanoTime();
        Map<String, List<UUID>> idsByStableKey = new HashMap<>();
        for (Map.Entry<UUID, Entry> cached : CACHE.entrySet()) {
            String stableKey = cached.getValue().stableKey;
            if (stableKey != null && PRESENT_STACKS.containsKey(cached.getKey())) {
                idsByStableKey.computeIfAbsent(stableKey, ignored -> new ArrayList<>())
                        .add(cached.getKey());
            }
        }

        Set<UUID> matches = new HashSet<>();
        for (ItemStack stack : filter.getFilteredItemStacks()) {
            String stableKey = stableIngredientKey(stack);
            List<UUID> ids = stableKey == null ? null : idsByStableKey.get(stableKey);
            if (ids != null) matches.addAll(ids);
        }
        long elapsed = System.nanoTime() - started;
        // JEI can expose an intermediate filtered list while its own search is
        // still settling. Show that list immediately, then let our index prove
        // the complete result instead of treating the seed as authoritative.
        MATCH_CACHE.put(term, new MatchResult(Set.copyOf(matches),
                indexVersion(GridSearchQuery.TOOLTIP), false));
        trimMatchCache();
        publishedMatchRevision++;
        jeiSeedBuilds++;
        jeiSeedMatches += matches.size();
        jeiSeedNanos += elapsed;
    }

    private static void ensureDiskCache(Minecraft minecraft) {
        if (!RSIntegrationConfig.GRID_SEARCH_DISK_CACHE_ENABLED.get()) {
            String desiredContext = diskContext(minecraft);
            if (diskState != DiskState.DISABLED
                    || !desiredContext.equals(diskContext)) {
                if (diskState == DiskState.READY && diskDirty) {
                    maybeScheduleDiskSave(true);
                }
                diskState = DiskState.DISABLED;
                diskContext = desiredContext;
                diskDirty = false;
                STABLE_TOOLTIP_CACHE.clear();
                resetGlobalPrewarm();
            }
            return;
        }

        String desiredContext = diskContext(minecraft);
        if (desiredContext.equals(diskContext)
                && diskState != DiskState.UNINITIALIZED
                && diskState != DiskState.DISABLED) {
            return;
        }

        if (diskState == DiskState.READY && diskDirty) maybeScheduleDiskSave(true);
        diskContext = desiredContext;
        diskState = DiskState.LOADING;
        diskDirty = false;
        STABLE_TOOLTIP_CACHE.clear();
        resetGlobalPrewarm();
        for (Entry entry : CACHE.values()) entry.diskLookupCounted = false;
        Path path = diskPath(desiredContext);
        int maxEntries = RSIntegrationConfig.GRID_SEARCH_DISK_CACHE_ENTRIES.get();
        long maxBytes = diskMaxBytes();
        try {
            DISK_EXECUTOR.execute(() -> {
                long started = System.nanoTime();
                GridSearchDiskStore.LoadResult result = GridSearchDiskStore.load(
                        path, desiredContext, maxEntries, maxBytes);
                DISK_LOAD_COMPLETIONS.offer(new DiskLoadCompletion(
                        desiredContext, result, System.nanoTime() - started));
            });
        } catch (RejectedExecutionException exception) {
            diskState = DiskState.READY;
            RSIntegrationMod.LOGGER.debug(
                    "[RSI Grid Search] Disk cache load queue is busy", exception);
        }
    }

    private static void drainDiskCompletions() {
        DiskLoadCompletion load;
        while ((load = DISK_LOAD_COMPLETIONS.poll()) != null) {
            if (!load.context().equals(diskContext)) continue;
            diskLoadNanos = load.elapsedNanos();
            diskLoadedEntries = load.result().entries().size();
            STABLE_TOOLTIP_CACHE.clear();
            STABLE_TOOLTIP_CACHE.putAll(load.result().entries());
            trimStableCache();
            diskState = DiskState.READY;
            if (load.result().corrupt()) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI Grid Search] Ignored a damaged tooltip search cache");
            }
            for (Map.Entry<UUID, Entry> cachedEntry : CACHE.entrySet()) {
                Entry entry = cachedEntry.getValue();
                entry.diskLookupCounted = false;
                hydrateTooltipFromDisk(cachedEntry.getKey(), entry);
            }
            scheduleCurrentQuery();
            RSIntegrationMod.LOGGER.debug(
                    "[RSI Grid Search] Loaded {} stable tooltip entries in {}us (accepted={})",
                    diskLoadedEntries, diskLoadNanos / 1_000L, load.result().accepted());
        }

        DiskSaveCompletion save;
        while ((save = DISK_SAVE_COMPLETIONS.poll()) != null) {
            diskSaveInFlight = false;
            if (!save.context().equals(diskContext)) continue;
            diskSaveNanos = save.elapsedNanos();
            diskSavedEntries = save.entries();
            if (save.failure() != null) {
                diskDirty = true;
                diskSaveDueNanos = System.nanoTime()
                        + RSIntegrationConfig.GRID_SEARCH_DISK_CACHE_SAVE_DELAY_MS.get()
                        * 1_000_000L;
                RSIntegrationMod.LOGGER.warn(
                        "[RSI Grid Search] Failed to save tooltip search cache", save.failure());
            }
        }
    }

    private static void hydrateTooltipFromDisk(UUID id, Entry entry) {
        if (entry.isReady(GridSearchQuery.TOOLTIP)
                || (diskState != DiskState.READY && diskState != DiskState.DISABLED)) {
            return;
        }
        String cached = entry.stableKey == null
                ? null : STABLE_TOOLTIP_CACHE.get(entry.stableKey);
        if (cached == null) {
            if (!entry.diskLookupCounted) {
                entry.diskLookupCounted = true;
                diskMisses++;
            }
            return;
        }
        if (!entry.diskLookupCounted) diskHits++;
        entry.diskLookupCounted = true;
        entry.tooltip = cached;
        markReady(id, entry, GridSearchQuery.TOOLTIP);
    }

    private static void rememberStableTooltip(@Nullable String stableKey, String text) {
        if (stableKey == null
                || diskState != DiskState.READY && diskState != DiskState.DISABLED) return;
        String previous = STABLE_TOOLTIP_CACHE.put(stableKey, text);
        trimStableCache();
        if (text.equals(previous) || diskState == DiskState.DISABLED) return;
        diskDirty = true;
        diskSaveDueNanos = System.nanoTime()
                + RSIntegrationConfig.GRID_SEARCH_DISK_CACHE_SAVE_DELAY_MS.get()
                * 1_000_000L;
    }

    private static void trimStableCache() {
        int maximum = RSIntegrationConfig.GRID_SEARCH_DISK_CACHE_ENTRIES.get();
        while (STABLE_TOOLTIP_CACHE.size() > maximum) {
            STABLE_TOOLTIP_CACHE.remove(STABLE_TOOLTIP_CACHE.keySet().iterator().next());
        }
    }

    private static void maybeScheduleDiskSave(boolean force) {
        if (diskState != DiskState.READY || !diskDirty || diskSaveInFlight) return;
        if (!force && System.nanoTime() < diskSaveDueNanos) return;

        String context = diskContext;
        Path path = diskPath(context);
        Map<String, String> snapshot = new LinkedHashMap<>(STABLE_TOOLTIP_CACHE);
        int maxEntries = RSIntegrationConfig.GRID_SEARCH_DISK_CACHE_ENTRIES.get();
        long maxBytes = diskMaxBytes();
        diskDirty = false;
        diskSaveInFlight = true;
        try {
            DISK_EXECUTOR.execute(() -> {
                long started = System.nanoTime();
                int entries = 0;
                IOException failure = null;
                try {
                    entries = GridSearchDiskStore.save(
                            path, context, snapshot, maxEntries, maxBytes);
                } catch (IOException exception) {
                    failure = exception;
                }
                DISK_SAVE_COMPLETIONS.offer(new DiskSaveCompletion(
                        context, entries, System.nanoTime() - started, failure));
            });
        } catch (RejectedExecutionException exception) {
            diskSaveInFlight = false;
            diskDirty = true;
        }
    }

    @Nullable
    private static String stableStackKey(IGridStack gridStack) {
        return stableIngredientKey(gridStack.getIngredient());
    }

    private static String candidateKey(IGridStack gridStack) {
        String stableKey = stableStackKey(gridStack);
        return stableKey != null ? stableKey : "grid|" + gridStack.getId();
    }

    private static List<String> sampleStableKeys(Set<String> keys) {
        return keys.stream().sorted().limit(3).toList();
    }

    @Nullable
    private static String stableIngredientKey(Object ingredient) {
        if (ingredient instanceof ItemStack stack && !stack.isEmpty()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id == null) return null;
            return "item|" + id + "|" + nbtDigest(stack.getTag());
        }
        if (ingredient instanceof FluidStack stack && !stack.isEmpty()) {
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
            if (id == null) return null;
            return "fluid|" + id + "|" + nbtDigest(stack.getTag());
        }
        return null;
    }

    private static String nbtDigest(@Nullable Object tag) {
        return tag == null ? "-" : sha256(tag.toString());
    }

    private static String diskContext(Minecraft minecraft) {
        if (modFingerprint == null) {
            String mods = ModList.get().getMods().stream()
                    .map(info -> info.getModId() + "=" + info.getVersion())
                    .sorted(Comparator.naturalOrder())
                    .reduce("", (left, right) -> left + "\n" + right);
            modFingerprint = sha256(mods);
        }
        return "schema=" + GridSearchDiskStore.SCHEMA
                + "|language=" + minecraft.getLanguageManager().getSelected()
                + "|advanced=" + minecraft.options.advancedItemTooltips
                + "|packs=" + sha256(String.join("\n", minecraft.options.resourcePacks))
                + "|mods=" + modFingerprint;
    }

    private static Path diskPath(String context) {
        return FMLPaths.CONFIGDIR.get()
                .resolve("rs_integration")
                .resolve("grid_search_cache")
                .resolve(sha256(context).substring(0, 24) + ".bin.gz");
    }

    private static long diskMaxBytes() {
        return RSIntegrationConfig.GRID_SEARCH_DISK_CACHE_MAX_MIB.get() * 1_048_576L;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static StringBuilder clearBuilder() {
        StringBuilder builder = SHARED_BUILDER.get();
        builder.setLength(0);
        return builder;
    }

    private static void appendNormalized(StringBuilder builder, String text) {
        if (text == null || text.isEmpty()) return;
        builder.append(text.toLowerCase(Locale.ROOT)).append('\n');
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private static void appendWithPinyin(StringBuilder builder, String text) {
        if (text == null || text.isEmpty()) return;
        appendNormalized(builder, text);
        appendPinyinForms(builder, text);
    }

    private static void appendPinyinForms(StringBuilder builder, String text) {
        if (!containsHan(text)) return;
        try {
            String pinyin = PinyinUtil.toPinyin(text);
            if (pinyin != null && !pinyin.isEmpty()
                    && !pinyin.equalsIgnoreCase(text)) {
                builder.append(pinyin.toLowerCase(Locale.ROOT)).append('\n');
            }
            String initials = PinyinUtil.toPinyinInitials(text);
            if (initials != null && !initials.isEmpty()
                    && !initials.equalsIgnoreCase(pinyin)) {
                builder.append(initials.toLowerCase(Locale.ROOT)).append('\n');
            }
        } catch (Throwable ignored) {
            // Keep the native search text when an optional pinyin provider fails.
        }
    }

    private static boolean containsHan(String text) {
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static List<String> captureTooltipLines(IGridStack stack) {
        List<String> lines = new ArrayList<>();
        boolean advanced = Minecraft.getInstance().options.advancedItemTooltips;
        for (Component line : stack.getTooltip(advanced)) {
            if (!SolCarrotSearchStatus.isDynamicTooltip(line)) lines.add(line.getString());
        }
        var runtime = RSJeiPlugin.getRuntime();
        if (runtime != null) {
            var manager = runtime.getIngredientManager();
            manager.createTypedIngredient(stack.getIngredient())
                    .ifPresent(typed -> lines.addAll(manager.getIngredientAliases(typed)));
        }
        return List.copyOf(lines);
    }

    private static List<String> captureTooltipLines(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        Minecraft minecraft = Minecraft.getInstance();
        TooltipFlag flag = minecraft.options.advancedItemTooltips
                ? TooltipFlag.Default.ADVANCED : TooltipFlag.Default.NORMAL;
        try {
            for (Component line : stack.getTooltipLines(minecraft.player, flag)) {
                if (!SolCarrotSearchStatus.isDynamicTooltip(line)) lines.add(line.getString());
            }
        } catch (RuntimeException exception) {
            lines.add(stack.getHoverName().getString());
        }
        var runtime = RSJeiPlugin.getRuntime();
        if (runtime != null) {
            var manager = runtime.getIngredientManager();
            manager.createTypedIngredient(stack)
                    .ifPresent(typed -> lines.addAll(manager.getIngredientAliases(typed)));
        }
        return List.copyOf(lines);
    }

    private static String buildTooltipText(List<String> lines) {
        StringBuilder builder = clearBuilder();
        for (String line : lines) appendNormalized(builder, line);
        appendPinyinForms(builder, builder.toString());
        return builder.toString();
    }

    private static String buildTags(IGridStack stack) {
        StringBuilder builder = clearBuilder();
        for (String tag : stack.getTags()) appendNormalized(builder, tag);
        return builder.toString();
    }

    @Nullable
    private static String buildJeiTags(ItemStack stack) {
        var runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) return null;
        StringBuilder builder = clearBuilder();
        try {
            runtime.getIngredientManager().getIngredientHelper(stack)
                    .getTagStream(stack)
                    .forEach(tag -> appendNormalized(builder, tag.toString()));
        } catch (RuntimeException ignored) {
            // Dynamic JEI ingredients are indexed from the live RS stack later.
            return null;
        }
        return builder.toString();
    }

    private static String modKey(String modId, String modName) {
        return modId == null || modId.isEmpty()
                ? "<unknown>\n" + String.valueOf(modName) : modId;
    }

    private static String buildModText(String modId, String modName) {
        StringBuilder builder = clearBuilder();
        appendNormalized(builder, modId);
        if (modName != null) appendWithPinyin(builder, modName.replace(" ", ""));
        return builder.toString();
    }
}
