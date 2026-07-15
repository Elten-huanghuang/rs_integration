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

    private static final Map<UUID, Map<MaterialKey, Long>> PENDING = new LinkedHashMap<>();
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
        if (!enabled) PENDING.clear();
        if (enabled) {
            RSIntegrationMod.LOGGER.info("Enabled FTB Quests progress for external backpack/RS insertions");
        } else if (ModList.get().isLoaded(ModIds.YZZZ_OPTIMIZATION)) {
            RSIntegrationMod.LOGGER.info("FTB external item progress disabled because yzzzoptimization already provides it");
        }
    }

    public static void enqueue(ServerPlayer player, ItemStack inserted) {
        if (!enabled || player == null || inserted == null || inserted.isEmpty()) return;
        MaterialKey key = MaterialKey.of(inserted);
        PENDING.computeIfAbsent(player.getUUID(), ignored -> new LinkedHashMap<>())
                .merge(key, (long) inserted.getCount(), ExternalItemProgressBridge::saturatedAdd);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!enabled || event.phase != TickEvent.Phase.END || PENDING.isEmpty()) return;
        MinecraftServer server = event.getServer();
        Map<UUID, Map<MaterialKey, Long>> batch = new LinkedHashMap<>(PENDING);
        PENDING.clear();
        for (Map.Entry<UUID, Map<MaterialKey, Long>> playerEntry : batch.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerEntry.getKey());
            if (player == null) continue;
            try {
                FtbQuestExternalItemDetector.detect(player, playerEntry.getValue());
            } catch (LinkageError | RuntimeException exception) {
                RSIntegrationMod.LOGGER.warn("Failed to apply external item progress for {}",
                        player.getGameProfile().getName(), exception);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PENDING.clear();
    }

    private static long saturatedAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }
}
