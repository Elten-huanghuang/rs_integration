package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskManager;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.OptionalLong;

/**
 * 灵魂在「当前存储后端」上的实时可达量。
 *
 * <p>预览和执行必须读同一个数，所以这里把来源判定收敛成唯一入口：{@code endpoint}
 * 说的是这次合成的存储端点，也就是灵魂的权威后端，不再按优先级猜测。</p>
 *
 * <p>三种来源：
 * <ul>
 *   <li>RS 端点：已挂载归墟盘上的可用灵魂（扣除预留）。这正是执行侧
 *       {@link MagicResourceReservations} 实际取用的那份库存；两者口径一致，
 *       所以预览说的“够”就是执行时真拿得到的。</li>
 *   <li>BD 端点：该 BD 网络的可用灵魂（扣除该网络上的全部预留）。</li>
 *   <li>其他/无端点：不可达，返回 {@link SourceKind#UNREACHABLE}，
 *       {@code authoritative} 为空。绝不能把它当成 0，否则预览会少算。</li>
 * </ul>
 */
public final class SoulPlanPreview {
    private SoulPlanPreview() {}

    /** 当前灵魂由哪一类存储后端回答。 */
    public enum SourceKind {
        /** Refined Storage 归墟盘。 */
        RS_DISK,
        /** Beyond Dimensions 网络。 */
        BD_NETWORK,
        /** 没有可用后端：纯原生来源或无端点。 */
        UNREACHABLE
    }

    /**
     * 灵魂可用量估算。
     *
     * @param total         本次合成需要的灵魂总量
     * @param authoritative 权威后端可达量；不可达时为空（不是 0）
     * @param source        回答这个数的后端类型
     */
    public record Estimate(long total, OptionalLong authoritative, SourceKind source) {
        public Estimate {
            total = Math.max(0, total);
            authoritative = authoritative == null ? OptionalLong.empty() : authoritative;
            source = source == null ? SourceKind.UNREACHABLE : source;
        }

        public boolean reachable() {
            return authoritative.isPresent();
        }

        /** 权威后端可达量；不可达时为 0，仅用于展示，不要当作“真的没有”。 */
        public long authoritativeOrZero() {
            return authoritative.orElse(0L);
        }

        /** 权威后端要承担的份额。 */
        public long backendShare() {
            return Math.min(total, authoritativeOrZero());
        }

        /** 需要机器原生来源/Arca 补足的份额。 */
        public long fallbackShare() {
            return Math.max(0, total - backendShare());
        }

        /** 与旧契约兼容的可用量；不可达时返回 -1。 */
        public long legacyAvailable() {
            return authoritative.orElse(-1);
        }
    }

    public static Estimate estimate(ServerPlayer player,
                                    @Nullable CraftStorageEndpoint endpoint, long total) {
        if (player == null) return new Estimate(total, OptionalLong.empty(), SourceKind.UNREACHABLE);
        if (CraftStorageEndpointsSupport.isRefinedStorageEndpoint(endpoint)) {
            UnifiedDiskManager manager = UnifiedDiskManager.existing(player.server);
            if (manager == null) {
                return new Estimate(total, OptionalLong.empty(), SourceKind.UNREACHABLE);
            }
            return new Estimate(total, OptionalLong.of(MagicDiskStore.available(
                    MagicDiskStore.mountedDisks(manager), MagicResourceKind.SOUL)),
                    SourceKind.RS_DISK);
        }
        int networkId = bdNetworkId(endpoint);
        if (networkId >= 0) {
            long available = BdReservationBridge.availableFor(player, networkId);
            return new Estimate(total, available < 0
                    ? OptionalLong.empty() : OptionalLong.of(available), SourceKind.BD_NETWORK);
        }
        return new Estimate(total, OptionalLong.empty(), SourceKind.UNREACHABLE);
    }

    /** 端点为 BD 时的网络号；其他端点返回 -1。 */
    private static int bdNetworkId(@Nullable CraftStorageEndpoint endpoint) {
        if (!CraftStorageEndpointsSupport.isBeyondDimensionsEndpoint(endpoint)) return -1;
        try {
            return Integer.parseInt(endpoint.session().reference().networkId());
        } catch (RuntimeException | LinkageError ignored) {
            return -1;
        }
    }
}
