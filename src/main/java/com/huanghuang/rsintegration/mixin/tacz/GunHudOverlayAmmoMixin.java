package com.huanghuang.rsintegration.mixin.tacz;

import com.huanghuang.rsintegration.mods.tacz.TaczResonanceAmmoHandler;
import com.tacz.guns.client.gui.overlay.GunHudOverlay;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 将共振盘弹药纳入原生 HUD 的备弹缓存，沿用原有背包和弹药箱计数。 */
@Mixin(value = GunHudOverlay.class, remap = false)
public abstract class GunHudOverlayAmmoMixin {
    @Shadow private static int cacheInventoryAmmoCount;

    @Inject(method = "handleInventoryAmmo", at = @At("RETURN"), remap = false, require = 1)
    private static void rsi$includeResonanceAmmo(ItemStack gun, Inventory inventory, CallbackInfo callback) {
        cacheInventoryAmmoCount = TaczResonanceAmmoHandler.includeClientAmmoCount(gun, cacheInventoryAmmoCount);
    }
}
