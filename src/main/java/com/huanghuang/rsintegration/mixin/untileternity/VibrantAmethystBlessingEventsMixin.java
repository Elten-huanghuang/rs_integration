package com.huanghuang.rsintegration.mixin.untileternity;

import com.carrot123.until_eternity.item.ModItems;
import com.huanghuang.rsintegration.resonance.bridge.ResonanceInventoryBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** 扩展原模组的持有判定，保留其紫水晶祝福的刷新规则。 */
@Mixin(targets = "com.carrot123.until_eternity.event.VibrantAmethystBlessingEvents", remap = false)
public abstract class VibrantAmethystBlessingEventsMixin {
    @ModifyVariable(method = "onPlayerTick", at = @At("STORE"), ordinal = 0,
            require = 0, remap = false)
    private static boolean rsi$includeResonanceDisk(boolean found,
                                                    TickEvent.PlayerTickEvent event) {
        if (found || !(event.player instanceof ServerPlayer player)) return found;
        return ResonanceInventoryBridge.hasItem(player,
                stack -> stack.is(ModItems.VIBRANT_AMETHYST.get()));
    }
}
