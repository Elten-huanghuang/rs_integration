package com.huanghuang.rsintegration.mixin.slashblade;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.mods.rs.GridItemRenderContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import mods.flammpfeil.slashblade.client.renderer.model.obj.WavefrontObject;
import mods.flammpfeil.slashblade.client.renderer.util.BladeRenderState;
import mods.flammpfeil.slashblade.event.client.RenderOverrideEvent;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Function;

@Mixin(value = BladeRenderState.class, remap = false)
public abstract class BladeRenderStateGridMixin {
    @WrapOperation(
            method = "renderOverrided(Lnet/minecraft/world/item/ItemStack;Lmods/flammpfeil/slashblade/client/renderer/model/obj/WavefrontObject;Ljava/lang/String;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILjava/util/function/Function;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lmods/flammpfeil/slashblade/event/client/RenderOverrideEvent;onRenderOverride(Lnet/minecraft/world/item/ItemStack;Lmods/flammpfeil/slashblade/client/renderer/model/obj/WavefrontObject;Ljava/lang/String;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILjava/util/function/Function;Z)Lmods/flammpfeil/slashblade/event/client/RenderOverrideEvent;"))
    private static RenderOverrideEvent rsi$omitGridGlintPass(
            ItemStack stack, WavefrontObject model, String target,
            ResourceLocation texture, PoseStack poses, MultiBufferSource buffers,
            int packedLight, Function<ResourceLocation, RenderType> renderType,
            boolean enableEffect, Operation<RenderOverrideEvent> original) {
        RenderOverrideEvent event = original.call(
                stack, model, target, texture, poses, buffers,
                packedLight, renderType, enableEffect);
        if (GridItemRenderContext.useLightweightSlashBladeRendering()) {
            event.setEnableEffect(false);
        }
        return event;
    }
}
