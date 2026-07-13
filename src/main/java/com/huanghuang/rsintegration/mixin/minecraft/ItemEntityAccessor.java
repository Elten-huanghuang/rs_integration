package com.huanghuang.rsintegration.mixin.minecraft;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Exposes {@link ItemEntity}'s private {@code thrower} UUID, which has a setter
 * but no public getter in 1.20.1. Used by {@code CraftOutputInterceptor} to skip
 * player-thrown items (thrower != null) when capturing machine craft outputs.
 */
@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {

    @Accessor("thrower")
    @Nullable
    UUID rsi$getThrower();
}
