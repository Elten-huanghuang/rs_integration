package com.huanghuang.rsintegration.autoeat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public enum AutoEatMode {
    DIVERSITY("rsi.autoeat.mode.diversity"),
    STACK("rsi.autoeat.mode.stack"),
    DIET("rsi.autoeat.mode.diet");

    private final String translationKey;

    AutoEatMode(String translationKey) {
        this.translationKey = translationKey;
    }

    public MutableComponent displayName() {
        return Component.translatable(translationKey);
    }

    public AutoEatMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public AutoEatMode prev() {
        return values()[(ordinal() + values().length - 1) % values().length];
    }
}
