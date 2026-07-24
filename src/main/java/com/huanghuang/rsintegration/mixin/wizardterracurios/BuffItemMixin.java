package com.huanghuang.rsintegration.mixin.wizardterracurios;

import com.huanghuang.rsintegration.resonance.bridge.RSInventoryBridge;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Extends Wizard Terra Curios' BuffItem inventory scan to the resonance disk.
 *
 * <p>The original class owns the effect-refresh rules. Its private helper is
 * resolved softly because older releases do not provide that method.</p>
 */
@Mixin(value = com.inolia_zaicek.wizard_terra_cuiros.Item.BuffItem.class, remap = false)
public abstract class BuffItemMixin {

    @Inject(method = "onPlayerTick", at = @At("RETURN"), require = 0)
    private static void rsi$applyDiskBuffs(TickEvent.PlayerTickEvent event, CallbackInfo ci) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) return;
        if (event.player.tickCount % 40 != 0) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        Method applyBuff = Reflect.findMethod(
                com.inolia_zaicek.wizard_terra_cuiros.Item.BuffItem.class,
                "applyBuffFromStack", new Class<?>[]{Player.class, ItemStack.class});
        if (applyBuff == null) return;

        ResonanceDiskWrapper disk = RSInventoryBridge.getResonanceDisk(player);
        if (disk == null) return;

        for (ItemStack stack : disk.getStacks()) {
            if (stack.isEmpty()) continue;
            try {
                applyBuff.invoke(null, player, stack);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                RSIntegrationMod.LOGGER.debug(
                        "[RSI-WizardTerraCurios] Failed to apply resonance-disk buff", exception);
            }
        }
    }
}
