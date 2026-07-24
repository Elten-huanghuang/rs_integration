package com.huanghuang.rsintegration.mods.rs;

import com.huanghuang.rsintegration.autoeat.client.PinyinUtil;

import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * RS grid search cache with tick-based pre-warming.
 * <p>
 * When a special search mode is first used, matching strings are queued and
 * computed incrementally on the client tick (time-budgeted at 1.5 ms/tick).
 * Each mode is cached independently, so a {@code #} search does not build
 * every stack's tooltip and mod-name data.
 * </p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class RSGridSearchCache {

    /** Lazily computed, lowercased search strings for a single grid stack. */
    private static final class Entry {
        private String tooltip;
        private String tags;
        private String mod;

        synchronized void warm(IGridStack stack, int mask) {
            if ((mask & SEARCH_TOOLTIP) != 0 && tooltip == null) tooltip = buildTooltip(stack);
            if ((mask & SEARCH_TAGS) != 0 && tags == null) tags = buildTags(stack);
            if ((mask & SEARCH_MOD) != 0 && mod == null) mod = buildMod(stack);
        }

        String tooltip(IGridStack stack) {
            warm(stack, SEARCH_TOOLTIP);
            return tooltip;
        }

        String tags(IGridStack stack) {
            warm(stack, SEARCH_TAGS);
            return tags;
        }

        String mod(IGridStack stack) {
            warm(stack, SEARCH_MOD);
            return mod;
        }
    }

    private static final int SEARCH_TOOLTIP = 1;
    private static final int SEARCH_TAGS = 2;
    private static final int SEARCH_MOD = 4;
    private static volatile int requestedSearches;

    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();

    private static final Queue<IGridStack> PENDING_QUEUE = new ConcurrentLinkedQueue<>();
    private static final Set<UUID> QUEUED_IDS = ConcurrentHashMap.newKeySet();

    private static boolean lastAdvancedTooltipState;
    private static String lastLanguage = "";
    private static Screen lastScreen;
    private static long lastPreWarmTick;

    private static final long MAX_NANOS_PER_TICK = 1_500_000L;
    private static final long RE_SCAN_INTERVAL = 40; // rescan every 2 s
    private static final int TIME_CHECK_BATCH = 5; // only check nanoTime() every N items

    // Snapshot of grid stack UUIDs from the last enqueueAll — used to skip
    // stale queue entries that were removed from the grid before warm-up.
    private static volatile Set<UUID> presentIds = Set.of();

    private RSGridSearchCache() {}

    @SubscribeEvent
    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        invalidateAll();
        lastScreen = null;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        // mc.level can be briefly null mid-dimension-switch while mc.player is set;
        // guard here so all downstream mc.level.getGameTime() reads are safe.
        if (mc.player == null || mc.level == null) return;

        // ── state-polling invalidation ──
        boolean currAdvanced = mc.options.advancedItemTooltips;
        String currLang = mc.getLanguageManager().getSelected();

        if (currAdvanced != lastAdvancedTooltipState || !currLang.equals(lastLanguage)) {
            lastAdvancedTooltipState = currAdvanced;
            lastLanguage = currLang;
            // Tooltip strings depend on UI state; tags/mod are objective but cheap
            // to recompute, so drop the whole entry and re-warm in one pass.
            CACHE.clear();
            PENDING_QUEUE.clear();
            QUEUED_IDS.clear();
            if (requestedSearches != 0 && mc.screen instanceof GridScreen screen) {
                enqueueAll(screen);
            }
        }

        // ── capture new stacks only after a special search mode is used ──
        if (requestedSearches != 0 && mc.screen instanceof GridScreen screen) {
            if (screen != lastScreen) {
                lastScreen = screen;
                enqueueAll(screen);
            } else if (mc.level.getGameTime() - lastPreWarmTick > RE_SCAN_INTERVAL) {
                enqueueAll(screen);
            }
        } else if (!(mc.screen instanceof GridScreen)) {
            // Keep cache for the session — no clear on close.
            lastScreen = null;
        }

        // ── consume queue with time budget ──
        if (PENDING_QUEUE.isEmpty()) return;
        long started = System.nanoTime();
        int processed = 0;
        Set<UUID> alive = presentIds; // snapshot for zombie check

        while (!PENDING_QUEUE.isEmpty()) {
            // Batched time-check: nanoTime() is a JNI syscall (~1-2 µs on Windows).
            // Checking every 5 items saves ~80% of the overhead without hurting budget accuracy.
            if ((++processed % TIME_CHECK_BATCH) == 0
                    && System.nanoTime() - started > MAX_NANOS_PER_TICK) {
                break;
            }

            IGridStack stack = PENDING_QUEUE.poll();
            if (stack == null) continue;

            UUID id = stack.getId();

            // Zombie guard: skip stacks that were removed from the grid
            // before warm-up reached them — don't waste CPU on dead entries.
            if (!alive.contains(id)) {
                QUEUED_IDS.remove(id);
                continue;
            }

            Entry entry = CACHE.computeIfAbsent(id, k -> new Entry());
            entry.warm(stack, requestedSearches);
            QUEUED_IDS.remove(id);
        }
    }

    private static void enqueueAll(GridScreen screen) {
        try {
            Set<UUID> present = ConcurrentHashMap.newKeySet();
            for (IGridStack stack : screen.getView().getAllStacks()) {
                UUID id = stack.getId();
                present.add(id);
                if (!CACHE.containsKey(id) && QUEUED_IDS.add(id)) {
                    PENDING_QUEUE.offer(stack);
                }
            }
            // Mark-sweep: evict cache entries for stacks no longer in the grid,
            // keeping the map bounded to the current network contents.
            CACHE.keySet().retainAll(present);
            presentIds = present; // snapshot for zombie guard in consume loop
            lastPreWarmTick = Minecraft.getInstance().level.getGameTime();
        } catch (Throwable ignored) {
            // Graceful degradation: if future RS versions change the API,
            // fall back to lazy on-demand caching.
        }
    }

    public static void invalidateAll() {
        CACHE.clear();
        PENDING_QUEUE.clear();
        QUEUED_IDS.clear();
        presentIds = Set.of();
        requestedSearches = 0;
    }

    // ── accessors with lazy on-demand fallback ──
    // Called from the grid filter mixins: if the user types before pre-warming
    // finishes, the entry is built on the spot.

    public static String getTooltip(UUID id, IGridStack stack) {
        requestSearch(SEARCH_TOOLTIP);
        return CACHE.computeIfAbsent(id, k -> new Entry()).tooltip(stack);
    }

    public static String getTags(UUID id, IGridStack stack) {
        requestSearch(SEARCH_TAGS);
        return CACHE.computeIfAbsent(id, k -> new Entry()).tags(stack);
    }

    public static String getMod(UUID id, IGridStack stack) {
        requestSearch(SEARCH_MOD);
        return CACHE.computeIfAbsent(id, k -> new Entry()).mod(stack);
    }

    private static void requestSearch(int search) {
        if ((requestedSearches & search) != 0) return;
        requestedSearches |= search;
        PENDING_QUEUE.clear();
        QUEUED_IDS.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof GridScreen screen) enqueueAll(screen);
    }

    // ── internal builders ──

    // 极客级优化: 客户端主线程专用的全局复用 StringBuilder, 彻底消灭 GC 压力!
    // 所有预热和搜索都跑在 Render thread, ThreadLocal 保证安全且零竞争.
    private static final ThreadLocal<StringBuilder> SHARED_SB =
            ThreadLocal.withInitial(() -> new StringBuilder(512));

    private static StringBuilder getClearBuilder() {
        StringBuilder sb = SHARED_SB.get();
        sb.setLength(0); // 光速清空, 不释放底层 char[]
        return sb;
    }

    /**
     * 辅助方法：同时追加原生小写字符串、全拼、以及拼音首字母
     * 这样缓存建好后，查字典 O(1) 就能同时命中中文、全拼(haimeichangguo)和首字母(hmcg)！
     */
    private static void appendWithPinyin(StringBuilder sb, String text) {
        if (text == null || text.isEmpty()) return;
        sb.append(text).append('\n');

        try {
            String pinyin = PinyinUtil.toPinyin(text);
            if (pinyin != null && !pinyin.isEmpty()) {
                sb.append(pinyin.toLowerCase()).append('\n');
            }
            String initials = PinyinUtil.toPinyinInitials(text);
            // 首字母与全拼相等时(纯英文/无中文)不重复追加，省缓存空间
            if (initials != null && !initials.isEmpty()
                    && !initials.equalsIgnoreCase(pinyin)) {
                sb.append(initials.toLowerCase()).append('\n');
            }
        } catch (Throwable t) {
            // 优雅降级: 即使拼音库缺失或报错也不影响原搜索，更不会崩端
        }
    }

    private static String buildTooltip(IGridStack stack) {
        StringBuilder sb = getClearBuilder();
        boolean advanced = Minecraft.getInstance().options.advancedItemTooltips;
        for (Component line : stack.getTooltip(advanced)) {
            appendWithPinyin(sb, line.getString().toLowerCase());
        }
        return sb.toString();
    }

    private static String buildTags(IGridStack stack) {
        StringBuilder sb = getClearBuilder();
        for (String tag : stack.getTags()) {
            if (tag != null && !tag.isEmpty()) {
                sb.append(tag.toLowerCase(Locale.ROOT)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String buildMod(IGridStack stack) {
        StringBuilder sb = getClearBuilder();
        String modId = stack.getModId();
        if (modId != null) appendWithPinyin(sb, modId.toLowerCase());
        String modName = stack.getModName();
        if (modName != null) appendWithPinyin(sb, modName.toLowerCase().replace(" ", ""));
        return sb.toString();
    }
}
