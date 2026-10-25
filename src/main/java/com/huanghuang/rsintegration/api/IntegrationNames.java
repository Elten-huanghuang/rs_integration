package com.huanghuang.rsintegration.api;

import org.jetbrains.annotations.ApiStatus;

import java.util.Locale;
import java.util.regex.Pattern;

/** 公共契约共享的稳定名称校验。 */
@ApiStatus.Internal
public final class IntegrationNames {
    private static final Pattern GROUP_NAME = Pattern.compile("[a-z0-9_.-]{1,64}");

    private IntegrationNames() {}

    public static String requireGroup(String value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!GROUP_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid " + label + ": " + value);
        }
        return normalized;
    }
}
