package com.huanghuang.rsintegration.feature.resonance.magic;

import com.huanghuang.rsintegration.magic.MagicResourceKind;

import net.minecraft.nbt.CompoundTag;

/** 共振魔法容器的统一 RS 绑定与库存视图。 */
public interface ResonantMagicStorage {
    MagicResourceKind resourceKind();
    long resourceCapacity();
    boolean isBound();
    CompoundTag binding();
    void setBinding(CompoundTag next);
    void clearBinding();
    boolean isBoundTo(CompoundTag next);
    long currentAmount();
    boolean reachable();
    int insert(int amount);
    int extract(int amount);
    void serverTick();
}
