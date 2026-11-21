package com.huanghuang.rsintegration.mixin.tarotcards;

import com.huanghuang.rsintegration.mods.tarotcards.TarotResonanceAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import shiroroku.tarotcards.Item.TarotItem;

@Mixin(value = TarotItem.class, remap = false)
public abstract class TarotItemMixin {
    @Inject(method = "hasTarot", at = @At("RETURN"), cancellable = true, require = 1)
    private static void rsi$includeResonanceCards(Player player, Item card,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && player instanceof ServerPlayer serverPlayer
                && TarotResonanceAccess.hasActiveCard(serverPlayer, card)) {
            cir.setReturnValue(true);
        }
    }
}
