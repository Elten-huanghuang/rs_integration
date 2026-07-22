package com.huanghuang.rsintegration.mixin.goetydelight;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.Polarice3.Goety.utils.BlockFinder", remap = false)
public abstract class BlockFinderCompatMixin {
    private static final ResourceLocation HUNTING_DENIAL = new ResourceLocation("goetydelight", "hunting_denial");

    @Inject(method = "findIllagerWard(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/player/Player;I)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void rsi$honorHuntingDenial(ServerLevel level, Player player, int range,
                                                CallbackInfoReturnable<Boolean> cir) {
        MobEffect effect = ForgeRegistries.MOB_EFFECTS.getValue(HUNTING_DENIAL);
        if (effect != null && player.hasEffect(effect)) {
            cir.setReturnValue(true);
        }
    }
}