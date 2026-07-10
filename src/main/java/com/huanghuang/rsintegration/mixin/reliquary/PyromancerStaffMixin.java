package com.huanghuang.rsintegration.mixin.reliquary;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.resonance.bridge.RSInventoryBridge;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.IntConsumer;

/**
 * Redirects Pyromancer Staff ammo consumption to the resonance disk.
 *
 * <p>The 5-param {@code consumeAndCharge(Player,int,int,Item,int,IntConsumer)}
 * is the entry point called by {@code inventoryTick}; it internally delegates
 * to the 6-param Predicate overload.  Injecting at its HEAD covers both the
 * charging (absorb) and extinguishing ammo-scan code paths.</p>
 *
 * <p>{@code removeItemFromInternalStorage} covers active fireball shooting.</p>
 */
@Mixin(value = reliquary.item.ToggleableItem.class, remap = false)
public abstract class PyromancerStaffMixin {

    @Unique
    private static int rsi$diagHit;
    @Unique
    private static int rsi$diagMiss;
    @Unique
    private static int rsi$diagNoDisk;
    @Unique
    private static int rsi$diagEntry;
    @Unique
    private static int rsi$diagNoCount;

    // ── Path 1: passive ammo scan (charging mode) ────────────────────

    @Inject(method = "consumeAndCharge",
            at = @At("HEAD"),
            cancellable = true)
    private void rsi$consumeAndChargeFromDisk(Player player, int unitWorth, int chargeLimit,
                                               Item item, int cost,
                                               IntConsumer chargeCallback, CallbackInfo ci) {
        if (rsi$diagEntry++ < 10)
            RSIntegrationMod.LOGGER.info("[RSI-Staff] consumeAndCharge called: item={}"
                    + " unitWorth={} chargeLimit={} cost={} isClient={}",
                    item.getDescription().getString(), unitWorth, chargeLimit, cost,
                    player.level().isClientSide());

        int count = Math.min(unitWorth / chargeLimit, cost);
        if (count <= 0) {
            if (rsi$diagNoCount++ < 5)
                RSIntegrationMod.LOGGER.info("[RSI-Staff] consumeAndCharge skip: count={}"
                        + " (unitWorth={} / chargeLimit={} = {})",
                        count, unitWorth, chargeLimit, unitWorth / chargeLimit);
            return;
        }
        if (player.level().isClientSide()) return;
        if (!(player instanceof ServerPlayer sp)) return;

        ResonanceDiskWrapper disk = RSInventoryBridge.getResonanceDisk(sp);
        if (disk == null) {
            if (rsi$diagNoDisk++ < 3)
                RSIntegrationMod.LOGGER.info("[RSI-Staff] consumeAndCharge: no disk for {}",
                        player.getName().getString());
            return;
        }

        for (ItemStack diskStack : disk.delegate().getStacks()) {
            if (diskStack.isEmpty()) continue;
            if (diskStack.getItem() != item) continue;

            int take = Math.min(count, diskStack.getCount());
            ItemStack extracted = disk.manualExtractExact(diskStack, take, 0, Action.PERFORM);
            if (!extracted.isEmpty()) {
                int charge = extracted.getCount() * chargeLimit;
                if (charge > 0) chargeCallback.accept(charge);
                if (rsi$diagHit++ < 5)
                    RSIntegrationMod.LOGGER.info("[RSI-Staff] consumeAndCharge from disk:"
                            + " {}x {} -> charge {} for {}",
                            extracted.getCount(),
                            diskStack.getDisplayName().getString(),
                            charge, sp.getName().getString());
                ci.cancel();
                return;
            }
        }
    }

    @Unique
    private static int rsi$diagRemove;

    // ── Path 2: active fireball shooting ───────────────────────────

    @Inject(method = "removeItemFromInternalStorage", at = @At("HEAD"), cancellable = true)
    private void rsi$removeItemFromInternalStorage(ItemStack staff, Item item, int count,
                                                    boolean simulate, Player player,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (rsi$diagRemove++ < 10)
            RSIntegrationMod.LOGGER.info("[RSI-Staff] removeItemFromInternalStorage: item={} count={}"
                    + " simulate={} isClient={}",
                    item.getDescription().getString(), count, simulate, player.level().isClientSide());

        if (player.level().isClientSide()) return;
        if (!(player instanceof ServerPlayer sp)) return;

        ResonanceDiskWrapper disk = RSInventoryBridge.getResonanceDisk(sp);
        if (disk == null) {
            if (rsi$diagNoDisk++ < 3)
                RSIntegrationMod.LOGGER.info("[RSI-Staff] no resonance disk for {}, item={}, count={}",
                        sp.getName().getString(), item.getDescription().getString(), count);
            return;
        }

        for (ItemStack diskStack : disk.delegate().getStacks()) {
            if (diskStack.isEmpty()) continue;
            if (diskStack.getItem() != item) continue;
            if (diskStack.getCount() < count) continue;

            if (simulate) {
                cir.setReturnValue(true);
                return;
            }

            ItemStack extracted = disk.manualExtractExact(diskStack, count, 0, Action.PERFORM);
            if (!extracted.isEmpty() && extracted.getCount() >= count) {
                if (rsi$diagHit++ < 5)
                    RSIntegrationMod.LOGGER.info("[RSI-Staff] extracted {}x {} from disk for {}",
                            count, item.getDescription().getString(), sp.getName().getString());
                cir.setReturnValue(true);
                return;
            }
        }

        if (rsi$diagMiss++ < 3)
            RSIntegrationMod.LOGGER.info("[RSI-Staff] item {} not found in disk for {}",
                    item.getDescription().getString(), sp.getName().getString());
    }
}
