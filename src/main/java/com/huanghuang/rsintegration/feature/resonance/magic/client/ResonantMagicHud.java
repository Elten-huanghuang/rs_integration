package com.huanghuang.rsintegration.feature.resonance.magic.client;

import com.huanghuang.rsintegration.feature.resonance.magic.ResonantMagicStorage;
import com.huanghuang.rsintegration.crafting.quantity.CompactQuantityFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** 看向共振方块时显示原生能量库存，避免常驻 HUD 遮挡游戏画面。 */
@OnlyIn(Dist.CLIENT)
public final class ResonantMagicHud {
    private ResonantMagicHud() {}

    @SubscribeEvent
    public static void render(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null
                || !(mc.hitResult instanceof BlockHitResult hit)) return;
        BlockEntity raw = mc.level.getBlockEntity(hit.getBlockPos());
        if (!(raw instanceof ResonantMagicStorage entity) || !entity.isBound()) return;
        String amount = CompactQuantityFormat.format(entity.currentAmount())
                + " / " + CompactQuantityFormat.format(entity.resourceCapacity());
        Component text = Component.translatable(entity.resourceKind().translationKey())
                .append("  ").append(amount);
        Font font = mc.font;
        int width = font.width(text) + 16;
        int x = (mc.getWindow().getGuiScaledWidth() - width) / 2;
        int y = mc.getWindow().getGuiScaledHeight() - 132;
        GuiGraphics graphics = event.getGuiGraphics();
        graphics.fill(x, y, x + width, y + font.lineHeight + 8, 0xC0101418);
        graphics.drawString(font, text, x + 8, y + 4, entity.reachable() ? 0xFF9AE6FF : 0xFFFF6B6B, true);
    }
}
