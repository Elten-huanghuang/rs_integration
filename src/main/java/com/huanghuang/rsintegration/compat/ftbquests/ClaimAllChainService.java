package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.RSIntegrationMod;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Continues FTB's native claim-all operation across newly unlocked quest layers. */
public final class ClaimAllChainService {

    private static final int MAX_CLAIMS_PER_PLAYER_PER_TICK = 256;
    private static final int MAX_IDLE_TICKS = 40;
    private static final int MAX_LIFETIME_TICKS = 200;
    private static final Map<UUID, ChainJob> ACTIVE_JOBS = new HashMap<>();

    private ClaimAllChainService() {
    }

    public static void schedule(ServerPlayer player) {
        if (player == null) return;
        long now = player.server.getTickCount();
        ACTIVE_JOBS.compute(player.getUUID(), (ignored, existing) -> {
            if (existing == null) return new ChainJob(now + MAX_LIFETIME_TICKS);
            existing.deadline = Math.max(existing.deadline, now + MAX_LIFETIME_TICKS);
            existing.idleTicks = 0;
            return existing;
        });
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE_JOBS.isEmpty()) return;
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();
        Iterator<Map.Entry<UUID, ChainJob>> iterator = ACTIVE_JOBS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ChainJob> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            ChainJob job = entry.getValue();
            if (player == null || now > job.deadline) {
                iterator.remove();
                continue;
            }

            try {
                int claimed = claimAvailable(player);
                job.idleTicks = claimed > 0 ? 0 : job.idleTicks + 1;
            } catch (RuntimeException | LinkageError exception) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-FTBQuests] Failed to continue claim-all chain for {}",
                        player.getGameProfile().getName(), exception);
                iterator.remove();
                continue;
            }
            if (job.idleTicks >= MAX_IDLE_TICKS) iterator.remove();
        }
    }

    private static int claimAvailable(ServerPlayer player) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        TeamData data = TeamData.get(player);
        if (file == null || file.isLoading() || data == null || data.isLocked()) return 0;

        int[] claimed = {0};
        file.withPlayerContext(player, () -> file.forAllQuests(quest -> {
            if (claimed[0] >= MAX_CLAIMS_PER_PLAYER_PER_TICK || !data.isCompleted(quest)) return;
            for (Reward reward : quest.getRewards()) {
                if (claimed[0] >= MAX_CLAIMS_PER_PLAYER_PER_TICK) return;
                if (reward.getExcludeFromClaimAll()
                        || data.isRewardBlocked(reward)
                        || !data.getClaimType(player.getUUID(), reward).canClaim()) {
                    continue;
                }
                try (FtbQuestRewardDropContext.Scope ignored = FtbQuestRewardDropContext.activate()) {
                    data.claimReward(player, reward, true);
                }
                claimed[0]++;
            }
        }));
        return claimed[0];
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ACTIVE_JOBS.clear();
    }

    private static final class ChainJob {
        private long deadline;
        private int idleTicks;

        private ChainJob(long deadline) {
            this.deadline = deadline;
        }
    }
}
