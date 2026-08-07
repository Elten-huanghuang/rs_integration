package com.huanghuang.rsintegration.util;

/** Pure hunger threshold policy shared by the RS feeding implementation and tests. */
public final class RSFeedingPolicy {
    private RSFeedingPolicy() {}

    public enum HungerRule {
        ANY,
        HALF,
        FULL
    }

    public static boolean canFeed(HungerRule level, int missingFood, int foodValue,
                                  boolean hurt, boolean feedImmediatelyWhenHurt) {
        if (missingFood <= 0 || foodValue <= 0 || level == null) return false;
        if (feedImmediatelyWhenHurt && hurt) return true;
        if (level == HungerRule.ANY) return true;
        int required = level == HungerRule.HALF ? foodValue / 2 : foodValue;
        return required <= missingFood;
    }
}
