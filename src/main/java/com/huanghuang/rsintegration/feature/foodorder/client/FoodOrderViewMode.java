package com.huanghuang.rsintegration.feature.foodorder.client;

import com.huanghuang.rsintegration.util.SharedText;
import net.minecraft.network.chat.Component;

enum FoodOrderViewMode {
    ALL(SharedText.ALL.key()),
    ORDERABLE("rsi.food_order.filter.orderable"),
    FAVORITES("rsi.food_order.filter.favorites"),
    NUTRITION("rsi.food_order.filter.nutrition");

    private final String key;

    FoodOrderViewMode(String key) {
        this.key = key;
    }

    Component label() {
        return Component.translatable(key);
    }
}
