package com.huanghuang.rsintegration.mixin.beyonddimensions;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.autoeat.client.AutoEatClientEvents;
import com.wintercogs.beyonddimensions.client.gui.widget.LeftButtonSidebar;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.gui.components.Renderable;

/** Uses the 0.7.30 sidebar registration path without changing legacy BD UI. */
@Pseudo
@Mixin(targets = "com.wintercogs.beyonddimensions.client.gui.DimensionsNetGUI", remap = false)
public abstract class DimensionsNetGuiSidebarMixin {
    @Shadow(remap = false)
    protected LeftButtonSidebar leftButtonSidebar;

    @Inject(method = {"init", "m_7856_"}, at = @At("TAIL"), remap = false, require = 0)
    private void rsi$installSidebarControls(CallbackInfo ci) {
        if (leftButtonSidebar == null) return;
        Screen screen = (Screen) (Object) this;
        AutoEatClientEvents.installControlsAfterNativeInit(screen,
                button -> addRenderableWidget(leftButtonSidebar.addButton(button)));
    }

    @Shadow(remap = true)
    protected abstract <T extends GuiEventListener & Renderable>
            T addRenderableWidget(T widget);
}
