package com.huanghuang.rsintegration.client;

/** 标记 RSI 正在绘制自己的工具提示，避免原生悬浮提示重复叠加。 */
public final class ClientTooltipGuard {
    private static int depth;

    private ClientTooltipGuard() {}

    public static void begin() {
        depth++;
    }

    public static void end() {
        if (depth > 0) depth--;
    }

    public static boolean isRendering() {
        return depth > 0;
    }
}
