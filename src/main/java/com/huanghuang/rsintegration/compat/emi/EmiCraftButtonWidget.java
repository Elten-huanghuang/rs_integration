package com.huanghuang.rsintegration.compat.emi;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.client.CraftButtonTextures;
import com.huanghuang.rsintegration.client.RecipeAvailabilityClient;
import com.huanghuang.rsintegration.mods.embers.EmbersMachineHints;
import com.huanghuang.rsintegration.sidepanel.client.AltarCraftButtons;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class EmiCraftButtonWidget extends Widget {
    private static final int SIZE = 10;
    // Keep double-click protection short so a slow preview remains retryable.
    private static final long CLICK_DEDUP_MS = 250L;
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
        var action = ClientTooltipComponent.create(Component.translatable(key).getVisualOrderText());
        if (machineButton) return List.of(action);
        List<ClientTooltipComponent> tooltip = new ArrayList<>();
        tooltip.add(action);
        for (String hint : EmbersMachineHints.tooltipKeys(spec.modType())) {
            tooltip.add(ClientTooltipComponent.create(Component.translatable(hint).getVisualOrderText()));
        }
        if (spec.availabilityKey() != null) {
            var state = RecipeAvailabilityClient.get(spec.availabilityKey());
            tooltip.add(ClientTooltipComponent.create(
                    Component.translatable(state.translationKey()).getVisualOrderText()));
            tooltip.add(ClientTooltipComponent.create(
                    Component.translatable("rsi.recipe.materials.scope").getVisualOrderText()));
        }
        return List.copyOf(tooltip);
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
            var mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.translatable("rsi.plan.failure.request_pending"), true);
            }
            return true;
        }
        LAST_REQUEST_MS.put(spec.recipeId(), now);
        return false;
    }

    private void renderCraftButton(GuiGraphics graphics, boolean hovered) {
        CraftButtonTextures.craft(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                RecipeAvailabilityClient.get(spec.availabilityKey()), hovered);
    }

    private void renderMachineButton(GuiGraphics graphics, boolean hovered) {
        CraftButtonTextures.machine(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), hovered);
    }
}
