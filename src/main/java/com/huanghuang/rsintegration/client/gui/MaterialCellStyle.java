package com.huanghuang.rsintegration.client.gui;

/** 预览与监控共用的低饱和整格底色；不依赖任何可选存储模组。 */
public enum MaterialCellStyle {
    STORED(0xFFA1A5BB), RUNNING(0xFF88B0AD), SCHEDULED(0xFFC3B887),
    BLOCKED(0xFFC6929B), STOPPING(0xFFB3A0C4), COMPLETED(0xFFA4B89A);

    private final int color;
    MaterialCellStyle(int color) { this.color = color; }
    public int color() { return color; }

    public static MaterialCellStyle preview(long available, long needed, long missing) {
        // 即使部分有货，真实缺口也必须显示缺料；由后续配方覆盖的差额才是待合成。
        if (missing > 0) return BLOCKED;
        return available >= needed ? STORED : SCHEDULED;
    }
}
