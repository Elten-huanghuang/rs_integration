package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.client.gui.CraftingUiStyle;
import net.minecraft.client.gui.GuiGraphics;

/** 监控器局部样式：统一细边框，用窄色条和浅底色表达状态。 */
public final class MonitorVisualStyle {
    private MonitorVisualStyle() {}

    public static int blend(int base, int tint, int percent) {
        int inverse = 100 - percent;
        int red = ((base >>> 16 & 255) * inverse + (tint >>> 16 & 255) * percent) / 100;
        int green = ((base >>> 8 & 255) * inverse + (tint >>> 8 & 255) * percent) / 100;
        int blue = ((base & 255) * inverse + (tint & 255) * percent) / 100;
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    public static void cell(GuiGraphics graphics, int x, int y, int width, int height,
                            int statusColor, boolean hovered) {
        int base = blend(CraftingUiStyle.canvas(), statusColor, 28);
        if (hovered) base = blend(base, CraftingUiStyle.highlight(), 18);
        graphics.fill(x, y, x + width, y + height, base);
        graphics.fill(x, y, x + width, y + 1, blend(base, CraftingUiStyle.highlight(), 30));
        graphics.fill(x, y + height - 1, x + width, y + height, blend(base, CraftingUiStyle.border(), 38));
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, blend(base, CraftingUiStyle.border(), 25));
        graphics.fill(x, y + 1, x + 2, y + height - 1, statusColor);
    }
}
