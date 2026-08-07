package com.huanghuang.rsintegration.autoeat;

final class AutoEatHungerPolicy {

    private AutoEatHungerPolicy() {}

    static boolean canIgnoreFullHunger(boolean foodCanAlwaysEat, boolean hasGnawsGift) {
        return foodCanAlwaysEat || hasGnawsGift;
    }
}
