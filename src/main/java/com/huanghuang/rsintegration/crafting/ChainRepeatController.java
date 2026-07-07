package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Stateless recursive repeat controller for AsyncCraftChain.
 * Consolidates the "onDone → check abort → schedule next" pattern
 * repeated across async chain resolution paths.
 */
public final class ChainRepeatController {

    private ChainRepeatController() {}

    /**
     * Schedules the next repeat after the current chain completes.
     * Call from inside an {@link AsyncCraftChain#onDone} callback.
     *
     * @param chain            the just-completed chain
     * @param server           server instance for player lookup
     * @param playerId         UUID of the player who initiated the chain
     * @param remainingRepeats how many repeats remain (including the just-finished one)
     * @param producedThisRound how many items were produced this chain run
     *                          (1 for single machine, N for parallel)
     * @param resolver         callback to start the next chain: {@code (player, remaining) -> void}
     */
    public static void scheduleNext(AsyncCraftChain chain, MinecraftServer server, UUID playerId,
                                     int remainingRepeats, int producedThisRound,
                                     RepeatResolver resolver) {
        if (chain.isAborted()) {
            int skipped = remainingRepeats - producedThisRound;
            if (skipped > 0) {
                PlayerUtils.safeSendMessage(server, playerId,
                        Component.translatable("rsi.repeat.aborted", skipped));
            }
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Repeat] Chain aborted (reason={}) — skipping {} remaining repeats",
                    chain.abortReason(), skipped);
            return;
        }

        int next = remainingRepeats - producedThisRound;
        if (next <= 0) {
            RSIntegrationMod.LOGGER.debug("[RSI-Repeat] All repeats completed");
            return;
        }
        int maxRepeats = RSIntegrationConfig.REPEAT_COUNT_MAX.get();
        if (next > maxRepeats) {
            RSIntegrationMod.LOGGER.warn("[RSI-Repeat] Clamping repeat count {} -> {}", next, maxRepeats);
            next = maxRepeats;
        }

        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Repeat] Player {} offline, skipping {} remaining repeats",
                    playerId, next);
            return;
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Repeat] Scheduling next repeat: {} remaining", next);
        resolver.resolve(online, next);
    }

    @FunctionalInterface
    public interface RepeatResolver {
        void resolve(ServerPlayer player, int remainingRepeats);
    }
}
