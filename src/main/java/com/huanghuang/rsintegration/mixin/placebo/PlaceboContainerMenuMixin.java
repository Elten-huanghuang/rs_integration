package com.huanghuang.rsintegration.mixin.placebo;

import com.huanghuang.rsintegration.network.gui.RemoteMenuConstructionContext;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes Placebo block-entity menus resolve their tile from the remote target level. */
@Pseudo
@Mixin(targets = "dev.shadowsoffire.placebo.menu.PlaceboContainerMenu", remap = false)
public abstract class PlaceboContainerMenuMixin {
    @Shadow @Final @Mutable protected Level level;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false, require = 0)
    private void rsi$useRemoteTargetLevel(MenuType<?> menuType, int containerId,
                                          Inventory inventory, CallbackInfo ci) {
        Level targetLevel = RemoteMenuConstructionContext.targetLevel(inventory.player);
        if (targetLevel != null) {
            this.level = targetLevel;
        }
    }
}
