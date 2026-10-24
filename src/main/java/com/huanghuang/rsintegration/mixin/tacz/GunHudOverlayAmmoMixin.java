package com.huanghuang.rsintegration.mixin.tacz;

import com.huanghuang.rsintegration.mods.tacz.TaczResonanceAmmoHandler;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.gui.overlay.GunHudOverlay;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 将共振盘弹药纳入原生 HUD 的备弹缓存，兼容 TaCZ 新旧 HUD 实现。 */
@Mixin(value = GunHudOverlay.class, remap = false)
public abstract class GunHudOverlayAmmoMixin {
    @Shadow private static int cacheInventoryAmmoCount;
    @Shadow private static long checkAmmoTimestamp;
    @Unique private static long rsi$lastIncludedAmmoTimestamp = Long.MIN_VALUE;

    @Inject(method = TaczMixinTargets.GUN_HUD_HANDLE_CACHE_COUNT_METHOD,
            at = @At("RETURN"), remap = false, require = 1)
    private static void rsi$includeResonanceAmmo(
            LocalPlayer player, ItemStack gun, GunData gunData, IGun gunItem,
            CallbackInfo callback) {
        if (!rsi$shouldIncludeCache(checkAmmoTimestamp, rsi$lastIncludedAmmoTimestamp)) return;
        rsi$lastIncludedAmmoTimestamp = checkAmmoTimestamp;
        cacheInventoryAmmoCount =
                TaczResonanceAmmoHandler.includeClientAmmoCount(gun, cacheInventoryAmmoCount);
    }

    private static boolean rsi$shouldIncludeCache(
            long nativeTimestamp, long lastIncludedTimestamp) {
        return nativeTimestamp != lastIncludedTimestamp;
    }
}
