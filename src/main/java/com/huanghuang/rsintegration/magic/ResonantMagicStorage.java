package com.huanghuang.rsintegration.magic;

import net.minecraft.nbt.CompoundTag;

/** 共振魔法容器的统一 RS 绑定与库存视图。 */
public interface ResonantMagicStorage {
    MagicResourceKind resourceKind();
    int resourceCapacity();
    boolean isBound();
    CompoundTag binding();
    void setBinding(CompoundTag next);
    void clearBinding();
    boolean isBoundTo(CompoundTag next);
    int currentAmount();
    boolean reachable();
    int insert(int amount);
    int extract(int amount);
    void serverTick();
}
