package com.huanghuang.rsintegration.mixin.enigmaticaddons;

import auviotre.enigmatic.addon.contents.items.AvariceRing;
import com.huanghuang.rsintegration.mixin.minecraft.InventoryAccessor;
import com.huanghuang.rsintegration.resonance.bridge.ClientDiskData;
import com.huanghuang.rsintegration.resonance.bridge.RSInventoryBridge;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Injects into {@code AvariceRing.getDamageBoost(Player)} so gems stored
 * in the resonance disk also count towards the damage bonus.
 *
 * <p>Uses {@code @Inject} at RETURN (core Mixin, no MixinExtras) to avoid
 * the MixinExtras 0.3.6/0.5.3 cross-version compatibility issue that
 * prevented {@code @WrapOperation} handlers from firing.</p>
 */
@Mixin(value = AvariceRing.class, remap = false)
public abstract class AvariceRingMixin {

    @Inject(method = "getDamageBoost", at = @At("RETURN"), cancellable = true)
    private void rsi$boostGetDamageBoost(Player player, CallbackInfoReturnable<Float> cir) {
        int diskGems;
        if (player instanceof ServerPlayer sp) {
            ResonanceDiskWrapper disk = RSInventoryBridge.getResonanceDisk(sp);
            if (disk == null) return;
            diskGems = rsi$countGems(disk.getStacks());
        } else if (player.level().isClientSide()) {
            diskGems = ClientDiskData.getGemCount();
        } else {
            return;
        }

        if (diskGems <= 0) return;

        int invGems = rsi$countInventoryGems(player);
        int totalGems = invGems + diskGems;

        double log64 = Math.log(64);
        double multiplier = AvariceRing.damageBoostMultiplier.getValue();
        float newBoost = (float)(multiplier * Math.log(1.0 + totalGems) / log64);
        cir.setReturnValue(newBoost);
    }

    @Unique
    private static int rsi$countInventoryGems(Player player) {
        int count = 0;
        for (NonNullList<ItemStack> comp :
                ((InventoryAccessor) player.getInventory()).rsi$getCompartments()) {
            for (ItemStack stack : comp) {
                if (!stack.isEmpty() && stack.is(net.minecraftforge.common.Tags.Items.GEMS)) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    @Unique
    private static int rsi$countGems(Iterable<ItemStack> stacks) {
        int count = 0;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && stack.is(net.minecraftforge.common.Tags.Items.GEMS)) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
