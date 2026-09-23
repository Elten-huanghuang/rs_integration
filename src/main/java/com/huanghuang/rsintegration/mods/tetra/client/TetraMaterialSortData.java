package com.huanghuang.rsintegration.mods.tetra.client;

import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;

public record TetraMaterialSortData(
        String category,
        float hardness,
        float density,
        float flexibility,
        float durability,
        int toolLevel,
        float toolEfficiency,
        float integrityGain,
        float integrityCost,
        int magicCapacity) {

    public static TetraMaterialSortData empty() {
        return new TetraMaterialSortData("", Float.NaN, Float.NaN, Float.NaN,
                Float.NaN, Integer.MIN_VALUE, Float.NaN, Float.NaN, Float.NaN,
                Integer.MIN_VALUE);
    }

    public static TetraMaterialSortData from(Object materialData) {
        return new TetraMaterialSortData(
                stringValue(TetraWorkbenchMaterialState.readField(materialData, "category")),
                floatValue(TetraWorkbenchMaterialState.readField(materialData, "primary")),
                floatValue(TetraWorkbenchMaterialState.readField(materialData, "secondary")),
                floatValue(TetraWorkbenchMaterialState.readField(materialData, "tertiary")),
                floatValue(TetraWorkbenchMaterialState.readField(materialData, "durability")),
                intValue(TetraWorkbenchMaterialState.readField(materialData, "toolLevel")),
                floatValue(TetraWorkbenchMaterialState.readField(materialData, "toolEfficiency")),
                floatValue(TetraWorkbenchMaterialState.readField(materialData, "integrityGain")),
                floatValue(TetraWorkbenchMaterialState.readField(materialData, "integrityCost")),
                intValue(TetraWorkbenchMaterialState.readField(materialData, "magicCapacity")));
    }

    public String categoryLabel() {
        if (category.isBlank()) return "";
        return Component.translatable("tetra.variant_category." + category + ".label")
                .getString();
    }

    private static String stringValue(@Nullable Object value) {
        return value instanceof String string ? string : "";
    }

    private static float floatValue(@Nullable Object value) {
        return value instanceof Number number ? number.floatValue() : Float.NaN;
    }

    private static int intValue(@Nullable Object value) {
        return value instanceof Number number ? number.intValue() : Integer.MIN_VALUE;
    }
}
