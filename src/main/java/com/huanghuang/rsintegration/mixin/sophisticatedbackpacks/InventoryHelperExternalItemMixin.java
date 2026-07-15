package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import com.huanghuang.rsintegration.compat.ftbquests.ExternalItemProgressBridge;
import com.huanghuang.rsintegration.util.ExternalItemProgressSuppression;
import com.huanghuang.rsintegration.util.InsertedStackDelta;
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
            at = @At("HEAD"), remap = false, require = 1)
    private static void rsi$beginExternalPickup(Level level, Player player,
                                                UpgradeHandler upgrades, ItemStack input,
                                                boolean simulate,
                                                CallbackInfoReturnable<ItemStack> cir) {
        ExternalItemProgressSuppression.beginOperation();
    }

    @Inject(
            method = "runPickupOnPickupResponseUpgrades(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/p3pp3rf1y/sophisticatedcore/upgrades/UpgradeHandler;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN"), remap = false, require = 1)
    private static void rsi$reportExternalPickup(Level level, Player player,
                                                 UpgradeHandler upgrades, ItemStack input,
                                                 boolean simulate,
                                                 CallbackInfoReturnable<ItemStack> cir) {
        boolean suppressed = ExternalItemProgressSuppression.consume();
        if (suppressed || simulate || !(player instanceof ServerPlayer serverPlayer)) return;
        ItemStack inserted = InsertedStackDelta.between(input, cir.getReturnValue());
        ExternalItemProgressBridge.enqueue(serverPlayer, inserted);
    }
}
