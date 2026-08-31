package com.huanghuang.rsintegration.compat.emi;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.mods.goety.RSClientAvailabilityCache;
import com.huanghuang.rsintegration.sidepanel.client.AltarCraftButtons;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class EmiCraftButtonWidget extends Widget {
    private static final int SIZE = 10;
    private static final long CLICK_DEDUP_MS = 1_500L;
    private static final Map<ResourceLocation, Long> LAST_REQUEST_MS = new ConcurrentHashMap<>();

    private final Bounds bounds;
    private final EmiCraftButtonSpec spec;
    private final boolean machineButton;

    EmiCraftButtonWidget(int x, int y, EmiCraftButtonSpec spec, boolean machineButton) {
        this.bounds = new Bounds(x, y, SIZE, SIZE);
        this.spec = spec;
        this.machineButton = machineButton;
    }

    @Override
    public Bounds getBounds() {
        return bounds;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (!machineButton && !isVisible()) return;
        boolean hovered = bounds.contains(mouseX, mouseY);
        if (machineButton) {
            renderMachineButton(graphics, hovered);
        } else {
            renderCraftButton(graphics, hovered);
        }
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (button != 0 || !bounds.contains(mouseX, mouseY)) return false;
        if (!machineButton && !isVisible()) return false;

        Runnable action = machineButton ? spec.machineAction() : spec.craftAction();
        if (action == null) return false;
        if (!machineButton && isDuplicateRequest()) return true;

        try {
            action.run();
        } catch (RuntimeException exception) {
            RSIntegrationMod.LOGGER.error("[RSI-EMI] Button action failed for {}", spec.recipeId(), exception);
        }
        return true;
    }

    @Override
    public List<ClientTooltipComponent> getTooltip(int mouseX, int mouseY) {
        if (!machineButton && !isVisible()) return List.of();
        String key = machineButton ? "rsi.jei.open_machine" : spec.tooltipKey();
        return List.of(ClientTooltipComponent.create(Component.translatable(key).getVisualOrderText()));
    }

    private boolean isVisible() {
        return AltarCraftButtons.isVisible(spec.recipeId(), spec.modType());
    }

    private boolean isDuplicateRequest() {
        long now = Util.getMillis();
        Long previous = LAST_REQUEST_MS.get(spec.recipeId());
        if (previous != null && now - previous < CLICK_DEDUP_MS) {
            RSIntegrationMod.LOGGER.debug("[RSI-EMI] Dedup: skipped {} ({}ms since last request)",
                    spec.recipeId(), now - previous);
            return true;
        }
        LAST_REQUEST_MS.put(spec.recipeId(), now);
        return false;
    }

    private void renderCraftButton(GuiGraphics graphics, boolean hovered) {
        boolean[] results = RSClientAvailabilityCache.get(spec.recipeId());
        boolean hasData = results != null && results.length > 0;
        boolean available = hasData;
        if (results != null) {
            for (boolean result : results) {
                if (!result) {
                    available = false;
                    break;
                }
            }
        }

        int background;
        int border;
        int text;
        if (hasData && available) {
            background = hovered ? 0xFF33AA33 : 0xFF226622;
            border = hovered ? 0xFF66FF66 : 0xFF33AA33;
            text = hovered ? 0xFFFFFF : 0xCCFFCC;
        } else if (hasData) {
            background = hovered ? 0xFFAA3333 : 0xFF662222;
            border = hovered ? 0xFFFF6666 : 0xFFAA3333;
            text = hovered ? 0xFFFFFF : 0xFFCCCC;
        } else {
            background = hovered ? 0xFF555555 : 0xFF333333;
            border = hovered ? 0xFFFFFFFF : 0xFF888888;
            text = hovered ? 0xFFFFFF : 0xAAAAAA;
        }

        fillBackground(graphics, border, background);
        String symbol = hasData && available ? "\u2713" : "+";
        Font font = Minecraft.getInstance().font;
        graphics.drawString(font, symbol,
                bounds.x() + (bounds.width() - font.width(symbol)) / 2,
                bounds.y() + (bounds.height() - font.lineHeight) / 2,
                text);
    }

    private void renderMachineButton(GuiGraphics graphics, boolean hovered) {
        int background = hovered ? 0xFF556688 : 0xFF334455;
        int border = hovered ? 0xFF88AACC : 0xFF556677;
        fillBackground(graphics, border, background);

        int x = bounds.x();
        int y = bounds.y();
        int right = bounds.right();
        int bottom = bounds.bottom();
        int icon = hovered ? 0xFFCCDDEE : 0xFF8899AA;
        int screen = hovered ? 0xFFEEF4FF : 0xFFAABBCC;
        int stand = hovered ? 0xFF99AACC : 0xFF667788;
        graphics.fill(x + 1, y + 1, right - 1, bottom - 3, icon);
        graphics.fill(x + 2, y + 2, right - 2, bottom - 4, screen);
        graphics.fill(x + 4, bottom - 2, x + 6, bottom - 1, stand);
        graphics.fill(x + 3, bottom - 1, x + 7, bottom, stand);
    }

    private void fillBackground(GuiGraphics graphics, int border, int background) {
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), border);
        graphics.fill(bounds.x() + 1, bounds.y() + 1,
                bounds.right() - 1, bounds.bottom() - 1, background);
    }
}
