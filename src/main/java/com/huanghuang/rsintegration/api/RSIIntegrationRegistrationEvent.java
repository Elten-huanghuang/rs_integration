package com.huanghuang.rsintegration.api;

import net.minecraftforge.eventbus.api.Event;

import java.util.Objects;

public final class RSIIntegrationRegistrationEvent extends Event {
    private final RSIIntegrationApi api;

    /** Forge EventBus 通过无参实例解析监听器泛型，此实例不会作为真实事件发布。 */
    public RSIIntegrationRegistrationEvent() {
        this.api = null;
    }

    public RSIIntegrationRegistrationEvent(RSIIntegrationApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    public RSIIntegrationApi api() {
        return Objects.requireNonNull(api, "event bus probe instance");
    }
}
