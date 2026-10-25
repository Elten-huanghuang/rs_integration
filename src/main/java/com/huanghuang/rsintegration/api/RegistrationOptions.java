package com.huanghuang.rsintegration.api;

import java.util.Map;

public record RegistrationOptions(
        int priority,
        Map<String, String> attributes
) {
    public RegistrationOptions {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static RegistrationOptions defaults() {
        return new RegistrationOptions(0, Map.of());
    }
}
