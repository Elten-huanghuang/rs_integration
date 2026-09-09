package com.huanghuang.rsintegration.mixin.craftflow;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Keeps CraftFlow's channel handshake intact while making every server action inert. */
@Pseudo
@Mixin(targets = "com.ybm.craftflow.server.ServerPseudoCraftHandler", remap = false)
public abstract class CraftFlowServerHandlerMixin {
    @Inject(method = "register", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableRegistration(CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = {"captureCraftingNetwork", "clearCraftingNetwork", "clearPlayerState"},
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disablePlayerState(ServerPlayer player, CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = "onServerTick", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableServerTick(TickEvent.ServerTickEvent event, CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = "onSpecialOutputJoin", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableOutputCapture(EntityJoinLevelEvent event, CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = "onContainerOpen", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableContainerCapture(PlayerContainerEvent.Open event, CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableExecution(ServerPlayer player,
                                             List<ResourceLocation> recipeIds,
                                             List<Integer> repeatCounts,
                                             boolean outputToStorage,
                                             List<Integer> selectedRecipes,
                                             List<Integer> selectedSpecialRecipes,
                                             boolean wirelessTerminal,
                                             int requestedCount,
                                             CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = "handlePreviewRequest", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disablePreview(ServerPlayer player, ResourceLocation recipeId,
                                           boolean remoteStorage, int requestedCount,
                                           CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = "handlePreviewRequestWithSelections", at = @At("HEAD"),
            cancellable = true, require = 0)
    private static void rsi$disableSelectedPreview(ServerPlayer player,
                                                   List<ResourceLocation> selectedRecipes,
                                                   ResourceLocation recipeId,
                                                   boolean remoteStorage,
                                                   int requestedCount,
                                                   CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = "handleMaxCraftableRequest", at = @At("HEAD"),
            cancellable = true, require = 0)
    private static void rsi$disableMaxRequest(ServerPlayer player, ResourceLocation recipeId,
                                              boolean remoteStorage, CallbackInfo callback) {
        callback.cancel();
    }
}
