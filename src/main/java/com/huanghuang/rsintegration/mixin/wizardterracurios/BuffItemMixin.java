package com.huanghuang.rsintegration.mixin.wizardterracurios;

import com.huanghuang.rsintegration.resonance.bridge.RSInventoryBridge;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Extends Wizard Terra Curios' BuffItem inventory scan to the resonance disk.
 *
 * <p>The original class owns the effect-refresh rules, so this reuses its
 * private helper instead of duplicating the third-party item contract.</p>
 */
@Mixin(value = com.inolia_zaicek.wizard_terra_cuiros.Item.BuffItem.class, remap = false)
public abstract class BuffItemMixin {

    @Shadow
    private static void applyBuffFromStack(Player player, ItemStack stack) {
        throw new AssertionError();
    }

    @Inject(method = "onPlayerTick", at = @At("RETURN"), require = 1)
    private static void rsi$applyDiskBuffs(TickEvent.PlayerTickEvent event, CallbackInfo ci) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) return;
        if (event.player.tickCount % 40 != 0) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        ResonanceDiskWrapper disk = RSInventoryBridge.getResonanceDisk(player);
        if (disk == null) return;

        for (ItemStack stack : disk.getStacks()) {
            if (!stack.isEmpty()) applyBuffFromStack(player, stack);
        }
    }
}
