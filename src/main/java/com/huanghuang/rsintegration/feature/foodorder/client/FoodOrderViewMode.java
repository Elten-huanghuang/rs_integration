package com.huanghuang.rsintegration.feature.foodorder.client;

import net.minecraft.network.chat.Component;

enum FoodOrderViewMode {
    ALL("all"),
    ORDERABLE("orderable"),
    FAVORITES("favorites"),
    NUTRITION("nutrition");

    private final String key;

    FoodOrderViewMode(String key) {
        this.key = "rsi.food_order.filter." + key;
    }

    Component label() {
        return Component.translatable(key);
    }
}
