package com.huanghuang.rsintegration.mixin.beyonddimensions;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.autoeat.client.AutoEatClientEvents;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Re-adds RSI controls after BD rebuilds its terminal widgets. */
@Pseudo
@Mixin(targets = "com.wintercogs.beyonddimensions.client.gui.DimensionsNetGUI", remap = false)
public abstract class DimensionsNetGuiAutoEatMixin {

    @Inject(method = {"init", "m_7856_"}, at = @At("TAIL"), remap = false, require = 0)
    private void rsi$installAutoEatControls(CallbackInfo ci) {
        AutoEatClientEvents.installControlsAfterNativeInit((Screen) (Object) this);
    }
}
