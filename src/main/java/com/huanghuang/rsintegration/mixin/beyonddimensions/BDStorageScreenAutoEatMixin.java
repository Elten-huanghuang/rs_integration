package com.huanghuang.rsintegration.mixin.beyonddimensions;

import com.huanghuang.rsintegration.feature.autoeat.client.AutoEatClientEvents;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 0.8 起超越维度终端界面为 {@code client.ui.storage.StorageScreen}，0.7 的
 * {@code client.gui.DimensionsNetGUI} 已删除。0.8 的同屏界面没有独立的左侧栏控件
 * （{@code LeftButtonSidebar} 一并消失），因此这里只走“原生 init 之后再挂控件”的路径，
 * 不再走侧栏注册。新界面同样是 {@code Screen} 子类，控件仍按原方式挂到屏幕上。
 */
@Pseudo
@Mixin(targets = "com.wintercogs.beyonddimensions.client.ui.storage.StorageScreen", remap = false)
public abstract class BDStorageScreenAutoEatMixin {

    @Inject(method = {"init", "m_7856_"}, at = @At("TAIL"), remap = false, require = 0)
    private void rsi$installAutoEatControls(CallbackInfo ci) {
        AutoEatClientEvents.installControlsAfterNativeInit((Screen) (Object) this);
    }
}
