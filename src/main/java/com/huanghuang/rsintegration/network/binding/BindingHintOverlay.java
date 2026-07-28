package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** One-line, non-modal hint for the only two terminal binding actions. */
@OnlyIn(Dist.CLIENT)
public final class BindingHintOverlay {
    private static final int BG = 0xC0101418;
    private static final int BIND = 0xFF69D6A3;
    private static final int UNBIND = 0xFF78B7FF;
    private static final int PADDING_X = 8;
    private static final int PADDING_Y = 5;
    private static final int BOTTOM_OFFSET = 96;

    private BindingHintOverlay() {}

    @SubscribeEvent
    public static void onRender(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        ItemStack held = mc.player.getMainHandItem();
        if (AltarBindingRegistry.findHook(held).isEmpty()) return;
        if (!(mc.hitResult instanceof BlockHitResult hit)) return;

        var target = BindingEventHandler.bindingTargetPos(mc.level, hit.getBlockPos());
        if (target == null) return;
        boolean bound = BindingStorage.hasBinding(held, mc.level.dimension().location(), target);
        Component text = bound
                ? Component.translatable("gui.rs_integration.binding_hint.unbind")
                : Component.translatable("gui.rs_integration.binding_hint.bind");
        int color = bound ? UNBIND : BIND;

        Font font = mc.font;
        int width = font.width(text) + PADDING_X * 2;
        int x = (mc.getWindow().getGuiScaledWidth() - width) / 2;
        int y = mc.getWindow().getGuiScaledHeight() - BOTTOM_OFFSET;
        GuiGraphics g = event.getGuiGraphics();
        g.fill(x, y, x + width, y + font.lineHeight + PADDING_Y * 2, BG);
        g.drawString(font, text, x + PADDING_X, y + PADDING_Y, color, true);
    }
}
