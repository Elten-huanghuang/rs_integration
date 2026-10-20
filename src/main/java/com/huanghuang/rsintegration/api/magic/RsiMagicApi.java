package com.huanghuang.rsintegration.api.magic;

import com.huanghuang.rsintegration.magic.MagicResourceEndpoint;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.magic.MagicResourceNetworkPool;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.server.MinecraftServer;

/**
 * RSI 的 RS 魔法资源公共入口。
 *
 * <p>该 API 只读取 RSI 归墟盘账本，不会访问 BD 或其它模组的库存。所有数量都
 * 是原生单位的 {@code long} 值，预留和实际提取遵循同一套磁盘预留账本。</p>
 */
public final class RsiMagicApi {
    private RsiMagicApi() {}

    public static Session server(MinecraftServer server) {
        return new ServerSession(server);
    }

    public static Session network(INetwork network) {
        return new NetworkSession(network);
    }

    public static boolean supportsRecursiveCrafting(MagicResourceKind kind) {
        return kind != null && kind.available() && kind.supportsRsiRecursiveCrafting();
    }

    public interface Session {
        long amount(MagicResourceKind kind);
        long available(MagicResourceKind kind);
        long insert(MagicResourceKind kind, long amount, boolean simulate);
        long extract(MagicResourceKind kind, long amount, boolean simulate);
        MagicResourceNetworkPool reserve(MagicResourceKind kind, long requested);
    }

    private record ServerSession(MinecraftServer server) implements Session {
        @Override public long amount(MagicResourceKind kind) {
            return kind == null ? 0 : MagicResourceEndpoint.total(server, kind);
        }

        @Override public long available(MagicResourceKind kind) {
            return kind == null ? 0 : MagicResourceEndpoint.available(server, kind);
        }

        @Override public long insert(MagicResourceKind kind, long amount, boolean simulate) {
            return kind == null ? 0
                    : MagicResourceEndpoint.insert(server, kind, amount, !simulate);
        }

        @Override public long extract(MagicResourceKind kind, long amount, boolean simulate) {
            return kind == null ? 0
                    : MagicResourceEndpoint.extract(server, kind, amount, !simulate);
        }

        @Override public MagicResourceNetworkPool reserve(MagicResourceKind kind, long requested) {
            return MagicResourceEndpoint.reserve(server, kind, requested);
        }
    }

    private record NetworkSession(INetwork network) implements Session {
        @Override public long amount(MagicResourceKind kind) {
            return kind == null ? 0 : MagicResourceEndpoint.total(network, kind);
        }

        @Override public long available(MagicResourceKind kind) {
            return kind == null ? 0 : MagicResourceEndpoint.available(network, kind);
        }

        @Override public long insert(MagicResourceKind kind, long amount, boolean simulate) {
            return kind == null ? 0
                    : MagicResourceEndpoint.insert(network, kind, amount, !simulate);
        }

        @Override public long extract(MagicResourceKind kind, long amount, boolean simulate) {
            return kind == null ? 0
                    : MagicResourceEndpoint.extract(network, kind, amount, !simulate);
        }

        @Override public MagicResourceNetworkPool reserve(MagicResourceKind kind, long requested) {
            return MagicResourceEndpoint.reserve(network, kind, requested);
        }
    }
}
