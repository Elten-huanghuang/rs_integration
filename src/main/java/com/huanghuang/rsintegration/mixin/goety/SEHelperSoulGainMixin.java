package com.huanghuang.rsintegration.mixin.goety;

import com.huanghuang.rsintegration.mods.goety.magic.GoetySoulOverflow;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** 标记当前正在处理的灵魂获取，供 SEImp 溢出分流使用。 */
@Pseudo
@Mixin(targets = "com.Polarice3.Goety.utils.SEHelper", remap = false)
public abstract class SEHelperSoulGainMixin {
    @WrapOperation(method = "increaseSouls(Lnet/minecraft/world/entity/player/Player;I)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/Polarice3/Goety/utils/SEHelper;increaseSESouls(Lnet/minecraft/world/entity/player/Player;I)Z"),
            remap = false, require = 0)
    private static boolean rsi$routeSoulOverflow(Player player, int souls,
                                                  Operation<Boolean> original) {
        GoetySoulOverflow.beginGain(player);
        try {
            return original.call(player, souls);
        } finally {
            GoetySoulOverflow.endGain();
        }
    }
}
