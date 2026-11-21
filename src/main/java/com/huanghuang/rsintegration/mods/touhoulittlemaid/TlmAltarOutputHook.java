package com.huanghuang.rsintegration.mods.touhoulittlemaid;

import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.function.Predicate;

public interface TlmAltarOutputHook {
    void rsi$setAltarOutputReceiver(@Nullable Predicate<Entity> receiver);
}
