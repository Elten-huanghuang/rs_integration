package com.huanghuang.rsintegration.crafting.progress;

/** 服务端单调时钟采样的执行时长；未知数据不能当作零秒。 */
public record CraftProgressTiming(long elapsedMillis, long sinceProgressMillis) {
    public static final CraftProgressTiming UNKNOWN = new CraftProgressTiming(-1L, -1L);

    public CraftProgressTiming {
        if (!(elapsedMillis == -1L && sinceProgressMillis == -1L)
                && (elapsedMillis < 0L || sinceProgressMillis < 0L || sinceProgressMillis > elapsedMillis)) {
            throw new IllegalArgumentException("invalid craft timing");
        }
    }

    public boolean known() { return elapsedMillis >= 0L; }
}
