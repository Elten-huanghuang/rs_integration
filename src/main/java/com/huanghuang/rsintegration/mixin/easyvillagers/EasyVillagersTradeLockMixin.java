package com.huanghuang.rsintegration.mixin.easyvillagers;

import com.huanghuang.rsintegration.villager.tradelock.VillagerTradeLockHooks;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Guards Easy Villagers' server-side trade-cycling entry point. */
@Pseudo
@Mixin(targets = "de.maxhenkel.easyvillagers.events.GuiEvents", remap = false)
public abstract class EasyVillagersTradeLockMixin {
    @Inject(method = "onCycleTrades", at = @At("HEAD"), cancellable = true, remap = false)
    private static void rsIntegration$lockBookmarkedTrade(ServerPlayer player,
                                                           CallbackInfo callback) {
        if (VillagerTradeLockHooks.blockRefresh(player)) callback.cancel();
    }
}
