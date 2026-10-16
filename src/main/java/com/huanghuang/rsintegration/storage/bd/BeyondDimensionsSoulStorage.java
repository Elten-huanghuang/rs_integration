package com.huanghuang.rsintegration.storage.bd;

import com.huanghuang.rsintegration.magic.GoetySoulStackKey;
import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import net.minecraft.server.level.ServerPlayer;

/** 仅在 BD 已加载时加载的直接灵魂库存写入器。 */
public final class BeyondDimensionsSoulStorage {
    private BeyondDimensionsSoulStorage() {}

    public static int amount(ServerPlayer player, int networkId) {
        if (networkId < 0 || !BeyondDimensionsReflection.isAuthorizedNetwork(player, networkId)) return 0;
        DimensionsNet network = DimensionsNet.getNetFromId(networkId);
        if (network == null) return 0;
        KeyAmount current = network.getUnifiedStorage().getStackByKey(GoetySoulStackKey.INSTANCE);
        return current == null ? 0
                : (int) Math.min(Integer.MAX_VALUE, Math.max(0L, current.amount()));
    }

    public static int insert(ServerPlayer player, int networkId, int amount) {
        if (amount <= 0 || networkId < 0
                || !BeyondDimensionsReflection.isAuthorizedNetwork(player, networkId)) return 0;
        DimensionsNet network = DimensionsNet.getNetFromId(networkId);
        if (network == null) return 0;
        KeyAmount remainder = network.getUnifiedStorage().insert(
                GoetySoulStackKey.INSTANCE, amount, false);
        long accepted = amount - (remainder == null ? 0L : remainder.amount());
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, accepted));
    }

    public static int extract(ServerPlayer player, int networkId, int amount) {
        if (amount <= 0 || networkId < 0
                || !BeyondDimensionsReflection.isAuthorizedNetwork(player, networkId)) return 0;
        DimensionsNet network = DimensionsNet.getNetFromId(networkId);
        if (network == null) return 0;
        KeyAmount extracted = network.getUnifiedStorage().extract(
                GoetySoulStackKey.INSTANCE, amount, false, false);
        return extracted == null ? 0
                : (int) Math.min(Integer.MAX_VALUE, Math.max(0L, extracted.amount()));
    }
}
