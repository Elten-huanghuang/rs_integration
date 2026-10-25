package com.huanghuang.rsintegration.api.machine;

public record ConcurrencyCapabilities(
        MaterialOwnership materials,
        OutputOwnership outputOwnership,
        CleanupContract cleanup,
        SideEffects sideEffects,
        PreparationContract preparation
) {
    public enum MaterialOwnership {
        CHAIN_RESERVED,
        SELF_EXTRACTING,
        UNKNOWN
    }

    public enum OutputOwnership {
        MACHINE_SLOT,
        DELEGATE_RESULT,
        OWNED_WORLD_CAPTURE,
        ENTITY,
        AMBIGUOUS
    }

    public enum CleanupContract {
        SEPARABLE_OFFLINE,
        ONLINE_ONLY,
        UNKNOWN
    }

    public enum SideEffects {
        NONE,
        MACHINE_LOCAL,
        LOCAL_WORLD_ITEMS,
        ADJACENT_MACHINE,
        PLAYER_TRANSFORM,
        WORLD_GLOBAL,
        UNKNOWN
    }

    public enum PreparationContract {
        RELIABLE,
        RETRY_SAFE,
        UNKNOWN
    }

    public ConcurrencyCapabilities {
        if (materials == null || outputOwnership == null || cleanup == null
                || sideEffects == null || preparation == null) {
            throw new IllegalArgumentException("concurrency capabilities are incomplete");
        }
    }

    public static ConcurrencyCapabilities machineSlot() {
        return new ConcurrencyCapabilities(
                MaterialOwnership.CHAIN_RESERVED,
                OutputOwnership.MACHINE_SLOT,
                CleanupContract.SEPARABLE_OFFLINE,
                SideEffects.MACHINE_LOCAL,
                PreparationContract.RETRY_SAFE
        );
    }

    public static ConcurrencyCapabilities unknown() {
        return new ConcurrencyCapabilities(
                MaterialOwnership.UNKNOWN,
                OutputOwnership.AMBIGUOUS,
                CleanupContract.UNKNOWN,
                SideEffects.UNKNOWN,
                PreparationContract.UNKNOWN
        );
    }
}
