package com.huanghuang.rsintegration.magic;

import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath.add;

/**
 * BD 网络灵魂的预留账本与反射访问边界。
 *
 * <p>这些内容原本内嵌在 {@link GoetySoulCraftingTask} 里，现按原样抽出，行为不变：
 * 预留按「网络号 + 玩家 UUID」的来源标识隔离，因为同一个网络号可能被不同网络
 * 重建后复用；退还时 BD 可达就按原样退回网络，不可达则保留 {@code remaining}
 * 交给任务层登记待核对记录。</p>
 *
 * <p>抽出成独立类型是为了让路由层的 BD 后端能复用同一套预留账，避免出现第二份
 * 记账。BD 调用一律不持锁，防止阻塞其他任务。</p>
 */
public final class BdReservationBridge {
    private static final Map<String, BdReservation> NO_RESERVATION = Map.of();
    private static final Map<String, List<BdReservation>> RESERVED_BY_BD =
            new LinkedHashMap<>();
    private static final Object RESERVATION_LOCK = new Object();

    private BdReservationBridge() {}

    /**
     * BD 预留的来源标识，用于区分「同一网络号在不同时刻指向的不同网络」。
     * 组合网络号与玩家 UUID 只需要常量开销，且在玩家离线时依然稳定。
     */
    public static String sourceOf(@Nullable ServerPlayer player, int networkId) {
        if (player == null) return Integer.toString(networkId);
        return networkId + "@" + player.getUUID();
    }

    /** 从来源标识里取回网络号，取不到时返回 -1。 */
    public static int parsedNetworkId(@Nullable String bdSource) {
        if (bdSource == null || bdSource.isEmpty()) return -1;
        int at = bdSource.indexOf('@');
        try {
            return Integer.parseInt(at < 0 ? bdSource : bdSource.substring(0, at));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** 在指定 BD 网络上预留灵魂；网络不可达或没有可用量时返回空的预留。 */
    public static GoetySoulNetworkPool reserve(ServerPlayer player, int networkId,
                                               long requested, String source) {
        return BdReservation.reserve(player, networkId, requested, source);
    }

    /**
     * 该 BD 网络上的可用量（扣除全部来源的预留），用于没有具体任务的探测。
     * 读数失败时返回 -1，与「盘上确实是空的」区分开。
     */
    public static long availableFor(ServerPlayer player, int networkId) {
        Long amount = amount(player, networkId);
        if (amount == null) return -1;
        synchronized (RESERVATION_LOCK) {
            return Math.max(0, amount - reservedFor("", networkId));
        }
    }

    /**
     * 该 BD 来源已预留的量。{@code bdSource} 为空时统计该网络 ID 上的全部来源，
     * 用于没有具体任务的可用量探测（其他玩家的预留同样不可用）。
     */
    public static long reservedFor(@Nullable String bdSource, int networkId) {
        List<List<BdReservation>> groups = bdSource == null || bdSource.isEmpty()
                ? List.copyOf(RESERVED_BY_BD.values())
                : List.of(RESERVED_BY_BD.getOrDefault(bdSource, List.of()));
        long total = 0;
        for (List<BdReservation> entries : groups) {
            for (BdReservation entry : entries) {
                if (entry.networkId == networkId) total = add(total, entry.remaining);
            }
        }
        return total;
    }

    /**
     * 取出该预留中尚未登记为待核对记录的剩余量；不是 BD 预留时返回 0。
     * 取出后再调用返回 0，所以登记待核对记录是幂等的。
     */
    public static long takeUnaccounted(@Nullable GoetySoulNetworkPool pool) {
        return pool instanceof BdReservation bd ? bd.takeUnaccounted() : 0;
    }

    /** 当前 BD 网络上的灵魂存量；读不出来时返回 {@code null}。 */
    @Nullable
    public static Long amount(ServerPlayer player, int networkId) {
        return callBd("amount", player, networkId, 0);
    }

    /**
     * 向 BD 网络写入；网络不可达时抛出，而不是返回 0 让上层以为是「接受 0」。
     *
     * @throws IllegalStateException BD 网络不可达
     */
    public static long insertOrThrow(ServerPlayer player, int networkId, long amount) {
        Long accepted = callBd("insert", player, networkId, amount);
        if (accepted == null) {
            throw new IllegalStateException("BD 网络不可达，无法写入 networkId=" + networkId);
        }
        return accepted;
    }

    /**
     * 从 BD 网络取出；网络不可达时抛出。
     *
     * @throws IllegalStateException BD 网络不可达
     */
    public static long extractOrThrow(ServerPlayer player, int networkId, long amount) {
        Long extracted = callBd("extract", player, networkId, amount);
        if (extracted == null) {
            throw new IllegalStateException("BD 网络不可达，无法取出 networkId=" + networkId);
        }
        return extracted;
    }

    /**
     * BD 库存的反射边界；BD 未安装或 API 不匹配时返回 {@code null}，
     * 让调用方按「不可达」处理，而不是误判成存量 0。
     */
    @Nullable
    private static Long callBd(String method, ServerPlayer player,
                               int networkId, long amount) {
        try {
            Class<?> type = Class.forName(
                    "com.huanghuang.rsintegration.storage.bd.BeyondDimensionsSoulStorage");
            Method call = type.getMethod(method, ServerPlayer.class, int.class, long.class);
            return (Long) call.invoke(null, player, networkId, amount);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static final class BdReservation implements GoetySoulNetworkPool {
        private final ServerPlayer player;
        private final int networkId;
        /** 网络来源标识；同一网络号可能被不同网络重建后复用，预留必须按来源隔离。 */
        private final String source;
        private long remaining;

        private BdReservation(ServerPlayer player, int networkId, String source) {
            this.player = player;
            this.networkId = networkId;
            this.source = source == null ? "" : source;
        }

        private static BdReservation reserve(ServerPlayer player, int networkId,
                                             long requested, String source) {
            BdReservation result = new BdReservation(player, networkId, source);
            if (networkId < 0 || requested <= 0) return result;
            synchronized (RESERVATION_LOCK) {
                Long raw = callBd("amount", player, networkId, 0);
                if (raw == null) return result;
                long available = Math.max(0, raw - reservedFor(result.source, networkId));
                long take = Math.min(available, requested);
                if (take > 0) {
                    result.remaining = take;
                    RESERVED_BY_BD
                            .computeIfAbsent(result.source, ignored -> new ArrayList<>())
                            .add(result);
                }
            }
            return result;
        }

        @Override public long remaining() {
            return remaining;
        }

        /** 取出尚未登记为待核对记录的剩余量；重复调用返回 0。 */
        synchronized long takeUnaccounted() {
            long value = remaining;
            remaining = 0;
            return value;
        }

        @Override public long withdraw(long requested) {
            if (requested <= 0 || remaining <= 0) return 0;
            long want = Math.min(requested, remaining);
            Long extracted = callBd("extract", player, networkId, want);
            if (extracted == null || extracted <= 0) return 0;
            long confirmed = Math.min(extracted, remaining);
            synchronized (RESERVATION_LOCK) {
                remaining -= confirmed;
            }
            return confirmed;
        }

        /**
         * 退回预留。BD 不可达时不能把预留算作已退回，也不能清零：这里保留
         * {@code remaining}，由 {@code close()} 登记为待核对记录。
         */
        @Override public void refund(long amount) {
            if (amount <= 0) return;
            Long accepted = callBd("insert", player, networkId, amount);
            if (accepted == null || accepted <= 0) return;
            synchronized (RESERVATION_LOCK) {
                remaining += accepted;
            }
        }

        @Override public void close() {
            long outstanding;
            synchronized (RESERVATION_LOCK) {
                outstanding = remaining;
            }
            // BD 可达时按原样退回网络；不可达时保留 remaining，交给任务层登记
            // 待核对记录，等待跨网络重连校验。BD 调用不持锁，避免阻塞其他任务。
            Long accepted = outstanding <= 0
                    ? null : callBd("insert", player, networkId, outstanding);
            synchronized (RESERVATION_LOCK) {
                if (accepted != null && accepted > 0) {
                    remaining -= Math.min(accepted, remaining);
                }
                List<BdReservation> entries = RESERVED_BY_BD.get(source);
                if (entries != null) {
                    entries.remove(this);
                    if (entries.isEmpty()) RESERVED_BY_BD.remove(source);
                }
            }
        }
    }
}
