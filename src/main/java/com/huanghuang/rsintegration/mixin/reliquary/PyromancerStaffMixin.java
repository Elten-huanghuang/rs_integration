package com.huanghuang.rsintegration.mixin.reliquary;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.resonance.bridge.ResonanceInventoryBridge;
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

        ItemStack extracted = ResonanceInventoryBridge.extractFirst(
                sp, stack -> stack.getItem() == item, count, false);
        if (extracted.isEmpty()) {
            if (rsi$diagNoDisk++ < 3)
                RSIntegrationMod.LOGGER.info("[RSI-Staff] consumeAndCharge: no disk for {}",
                        player.getName().getString());
            return;
        }
        int charge = extracted.getCount() * chargeLimit;
        if (charge > 0) chargeCallback.accept(charge);
        if (rsi$diagHit++ < 5)
            RSIntegrationMod.LOGGER.info("[RSI-Staff] consumeAndCharge from resonance storage:"
                    + " {}x {} -> charge {} for {}",
                    extracted.getCount(), extracted.getDisplayName().getString(),
                    charge, sp.getName().getString());
        ci.cancel();
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

        ItemStack extracted = ResonanceInventoryBridge.extractFirst(
                sp, stack -> stack.getItem() == item, count, simulate);
        if (extracted.isEmpty() || extracted.getCount() < count) {
            if (rsi$diagNoDisk++ < 3)
                RSIntegrationMod.LOGGER.info("[RSI-Staff] no resonance disk for {}, item={}, count={}",
                        sp.getName().getString(), item.getDescription().getString(), count);
            return;
        }
        if (rsi$diagHit++ < 5)
            RSIntegrationMod.LOGGER.info("[RSI-Staff] extracted {}x {} from resonance storage for {}",
                    count, item.getDescription().getString(), sp.getName().getString());
        cir.setReturnValue(true);

        if (rsi$diagMiss++ < 3)
            RSIntegrationMod.LOGGER.info("[RSI-Staff] item {} not found in disk for {}",
                    item.getDescription().getString(), sp.getName().getString());
    }
}
