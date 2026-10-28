package com.huanghuang.rsintegration.crafting.batch;

import net.minecraft.core.BlockPos;

import java.util.List;

/** Safety contract used before admitting a delegate to cross-node concurrency. */
public record BatchConcurrencyCapabilities(
        MaterialOwnership materials,
        OutputOwnership outputOwnership,
        CleanupContract cleanup,
        SideEffects sideEffects,
        PreparationContract preparation,
        List<BlockPos> supportOffsets
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
        INFER,
        UNKNOWN
    }

    public enum PreparationContract {
        RELIABLE,
        RETRY_SAFE,
        UNKNOWN
    }

    public BatchConcurrencyCapabilities {
        materials = materials == null ? MaterialOwnership.UNKNOWN : materials;
        outputOwnership = outputOwnership == null ? OutputOwnership.AMBIGUOUS : outputOwnership;
        cleanup = cleanup == null ? CleanupContract.UNKNOWN : cleanup;
        sideEffects = sideEffects == null ? SideEffects.UNKNOWN : sideEffects;
        preparation = preparation == null ? PreparationContract.UNKNOWN : preparation;
        supportOffsets = supportOffsets == null ? List.of() : List.copyOf(supportOffsets);
    }

    public static BatchConcurrencyCapabilities machineSlot() {
        return new BatchConcurrencyCapabilities(
                MaterialOwnership.CHAIN_RESERVED,
                OutputOwnership.MACHINE_SLOT,
                CleanupContract.SEPARABLE_OFFLINE,
                SideEffects.MACHINE_LOCAL,
                PreparationContract.RETRY_SAFE,
                List.of());
    }

    /** Main output stays in the leased machine; recipe remainders may drop nearby but are not DAG assets. */
    public static BatchConcurrencyCapabilities machineSlotWithLocalWorldItems() {
        return new BatchConcurrencyCapabilities(
                MaterialOwnership.CHAIN_RESERVED,
                OutputOwnership.MACHINE_SLOT,
                CleanupContract.SEPARABLE_OFFLINE,
                SideEffects.LOCAL_WORLD_ITEMS,
                PreparationContract.RETRY_SAFE,
                List.of());
    }

    public static BatchConcurrencyCapabilities delegateResult() {
        return new BatchConcurrencyCapabilities(
                MaterialOwnership.CHAIN_RESERVED,
                OutputOwnership.DELEGATE_RESULT,
                CleanupContract.SEPARABLE_OFFLINE,
                SideEffects.MACHINE_LOCAL,
                PreparationContract.RETRY_SAFE,
                List.of());
    }

    /** 产物由委托从世界捕获（祭坛、坩埚等多方块结构）。 */
    public static BatchConcurrencyCapabilities ownedWorldCapture() {
        return new BatchConcurrencyCapabilities(
                MaterialOwnership.CHAIN_RESERVED,
                OutputOwnership.OWNED_WORLD_CAPTURE,
                CleanupContract.SEPARABLE_OFFLINE,
                SideEffects.LOCAL_WORLD_ITEMS,
                PreparationContract.RETRY_SAFE,
                List.of());
    }

    /** 显式声明机器只能独占运行；用于确实无法安全并行的世界交互。 */
    public static BatchConcurrencyCapabilities exclusive() {
        return new BatchConcurrencyCapabilities(
                MaterialOwnership.UNKNOWN,
                OutputOwnership.AMBIGUOUS,
                CleanupContract.UNKNOWN,
                SideEffects.UNKNOWN,
                PreparationContract.UNKNOWN,
                List.of());
    }

    /** 无物理机器、无世界副作用的纯账本事务。 */
    public static BatchConcurrencyCapabilities virtualTransaction() {
        return new BatchConcurrencyCapabilities(
                MaterialOwnership.CHAIN_RESERVED,
                OutputOwnership.DELEGATE_RESULT,
                CleanupContract.SEPARABLE_OFFLINE,
                SideEffects.NONE,
                PreparationContract.RETRY_SAFE,
                List.of());
    }
}
