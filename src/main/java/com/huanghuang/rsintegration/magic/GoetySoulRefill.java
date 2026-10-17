package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 灵魂方舟消耗后的 RS 反向补货入口。 */
public final class GoetySoulRefill {
    static final long RETRY_COOLDOWN_TICKS = 20L;

    private static final Map<UUID, Long> NEXT_ATTEMPT = new HashMap<>();
    private static final ThreadLocal<Integer> SUPPRESSION_DEPTH =
            ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> LOSS_BEFORE = new ThreadLocal<>();

    private GoetySoulRefill() {}

    public static void beginLoss(Player rawPlayer) {
        if (rawPlayer instanceof ServerPlayer player) {
            LOSS_BEFORE.set(GoetyPlayerSoulAccess.souls(player));
        } else {
            LOSS_BEFORE.remove();
        }
    }

    public static void afterLoss(Player rawPlayer) {
        Integer before = LOSS_BEFORE.get();
        LOSS_BEFORE.remove();
        if (!(rawPlayer instanceof ServerPlayer player) || before == null) return;
        int lostSouls = Math.max(0, before - GoetyPlayerSoulAccess.souls(player));
        if (lostSouls <= 0) return;
        if (isSuppressed() || !RSIntegrationConfig.ENABLE_GOETY_SOUL_REVERSE_REFILL.get()) {
            return;
        }
        if (ModList.get() == null || !ModList.get().isLoaded("refinedstorage")) return;

        long now = player.serverLevel().getGameTime();
        UUID playerId = player.getUUID();
        Long nextAttempt = NEXT_ATTEMPT.get(playerId);
        if (nextAttempt != null && now < nextAttempt) return;
        NEXT_ATTEMPT.put(playerId, now + RETRY_COOLDOWN_TICKS);

        int requested = refillRequest(
                GoetyPlayerSoulAccess.souls(player), GoetySoulOverflow.maxArcaSouls(), lostSouls);
        if (requested <= 0) return;
        invokeBackend(player, requested);
    }

    /** RS 合成正在自行结算原生与磁盘来源时，禁止补货再次抽取磁盘。 */
    public static Scope suspend() {
        int previous = SUPPRESSION_DEPTH.get();
        SUPPRESSION_DEPTH.set(previous + 1);
        return new Scope(previous);
    }

    static int refillRequest(int current, int capacity, int lostSouls) {
        if (lostSouls <= 0) return 0;
        return Math.max(0, capacity - Math.max(0, current));
    }

    private static boolean isSuppressed() {
        return SUPPRESSION_DEPTH.get() > 0;
    }

    private static void invokeBackend(ServerPlayer player, int requested) {
        try {
            Class<?> type = Class.forName(
                    "com.huanghuang.rsintegration.magic.GoetySoulRefillRs");
            Method refill = type.getMethod("refill", ServerPlayer.class, int.class);
            refill.invoke(null, player, requested);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // RS 不可用或接口变化时保持原版灵魂消耗行为。
        }
    }

    public static final class Scope implements AutoCloseable {
        private final int previous;
        private boolean closed;

        private Scope(int previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous <= 0) SUPPRESSION_DEPTH.remove();
            else SUPPRESSION_DEPTH.set(previous);
        }
    }
}
