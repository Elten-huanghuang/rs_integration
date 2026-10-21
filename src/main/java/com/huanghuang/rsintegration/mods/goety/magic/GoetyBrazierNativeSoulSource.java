package com.huanghuang.rsintegration.mods.goety.magic;

import com.huanghuang.rsintegration.reflection.probes.GoetyReflection;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** 亡灵火盆的原生灵魂来源，保留灵魂烛台和 Arca 作为网络不足时的补差。 */
public final class GoetyBrazierNativeSoulSource implements GoetyNativeSoulSource {
    private final ServerPlayer player;
    private final BlockEntity brazier;
    private List<Object> candles = List.of();
    private long lastScan = Long.MIN_VALUE;

    public GoetyBrazierNativeSoulSource(ServerPlayer player, BlockEntity brazier) {
        this.player = player;
        this.brazier = brazier;
    }

    public List<Object> candles() {
        refreshCandles();
        return candles;
    }

    @Override
    public int available() {
        refreshCandles();
        int independent = 0;
        for (Object candle : candles) {
            Object cage = cage(candle);
            if (cage == null || owner(cage) != null) continue;
            independent = GoetySoulSourceCoordinator.combine(
                    independent, souls(cage));
        }
        return GoetySoulSourceCoordinator.combine(
                independent, GoetyPlayerSoulAccess.souls(player));
    }

    @Override
    public int consume(int requested) {
        if (requested <= 0) return 0;
        if (available() < requested) return 0;
        refreshCandles();
        int moved = 0;
        for (Object candle : candles) {
            if (moved >= requested) break;
            Object cage = cage(candle);
            if (cage == null || owner(cage) != null) continue;
            int take = Math.min(requested - moved, Math.max(0, souls(cage)));
            if (take <= 0) continue;
            decrease(cage, take);
            moved += take;
        }
        int remaining = requested - moved;
        if (remaining <= 0) return moved;
        return moved + GoetyPlayerSoulAccess.consume(player, remaining);
    }

    private void refreshCandles() {
        ServerLevel level = level();
        if (level == null) {
            candles = List.of();
            return;
        }
        long now = level.getGameTime();
        if (now - lastScan < 40L) return;
        lastScan = now;
        if (GoetyReflection.soulCandlestickBEClass == null) {
            candles = List.of();
            return;
        }
        List<Object> found = new ArrayList<>();
        BlockPos center = brazier.getBlockPos();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-8, -8, -8), center.offset(8, 8, 8))) {
            if (!level.isLoaded(pos)) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if (GoetyReflection.soulCandlestickBEClass.isInstance(be)) {
                found.add(be);
            }
        }
        candles = List.copyOf(found);
    }

    @Nullable
    private ServerLevel level() {
        return brazier.getLevel() instanceof ServerLevel serverLevel ? serverLevel : null;
    }

    @Nullable
    private static Object cage(Object candle) {
        return Reflect.getField(candle, "cursedCageTile").orElse(null);
    }

    @Nullable
    private static Object owner(Object cage) {
        return Reflect.invoke(cage, "getOwner").orElse(null);
    }

    private static int souls(Object cage) {
        return GoetyVirtualSoulAccess.physicalSouls(cage);
    }

    private static void decrease(Object cage, int amount) {
        GoetyVirtualSoulAccess.physicalDecrease(cage, amount);
    }
}
