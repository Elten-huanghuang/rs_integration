package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

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
        if (AltarBindingRegistry.findHook(held).isEmpty()) {
            held = mc.player.getOffhandItem();
        }
        if (AltarBindingRegistry.findHook(held).isEmpty()) return;
        if (!(mc.hitResult instanceof BlockHitResult hit)) return;

        var blockEntity = mc.level.getBlockEntity(hit.getBlockPos());
        if (blockEntity != null
                && "dev.shadowsoffire.apotheosis.spawn.spawner.ApothSpawnerTile"
                .equals(blockEntity.getClass().getName())) {
            var itemId = ForgeRegistries.ITEMS.getKey(held.getItem());
            boolean bdTerminal = itemId != null
                    && "beyonddimensions".equals(itemId.getNamespace())
                    && "net_terminal_item".equals(itemId.getPath());
            Component text = Component.translatable(bdTerminal
                    ? "gui.rs_integration.spawner_hint.open_alt"
                    : "gui.rs_integration.spawner_hint.open");
            drawHint(event, text, BIND);
            return;
        }

        var target = BindingEventHandler.bindingTargetPos(mc.level, hit.getBlockPos());
        if (target == null) return;
        // Multiblock/代理方块 targets can resolve to a root position on the
        // server while the client ray trace still points at a visible part.
        // Check the exact root first, then the clicked position and a small
        // neighboring fallback so the HUD flips immediately after binding.
        var dimension = mc.level.dimension().location();
        boolean bound = BindingStorage.hasBinding(held, dimension, target)
                || BindingStorage.hasBinding(held, dimension, hit.getBlockPos());
        if (!bound) {
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(held)) {
                if (!dimension.equals(entry.dim())) continue;
                // A multiblock may expose a different visible part on the
                // client than the one that was hit when it was bound. Resolve
                // the stored position through the same target resolver so the
                // HUD still recognizes the existing binding.
                BlockPos storedTarget = BindingEventHandler.bindingTargetPos(mc.level, entry.pos());
                if (target.equals(storedTarget)) { bound = true; break; }
            }
        }
        var itemId = ForgeRegistries.ITEMS.getKey(held.getItem());
        boolean bdTerminal = itemId != null
                && "beyonddimensions".equals(itemId.getNamespace())
                && "net_terminal_item".equals(itemId.getPath());
        String key = bdTerminal
                ? (bound ? "gui.rs_integration.binding_hint.unbind_alt"
                        : "gui.rs_integration.binding_hint.bind_alt")
                : (bound ? "gui.rs_integration.binding_hint.unbind"
                        : "gui.rs_integration.binding_hint.bind");
        Component text = Component.translatable(key);
        int color = bound ? UNBIND : BIND;

        drawHint(event, text, color);
    }

    private static void drawHint(RenderGuiOverlayEvent.Post event, Component text, int color) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int width = font.width(text) + PADDING_X * 2;
        int x = (mc.getWindow().getGuiScaledWidth() - width) / 2;
        int y = mc.getWindow().getGuiScaledHeight() - BOTTOM_OFFSET;
        GuiGraphics g = event.getGuiGraphics();
        g.fill(x, y, x + width, y + font.lineHeight + PADDING_Y * 2, BG);
        g.drawString(font, text, x + PADDING_X, y + PADDING_Y, color, true);
    }
}
