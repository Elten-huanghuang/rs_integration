package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Coalesces external insertions before invoking the optional FTB Quests adapter. */
public final class ExternalItemProgressBridge {

    private static final Map<UUID, Map<MaterialKey, Long>> PENDING_EXTERNAL = new LinkedHashMap<>();
    private static final Map<UUID, Map<MaterialKey, Long>> PENDING_CRAFTED = new LinkedHashMap<>();
    private static final Map<UUID, Long> EXTERNAL_DEADLINES = new LinkedHashMap<>();
    private static final Map<UUID, Long> CRAFTED_DEADLINES = new LinkedHashMap<>();
    private static final int MAX_PENDING_PLAYERS = 256;
    private static final int MAX_PENDING_ITEMS_PER_PLAYER = 4096;
    private static final long MAX_PENDING_AGE_TICKS = 200L;
    private static volatile boolean enabled;
    private static boolean initialized;

    private ExternalItemProgressBridge() {}

    public static void initialize() {
        initialized = true;
        refreshEnabled();
    }

    public static void refreshEnabled() {
        if (!initialized) return;
        enabled = RSIntegrationConfig.ENABLE_FTB_QUEST_EXTERNAL_ITEM_PROGRESS.get()
                && ModList.get().isLoaded(ModIds.FTB_QUESTS)
                && ModList.get().isLoaded(ModIds.FTB_TEAMS)
                && !ModList.get().isLoaded(ModIds.YZZZ_OPTIMIZATION);
        if (!enabled) clearPending();
        if (enabled) {
            RSIntegrationMod.LOGGER.info("Enabled FTB Quests progress for external and crafted RS insertions");
        } else if (ModList.get().isLoaded(ModIds.YZZZ_OPTIMIZATION)) {
            RSIntegrationMod.LOGGER.info("FTB external item progress disabled because yzzzoptimization already provides it");
        }
    }

    public static void enqueue(ServerPlayer player, ItemStack inserted) {
        enqueue(PENDING_EXTERNAL, EXTERNAL_DEADLINES, player, inserted);
    }

    public static void enqueueCrafted(ServerPlayer player, ItemStack inserted) {
        enqueue(PENDING_CRAFTED, CRAFTED_DEADLINES, player, inserted);
    }

    private static void enqueue(Map<UUID, Map<MaterialKey, Long>> pending,
                                Map<UUID, Long> deadlines,
                                ServerPlayer player, ItemStack inserted) {
        if (!enabled || player == null || inserted == null || inserted.isEmpty()) return;
        MaterialKey key = MaterialKey.of(inserted);
        UUID playerId = player.getUUID();
        Map<MaterialKey, Long> playerPending = pending.computeIfAbsent(
                playerId, ignored -> new LinkedHashMap<>());
        if (!playerPending.containsKey(key) && playerPending.size() >= MAX_PENDING_ITEMS_PER_PLAYER) {
            RSIntegrationMod.LOGGER.warn("Dropping FTB progress item for {}: per-player queue limit reached",
                    player.getGameProfile().getName());
            return;
        }
        playerPending.merge(key, (long) inserted.getCount(), ExternalItemProgressBridge::saturatedAdd);
        deadlines.putIfAbsent(playerId, player.server.getTickCount() + MAX_PENDING_AGE_TICKS);
        if (pending.size() > MAX_PENDING_PLAYERS) {
            UUID oldest = pending.keySet().iterator().next();
            pending.remove(oldest);
            deadlines.remove(oldest);
            RSIntegrationMod.LOGGER.warn("Dropping oldest FTB progress queue: player limit reached");
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!enabled || event.phase != TickEvent.Phase.END
                || (PENDING_EXTERNAL.isEmpty() && PENDING_CRAFTED.isEmpty())) return;
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();
        flushExternal(server, drain(PENDING_EXTERNAL), now);
        flushCrafted(server, drain(PENDING_CRAFTED), now);
    }

    private static void flushExternal(MinecraftServer server,
                                      Map<UUID, Map<MaterialKey, Long>> batch, long now) {
        for (Map.Entry<UUID, Map<MaterialKey, Long>> playerEntry : batch.entrySet()) {
            UUID playerId = playerEntry.getKey();
            if (expired(EXTERNAL_DEADLINES, playerId, now)) {
                EXTERNAL_DEADLINES.remove(playerId);
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || !FtbQuestExternalItemDetector.isReady(player)) {
                requeue(PENDING_EXTERNAL, playerEntry);
                continue;
            }
            try {
                FtbQuestExternalItemDetector.detect(player, playerEntry.getValue());
            } catch (LinkageError | RuntimeException exception) {
                RSIntegrationMod.LOGGER.warn("Failed to apply external item progress for {}",
                        player.getGameProfile().getName(), exception);
            }
            EXTERNAL_DEADLINES.remove(playerId);
        }
    }

    private static void flushCrafted(MinecraftServer server,
                                     Map<UUID, Map<MaterialKey, Long>> batch, long now) {
        for (Map.Entry<UUID, Map<MaterialKey, Long>> playerEntry : batch.entrySet()) {
            UUID playerId = playerEntry.getKey();
            if (expired(CRAFTED_DEADLINES, playerId, now)) {
                CRAFTED_DEADLINES.remove(playerId);
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || !FtbQuestExternalItemDetector.isReady(player)) {
                requeue(PENDING_CRAFTED, playerEntry);
                continue;
            }
            try {
                FtbQuestCraftedItemDetector.detect(player, playerEntry.getValue());
                FtbQuestExternalItemDetector.detect(player, playerEntry.getValue());
            } catch (LinkageError | RuntimeException exception) {
                RSIntegrationMod.LOGGER.warn("Failed to apply crafted item progress for {}",
                        player.getGameProfile().getName(), exception);
            }
            CRAFTED_DEADLINES.remove(playerId);
        }
    }

    private static boolean expired(Map<UUID, Long> deadlines, UUID playerId, long now) {
        Long deadline = deadlines.get(playerId);
        return deadline != null && now >= deadline;
    }

    private static Map<UUID, Map<MaterialKey, Long>> drain(
            Map<UUID, Map<MaterialKey, Long>> pending) {
        Map<UUID, Map<MaterialKey, Long>> batch = new LinkedHashMap<>(pending);
        pending.clear();
        return batch;
    }

    private static void requeue(Map<UUID, Map<MaterialKey, Long>> pending,
                                Map.Entry<UUID, Map<MaterialKey, Long>> playerEntry) {
        Map<MaterialKey, Long> playerPending = pending.computeIfAbsent(
                playerEntry.getKey(), ignored -> new LinkedHashMap<>());
        playerEntry.getValue().forEach((key, amount) ->
                playerPending.merge(key, amount, ExternalItemProgressBridge::saturatedAdd));
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        clearPending();
        try {
            RsAutocraftProgressTracker.clear();
        } catch (LinkageError ignored) {
            // Progress tracking is optional and must not break server shutdown.
        }
    }

    static boolean isEnabled() {
        return enabled;
    }

    private static void clearPending() {
        PENDING_EXTERNAL.clear();
        PENDING_CRAFTED.clear();
        EXTERNAL_DEADLINES.clear();
        CRAFTED_DEADLINES.clear();
    }

    private static long saturatedAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }
}
