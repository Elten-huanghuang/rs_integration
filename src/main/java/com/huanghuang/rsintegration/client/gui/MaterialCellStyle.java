package com.huanghuang.rsintegration.client.gui;

import com.huanghuang.rsintegration.config.CraftingUiColors;

/** 预览与监控共用的整格底色；配置重载后同步更新，不依赖可选存储模组。 */
public enum MaterialCellStyle {
    STORED(CraftingUiColors.MATERIAL_STORED), RUNNING(CraftingUiColors.MATERIAL_RUNNING),
    SCHEDULED(CraftingUiColors.MATERIAL_SCHEDULED), BLOCKED(CraftingUiColors.MATERIAL_BLOCKED),
    STOPPING(CraftingUiColors.MATERIAL_STOPPING), COMPLETED(CraftingUiColors.MATERIAL_COMPLETED);

    private final CraftingUiColors color;
    MaterialCellStyle(CraftingUiColors color) { this.color = color; }
    public int color() { return color.argb(); }

    public static MaterialCellStyle preview(long available, long needed, long missing) {
        // 即使部分有货，真实缺口也必须显示缺料；由后续配方覆盖的差额才是待合成。
        if (missing > 0) return BLOCKED;
        return available >= needed ? STORED : SCHEDULED;
    }
}
