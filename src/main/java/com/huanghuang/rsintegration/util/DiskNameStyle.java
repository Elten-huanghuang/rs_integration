package com.huanghuang.rsintegration.util;

import net.minecraft.network.chat.Component;

/** 磁盘名称的流光配色，首尾颜色一致以保持循环平滑。 */
public final class DiskNameStyle {
    private static final int[] GUIXU_GOLD = {
            0xD99028, 0xF2BD48, 0xFFF1B0, 0xF2BD48, 0xD99028
    };
    private static final int[] RESONANCE_GLOW = {
            0xC58A28, 0xFFE29A, 0xFFF1B0, 0xE6B84F, 0xC58A28
    };

    private DiskNameStyle() {}

    public static Component guixu(Component name) {
        return TextBuilder.of(name).colorFlow(3600L, 0.0F, GUIXU_GOLD).bold().build();
    }

    public static Component resonance(Component name) {
        return TextBuilder.of(name).colorFlow(2800L, 0.0F, RESONANCE_GLOW).bold().build();
    }
}
