package com.huanghuang.rsintegration.mixin.minecraft;

import com.huanghuang.rsintegration.crafting.batch.PreparationMessageScope;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMessageMixin {

    @Inject(
            method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;Z)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void rsi$suppressPreparationMessage(Component message, boolean overlay,
                                                 CallbackInfo ci) {
        if (PreparationMessageScope.isSilent()) ci.cancel();
    }
}
