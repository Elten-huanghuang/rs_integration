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
    private static final int MAX_PENDING_PLAYERS = 256;
    private static final int MAX_PENDING_ITEMS_PER_PLAYER = 4096;
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
        enqueue(PENDING_EXTERNAL, player, inserted);
    }

    public static void enqueueCrafted(ServerPlayer player, ItemStack inserted) {
        enqueue(PENDING_CRAFTED, player, inserted);
    }

    private static void enqueue(Map<UUID, Map<MaterialKey, Long>> pending,
                                ServerPlayer player, ItemStack inserted) {
        if (!enabled || player == null || inserted == null || inserted.isEmpty()) return;
        MaterialKey key = MaterialKey.of(inserted);
        Map<MaterialKey, Long> playerPending = pending.computeIfAbsent(
                player.getUUID(), ignored -> new LinkedHashMap<>());
        if (!playerPending.containsKey(key) && playerPending.size() >= MAX_PENDING_ITEMS_PER_PLAYER) {
            RSIntegrationMod.LOGGER.warn("Dropping FTB progress item for {}: per-player queue limit reached",
                    player.getGameProfile().getName());
            return;
        }
        playerPending.merge(key, (long) inserted.getCount(), ExternalItemProgressBridge::saturatedAdd);
        if (pending.size() > MAX_PENDING_PLAYERS) {
            UUID oldest = pending.keySet().iterator().next();
            pending.remove(oldest);
            RSIntegrationMod.LOGGER.warn("Dropping oldest FTB progress queue: player limit reached");
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!enabled || event.phase != TickEvent.Phase.END
                || (PENDING_EXTERNAL.isEmpty() && PENDING_CRAFTED.isEmpty())) return;
        MinecraftServer server = event.getServer();
        flushExternal(server, drain(PENDING_EXTERNAL));
        flushCrafted(server, drain(PENDING_CRAFTED));
    }

    private static void flushExternal(MinecraftServer server,
                                      Map<UUID, Map<MaterialKey, Long>> batch) {
        for (Map.Entry<UUID, Map<MaterialKey, Long>> playerEntry : batch.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerEntry.getKey());
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
        }
    }

    private static void flushCrafted(MinecraftServer server,
                                     Map<UUID, Map<MaterialKey, Long>> batch) {
        for (Map.Entry<UUID, Map<MaterialKey, Long>> playerEntry : batch.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerEntry.getKey());
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
        }
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
        RsAutocraftProgressTracker.clear();
    }

    private static void clearPending() {
        PENDING_EXTERNAL.clear();
        PENDING_CRAFTED.clear();
    }

    private static long saturatedAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }
}
