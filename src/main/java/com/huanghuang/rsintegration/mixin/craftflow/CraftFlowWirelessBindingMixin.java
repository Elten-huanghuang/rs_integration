package com.huanghuang.rsintegration.mixin.craftflow;
import java.lang.reflect.Method;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents CraftFlow from competing with RSI for wireless machine bindings. */
@Pseudo
@Mixin(targets = "com.ybm.craftflow.server.WirelessMachineBinding", remap = false)
public abstract class CraftFlowWirelessBindingMixin {
    @Inject(method = "onRightClickBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableBlockBinding(PlayerInteractEvent.RightClickBlock event,
                                                CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = "onRegisterCommands", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableBindingCommands(RegisterCommandsEvent event,
                                                   CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = "onServerTick", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableBindingTick(TickEvent.ServerTickEvent event,
                                               CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = "consumeSuppressedBeyondUse", at = @At("HEAD"),
            cancellable = true, require = 0)
    private static void rsi$doNotSuppressBeyondUse(Player player,
                                                   CallbackInfoReturnable<Boolean> callback) {
        callback.setReturnValue(false);
    }
}
