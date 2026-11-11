package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.client.gui.CraftingUiStyle;
import com.huanghuang.rsintegration.crafting.quantity.CompactQuantityFormat;
import com.huanghuang.rsintegration.mixin.refinedstorage.MonitorAmountsAccessor;
import com.huanghuang.rsintegration.mixin.refinedstorage.MonitorErrorAccessor;
import com.huanghuang.rsintegration.mixin.refinedstorage.MonitorFluidAccessor;
import com.huanghuang.rsintegration.mixin.refinedstorage.MonitorItemAccessor;
import com.huanghuang.rsintegration.mods.ironsspellbooks.client.InkFluidRenderer;
import com.refinedmods.refinedstorage.api.autocrafting.craftingmonitor.ICraftingMonitorElement;
import com.refinedmods.refinedstorage.api.render.IElementDrawers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;

/** 原始像素字号，格子不足时缩略字段并通过原生悬浮提示显示完整数量。 */
public final class MonitorGridRenderer {
    private MonitorGridRenderer() {}

    public static Component amount(String key, long value) {
        return Component.translatable("rsi.monitor.material." + key, CompactQuantityFormat.format(value));
    }

    public static void draw(GuiGraphics graphics, int x, int y, ItemStack item, FluidStack fluid,
                            List<Component> lines, MonitorGridStyle style, IElementDrawers drawers, int width, int height) {
        var font = Minecraft.getInstance().font;
        graphics.fill(x, y, x + width, y + height, style.color());
        if (!item.isEmpty()) InkFluidRenderer.render(graphics, item, x + width - 20, y + (height - 16) / 2);
        else if (!fluid.isEmpty()) drawers.getFluidDrawer().draw(graphics, x + width - 20, y + (height - 16) / 2, fluid);
        int lineHeight = font.lineHeight + 1;
        int shown = Math.min(lines.size(), Math.max(1, (height - 4) / lineHeight));
        int textTop = y + (height - shown * lineHeight + 1) / 2;
        for (int line = 0; line < shown; line++) {
            graphics.drawString(font, CraftingUiStyle.fit(font, lines.get(line).getString(), width - 26),
                    x + 3, textTop + line * lineHeight, CraftingUiStyle.TEXT, false);
        }
        if (lines.size() > shown) graphics.drawString(font, "+", x + width - 8, y + height - 9, CraftingUiStyle.TEXT, false);
    }

    public static void drawNative(ICraftingMonitorElement original, GuiGraphics graphics, int x, int y,
                                  IElementDrawers drawers, int width, int height) {
        if (original instanceof RsiMonitorElement material) {
            material.drawSized(graphics, x, y, drawers, width, height);
            return;
        }
        var base = original instanceof MonitorErrorAccessor error ? error.rsi$base() : original;
        if (!(base instanceof MonitorAmountsAccessor amount)) {
            original.draw(graphics, x, y, drawers);
            return;
        }
        ItemStack item = base instanceof MonitorItemAccessor accessor ? accessor.rsi$stack() : ItemStack.EMPTY;
        FluidStack fluid = base instanceof MonitorFluidAccessor accessor ? accessor.rsi$stack() : FluidStack.EMPTY;
        if (item.isEmpty() && fluid.isEmpty()) {
            original.draw(graphics, x, y, drawers);
            return;
        }
        var lines = new ArrayList<Component>();
        if (amount.rsi$missing() > 0) lines.add(amount("missing", amount.rsi$missing()));
        if (amount.rsi$stored() > 0) lines.add(amount("available", amount.rsi$stored()));
        if (amount.rsi$crafting() > 0) lines.add(amount("crafting", amount.rsi$crafting()));
        if (amount.rsi$scheduled() > 0) lines.add(amount("scheduled", amount.rsi$scheduled()));
        if (amount.rsi$processing() > 0) lines.add(amount("processing", amount.rsi$processing()));
        draw(graphics, x, y, item, fluid, lines, MonitorGridStyle.nativeElement(original), drawers, width, height);
    }
}
