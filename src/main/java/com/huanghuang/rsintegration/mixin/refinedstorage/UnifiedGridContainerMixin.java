package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.config.RSStorageConfig;
import com.huanghuang.rsintegration.unifiedgrid.UnifiedGridMenuAccess;
import com.huanghuang.rsintegration.unifiedgrid.UnifiedGridSession;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GridContainerMenu.class, remap = false)
public abstract class UnifiedGridContainerMixin implements UnifiedGridMenuAccess {
    @Unique private UnifiedGridSession rsi$session;
    @Unique private boolean rsi$initialized;

    @Override public UnifiedGridSession rsi$unifiedSession() { return rsi$session; }

    @Inject(method = "m_38946_", at = @At("HEAD"))
    private void rsi$initializeMixedGrid(CallbackInfo ci) {
        GridContainerMenu menu = (GridContainerMenu) (Object) this;
        if (!rsi$initialized && menu.getPlayer() instanceof ServerPlayer player) {
            rsi$initialized = true;
            if (RSStorageConfig.enabled(RSStorageConfig.UNIFIED_GRID) && UnifiedGridSession.supports(menu.getGrid()))
                rsi$session = new UnifiedGridSession(menu, player);
        }
    }

    @Redirect(method = "m_38946_", at = @At(value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/api/network/grid/IGrid;getStorageCache()Lcom/refinedmods/refinedstorage/api/storage/cache/IStorageCache;"))
    private IStorageCache<?> rsi$ownBothSubscriptions(IGrid grid) {
        return rsi$session != null && rsi$session.enabled() ? null : grid.getStorageCache();
    }

    @Inject(method = "m_38946_", at = @At("TAIL"))
    private void rsi$flushMixedGrid(CallbackInfo ci) {
        if (rsi$session != null) rsi$session.tick();
    }

    @Inject(method = "m_6877_", at = @At("TAIL"))
    private void rsi$closeMixedGrid(Player player, CallbackInfo ci) {
        if (rsi$session != null) rsi$session.close();
    }
}
