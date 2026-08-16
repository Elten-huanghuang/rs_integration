package com.huanghuang.rsintegration.mixin.placebo;

import com.huanghuang.rsintegration.network.gui.RemotePlaceboMenuSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Supplies a one-shot client tile snapshot to cross-dimensional Placebo menus. */
@Pseudo
@Mixin(targets = "dev.shadowsoffire.placebo.menu.BlockEntityMenu", remap = false)
public abstract class PlaceboBlockEntityMenuMixin {
    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target =
                    "Lnet/minecraft/world/level/Level;m_7702_(Lnet/minecraft/core/BlockPos;)"
                            + "Lnet/minecraft/world/level/block/entity/BlockEntity;"),
            remap = false,
            require = 0)
    private BlockEntity rsi$useRemoteBlockEntitySnapshot(Level level, BlockPos pos) {
        BlockEntity snapshot = RemotePlaceboMenuSnapshot.take(level, pos);
        return snapshot != null ? snapshot : level.getBlockEntity(pos);
    }
}
