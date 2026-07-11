package com.huanghuang.rsintegration.mixin.enigmaticlegacy;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.resonance.bridge.RSInventoryBridge;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.aizistral.enigmaticlegacy.handlers.SuperpositionHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(value = SuperpositionHandler.class, remap = false)
public abstract class SuperpositionHandlerMixin {

    private static final Set<String> rsi$seenItems = ConcurrentHashMap.newKeySet();
    private static int rsi$diagCount;

    // Log every unique item queried via hasItem, regardless of return value.
    @Inject(method = "hasItem", at = @At("HEAD"))
    private static void rsi$logCall(Player player, Item item, CallbackInfoReturnable<Boolean> ci) {
        if (!(player instanceof ServerPlayer)) return;
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(item);
        if (rl == null) return;
        String key = rl.toString();
        if (rsi$seenItems.add(key)) {
            RSIntegrationMod.LOGGER.info("[RSI-hasItem] queried: {}", key);
        }
    }

    @Inject(method = "hasItem", at = @At("RETURN"), cancellable = true)
    private static void rsi$checkDisk(Player player, Item item, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) return;
        if (!(player instanceof ServerPlayer sp)) return;

        ResonanceDiskWrapper disk = RSInventoryBridge.getResonanceDisk(sp);
        if (disk == null) return;

        ResourceLocation target = BuiltInRegistries.ITEM.getKey(item);
        for (ItemStack stack : disk.getStacks()) {
            if (!stack.isEmpty() && stack.is(item)) {
                cir.setReturnValue(true);
                if (rsi$diagCount < 5) {
                    rsi$diagCount++;
                    RSIntegrationMod.LOGGER.info("[RSI-hasItem] FOUND {} in resonance disk for {}",
                            target, sp.getName().getString());
                }
                return;
            }
        }
    }
}
