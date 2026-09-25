package com.huanghuang.rsintegration.compat.emi;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.widget.WidgetHolder;

import java.util.Optional;

public final class EmiCraftButtonDecorator {
    private EmiCraftButtonDecorator() {}

    public static int sideWidth(EmiRecipe recipe, int rows) {
        Optional<EmiCraftButtonSpec> resolved = resolve(recipe);
        if (resolved.isEmpty()) return 0;

        int buttonCount = resolved.get().machineAction() == null ? 1 : 2;
        return EmiCraftButtonPlacement.sideWidth(buttonCount, rows);
    }

    public static void decorate(EmiRecipe recipe, WidgetHolder widgets,
                                int columnOffset, int rows) {
        Optional<EmiCraftButtonSpec> resolved = resolve(recipe);
        if (resolved.isEmpty()) return;

        EmiCraftButtonSpec spec = resolved.get();
        int buttonCount = spec.machineAction() == null ? 1 : 2;
        int[] craftPosition = EmiCraftButtonPlacement.position(
                0, buttonCount, rows, widgets.getWidth(), widgets.getHeight(), columnOffset);
        widgets.add(new EmiCraftButtonWidget(
                craftPosition[0], craftPosition[1], spec, false));

        if (spec.machineAction() == null) return;
        int[] machinePosition = EmiCraftButtonPlacement.position(
                1, buttonCount, rows, widgets.getWidth(), widgets.getHeight(), columnOffset);
        widgets.add(new EmiCraftButtonWidget(
                machinePosition[0], machinePosition[1], spec, true));
    }

    private static Optional<EmiCraftButtonSpec> resolve(EmiRecipe recipe) {
        if (!RSIntegrationConfig.ENABLE_JEI.get()) return Optional.empty();
        return EmiCraftButtonResolver.resolve(recipe);
    }
}
