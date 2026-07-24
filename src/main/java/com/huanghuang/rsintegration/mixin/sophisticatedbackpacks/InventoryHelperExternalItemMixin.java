package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import com.huanghuang.rsintegration.util.ExternalItemProgressSuppression;
import com.huanghuang.rsintegration.util.InsertedStackDelta;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeHandler;
import net.p3pp3rf1y.sophisticatedcore.util.InventoryHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reports pickup-response insertions that bypass vanilla player inventory slots. */
@Mixin(value = InventoryHelper.class, remap = false)
public abstract class InventoryHelperExternalItemMixin {

    @Inject(
            method = "runPickupOnPickupResponseUpgrades(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/p3pp3rf1y/sophisticatedcore/upgrades/UpgradeHandler;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), remap = false, require = 0)
    private static void rsi$beginExternalPickup(Level level, Player player,
                                                UpgradeHandler upgrades, ItemStack input,
                                                boolean simulate,
                                                CallbackInfoReturnable<ItemStack> cir,
                                                @Share("originalInput") LocalRef<ItemStack> originalInput) {
        // InventoryHelper overwrites the input parameter's local-variable slot with
        // each upgrade remainder. Capture it before the call so accepted = input - remainder.
        originalInput.set(input.copy());
        ExternalItemProgressSuppression.beginOperation();
    }

    @Inject(
            method = "runPickupOnPickupResponseUpgrades(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/p3pp3rf1y/sophisticatedcore/upgrades/UpgradeHandler;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN"), remap = false, require = 0)
    private static void rsi$reportExternalPickup(Level level, Player player,
                                                 UpgradeHandler upgrades, ItemStack input,
                                                 boolean simulate,
                                                 CallbackInfoReturnable<ItemStack> cir,
                                                 @Share("originalInput") LocalRef<ItemStack> originalInput) {
        if (simulate || !(player instanceof ServerPlayer serverPlayer)) {
            ExternalItemProgressSuppression.consume();
            return;
        }
        InsertedStackDelta.report(serverPlayer, originalInput.get(), cir.getReturnValue());
    }
}
