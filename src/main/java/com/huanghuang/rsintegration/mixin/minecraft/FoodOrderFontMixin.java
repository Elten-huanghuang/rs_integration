package com.huanghuang.rsintegration.mixin.minecraft;

import com.huanghuang.rsintegration.feature.foodorder.client.FoodOrderScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** 纸面菜单统一关闭文字阴影，兼容原生输入框和模组提供的提示组件。 */
@Mixin(Font.class)
public abstract class FoodOrderFontMixin {
    @ModifyVariable(method = {
            "drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
            "drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;IIZ)I",
            "drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
            "drawInBatch(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I"
    }, at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 4)
    private boolean rsi$paperTextShadow(boolean shadow) {
        return shadow && !(Minecraft.getInstance().screen instanceof FoodOrderScreen);
    }
}
