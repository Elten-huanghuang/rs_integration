package com.huanghuang.rsintegration.mixin.slashblade;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.mods.rs.GridItemRenderContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import mods.flammpfeil.slashblade.client.renderer.SlashBladeTEISR;
import mods.flammpfeil.slashblade.client.renderer.model.obj.WavefrontObject;
import mods.flammpfeil.slashblade.client.renderer.util.BladeRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = SlashBladeTEISR.class, remap = false)
public abstract class SlashBladeTEISRGridMixin {
    @ModifyArg(
            method = "renderBlade",
            at = @At(
                    value = "INVOKE",
                    target = "Lmods/flammpfeil/slashblade/client/renderer/SlashBladeTEISR;renderIcon(Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IFZ)V"),
            index = 5)
    private boolean rsi$omitGridDurabilityModel(boolean renderDurability) {
        return GridItemRenderContext.useLightweightSlashBladeRendering()
                ? false : renderDurability;
    }

    @WrapOperation(
            method = "renderIcon(Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IFZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lmods/flammpfeil/slashblade/client/renderer/util/BladeRenderState;renderOverridedLuminous(Lnet/minecraft/world/item/ItemStack;Lmods/flammpfeil/slashblade/client/renderer/model/obj/WavefrontObject;Ljava/lang/String;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private void rsi$omitGridLuminousLayer(
            ItemStack stack, WavefrontObject model, String target,
            ResourceLocation texture, PoseStack poses, MultiBufferSource buffers,
            int packedLight, Operation<Void> original) {
        if (!GridItemRenderContext.useLightweightSlashBladeRendering()) {
            original.call(stack, model, target, texture, poses, buffers, packedLight);
        }
    }
}
