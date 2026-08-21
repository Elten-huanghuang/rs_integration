package com.huanghuang.rsintegration.storage;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable identifier for one storage backend implementation. */
public record StorageBackendId(String value) implements Comparable<StorageBackendId> {
    public static final int MAX_LENGTH = 64;
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_.-]+");

    public StorageBackendId {
        Objects.requireNonNull(value, "value");
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_LENGTH || !VALID_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid storage backend id: " + value);
        }
    }

    @Override
    public int compareTo(StorageBackendId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
