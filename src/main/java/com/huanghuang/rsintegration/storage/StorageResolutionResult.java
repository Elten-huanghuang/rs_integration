package com.huanghuang.rsintegration.storage;

import java.util.Objects;
import java.util.Optional;

/** Structured network resolution result; an empty session never hides the reason. */
public final class StorageResolutionResult {
    private final StorageResolutionStatus status;
    private final StorageSession session;

    private StorageResolutionResult(StorageResolutionStatus status, StorageSession session) {
        this.status = Objects.requireNonNull(status, "status");
        this.session = session;
    }

    public static StorageResolutionResult resolved(StorageSession session) {
        return new StorageResolutionResult(StorageResolutionStatus.RESOLVED,
                Objects.requireNonNull(session, "session"));
    }

    public static StorageResolutionResult failure(StorageResolutionStatus status) {
        Objects.requireNonNull(status, "status");
        if (status == StorageResolutionStatus.RESOLVED) {
            throw new IllegalArgumentException("resolved status requires a session");
        }
        return new StorageResolutionResult(status, null);
    }

    public StorageResolutionStatus status() { return status; }
    public Optional<StorageSession> session() { return Optional.ofNullable(session); }
    public boolean resolved() { return status == StorageResolutionStatus.RESOLVED; }
}
