package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.network.binding.RSBindingHook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** RS 专用的共振方块绑定提示，避免可选 RS 类型进入通用客户端事件类。 */
@OnlyIn(Dist.CLIENT)
public final class ResonantMagicBindingHintOverlay {
    private static final int BG = 0xC0101418;
    private static final int BIND = 0xFF69D6A3;
    private static final int UNBIND = 0xFF78B7FF;
    private static final int PADDING_X = 8;
    private static final int PADDING_Y = 5;
    private static final int BOTTOM_OFFSET = 96;

    private ResonantMagicBindingHintOverlay() {}

    @SubscribeEvent
    public static void onRender(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null
                || !(mc.hitResult instanceof BlockHitResult hit)) return;
        if (!(mc.level.getBlockState(hit.getBlockPos()).getBlock()
                instanceof ResonantMagicBlockMarker)) return;
        ResonantMagicStorage entity = mc.level.getBlockEntity(hit.getBlockPos())
                instanceof ResonantMagicStorage magic ? magic : null;

        ItemStack held = mc.player.getMainHandItem();
        if (!RSBindingHook.INSTANCE.matches(held)) {
            held = mc.player.getOffhandItem();
        }
        if (held.isEmpty() || !RSBindingHook.INSTANCE.matches(held)) return;

        boolean canBind = RSBindingHook.INSTANCE.matches(held);
        String key = !canBind ? "gui.rs_integration.binding_hint.connect_network"
                : entity != null && entity.isBound() ? "gui.rs_integration.binding_hint.unbind"
                        : "gui.rs_integration.binding_hint.bind";
        int color = canBind && entity != null && entity.isBound() ? UNBIND : BIND;
        drawHint(event, Component.translatable(key), color);
    }

    private static void drawHint(RenderGuiOverlayEvent.Post event, Component text, int color) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int width = font.width(text) + PADDING_X * 2;
        int x = (mc.getWindow().getGuiScaledWidth() - width) / 2;
        int y = mc.getWindow().getGuiScaledHeight() - BOTTOM_OFFSET;
        GuiGraphics graphics = event.getGuiGraphics();
        graphics.fill(x, y, x + width, y + font.lineHeight + PADDING_Y * 2, BG);
        graphics.drawString(font, text, x + PADDING_X, y + PADDING_Y, color, true);
    }
}
