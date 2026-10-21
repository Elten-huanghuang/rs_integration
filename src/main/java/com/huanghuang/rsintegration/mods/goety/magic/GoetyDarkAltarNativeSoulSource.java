package com.huanghuang.rsintegration.mods.goety.magic;

import com.huanghuang.rsintegration.reflection.probes.GoetyReflection;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;

/** 黑暗祭坛可用的原生灵魂来源：诅咒笼中的灵魂容器，以及玩家激活的 Arca。 */
public final class GoetyDarkAltarNativeSoulSource implements GoetyNativeSoulSource {
    private final Object altar;
    private final ServerPlayer player;
    private final Object cage;

    public GoetyDarkAltarNativeSoulSource(Object altar, ServerPlayer player) {
        this.altar = altar;
        this.player = player;
        this.cage = findCage(altar);
    }

    @Override
    public int available() {
        if (this.cage == null) return arcaSouls();

        int cageSouls = souls(this.cage);
        Object owner = Reflect.invoke(this.cage, "getOwner").orElse(null);
        // 绑定所有者的诅咒笼读取的就是该玩家 Arca，不能再次重复相加。
        if (owner != null) return Math.max(0, cageSouls);
        return GoetySoulSourceCoordinator.combine(cageSouls, arcaSouls());
    }

    @Override
    public int consume(int requested) {
        if (requested <= 0) return 0;
        if (available() < requested) return 0;
        if (this.cage == null) return consumeArca(requested);

        int cageSouls = Math.max(0, souls(this.cage));
        Object owner = Reflect.invoke(this.cage, "getOwner").orElse(null);
        int fromCage = Math.min(requested, cageSouls);
        if (fromCage > 0) {
            decrease(this.cage, fromCage);
        }

        int remaining = requested - fromCage;
        if (remaining <= 0) return fromCage;
        // owner 非空时 cage 已经代表玩家 Arca，不能再从同一来源扣第二次。
        if (owner != null) return fromCage;
        return fromCage + consumeArca(remaining);
    }

    public Object cage() {
        return cage;
    }

    @Nullable
    public static Object findCage(Object altar) {
        if (altar == null) return null;
        Object current = Reflect.getField(
                altar, GoetyReflection.F_CURSED_CAGE_TILE).orElse(null);
        if (current != null) return current;
        if (!(altar instanceof BlockEntity blockEntity)) {
            return null;
        }
        Level level = blockEntity.getLevel();
        if (level == null || GoetyReflection.cursedCageBEClass == null) return null;
        BlockPos below = blockEntity.getBlockPos().below();
        if (!level.isLoaded(below)) return null;
        BlockEntity candidate = level.getBlockEntity(below);
        if (!GoetyReflection.cursedCageBEClass.isInstance(candidate)) return null;
        Reflect.setField(altar, GoetyReflection.F_CURSED_CAGE_TILE, candidate);
        return candidate;
    }

    private static int souls(Object cage) {
        return GoetyVirtualSoulAccess.physicalSouls(cage);
    }

    private static void decrease(Object cage, int amount) {
        GoetyVirtualSoulAccess.physicalDecrease(cage, amount);
    }

    private int arcaSouls() {
        return GoetyPlayerSoulAccess.souls(player);
    }

    private int consumeArca(int requested) {
        return GoetyPlayerSoulAccess.consume(player, requested);
    }
}
