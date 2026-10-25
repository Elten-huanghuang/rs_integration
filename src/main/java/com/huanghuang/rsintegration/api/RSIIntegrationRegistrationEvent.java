package com.huanghuang.rsintegration.api;

import net.minecraftforge.eventbus.api.Event;

import java.util.Objects;

public final class RSIIntegrationRegistrationEvent extends Event {
    private final RSIIntegrationApi api;

    public RSIIntegrationRegistrationEvent(RSIIntegrationApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    public RSIIntegrationApi api() {
        return api;
    }
}
