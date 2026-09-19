package com.huanghuang.rsintegration.mixin.constructionwand;

import com.huanghuang.rsintegration.client.JeiNetworkItemCache;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds the synchronized network stock to Construction Wand's client preview. */
@Pseudo
@Mixin(targets = "thetadev.constructionwand.basics.WandUtil", remap = false)
public abstract class ConstructionWandClientNetworkMixin {
    @Inject(method = "countItem", at = @At("RETURN"), cancellable = true, require = 0)
    private static void rsi$countNetworkPreview(Player player, Item item,
                                                 CallbackInfoReturnable<Integer> callback) {
        if (item == null || !JeiNetworkItemCache.INSTANCE.isConnected()) return;
        long networkCount = JeiNetworkItemCache.INSTANCE.amount(item);
        if (networkCount <= 0L) return;

        int localCount = callback.getReturnValue();
        callback.setReturnValue(networkCount >= Integer.MAX_VALUE - (long) localCount
                ? Integer.MAX_VALUE : localCount + (int) networkCount);
    }
}
