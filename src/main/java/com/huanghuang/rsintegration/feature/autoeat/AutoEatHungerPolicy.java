package com.huanghuang.rsintegration.feature.autoeat;

final class AutoEatHungerPolicy {

    private AutoEatHungerPolicy() {}

    static boolean canIgnoreFullHunger(boolean foodCanAlwaysEat, boolean hasGnawsGift) {
        return foodCanAlwaysEat || hasGnawsGift;
    }
}
