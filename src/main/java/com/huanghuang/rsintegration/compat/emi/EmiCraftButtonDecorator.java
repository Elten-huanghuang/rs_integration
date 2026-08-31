package com.huanghuang.rsintegration.compat.emi;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.widget.WidgetHolder;

import java.util.Optional;

public final class EmiCraftButtonDecorator {
    private static final int BUTTON_SIZE = 10;
    private static final int BUTTON_GAP = 2;

    private EmiCraftButtonDecorator() {}

    public static void decorate(EmiRecipe recipe, WidgetHolder widgets) {
        if (!RSIntegrationConfig.ENABLE_JEI.get()) return;

        Optional<EmiCraftButtonSpec> resolved = EmiCraftButtonResolver.resolve(recipe);
        if (resolved.isEmpty()) return;

        EmiCraftButtonSpec spec = resolved.get();
        int y = Math.max(0, widgets.getHeight() - BUTTON_SIZE);
        int craftX = Math.max(0, widgets.getWidth() - BUTTON_SIZE);
        widgets.add(new EmiCraftButtonWidget(craftX, y, spec, false));

        if (spec.machineAction() != null) {
            int machineX = Math.max(0, craftX - BUTTON_GAP - BUTTON_SIZE);
            widgets.add(new EmiCraftButtonWidget(machineX, y, spec, true));
        }
    }
}
