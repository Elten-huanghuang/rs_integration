package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Locale;

/** Conservative policy gate for cross-node delegate concurrency. */
public final class GraphConcurrencyPolicy {
    public record Decision(boolean exclusive, String reason,
                           BatchConcurrencyCapabilities capabilities) {
        static Decision allow(BatchConcurrencyCapabilities capabilities) {
            return new Decision(false, "capability guards passed", capabilities);
        }

        static Decision deny(String reason, BatchConcurrencyCapabilities capabilities) {
            return new Decision(true, reason, capabilities);
        }
    }

    private GraphConcurrencyPolicy() {}

    public static boolean isExclusive(String modTypeId, IBatchDelegate delegate) {
        return decide(modTypeId, delegate).exclusive();
    }

    public static Decision decide(String modTypeId, IBatchDelegate delegate) {
        return decide(modTypeId, delegate, null);
    }

    public static Decision decide(String modTypeId, IBatchDelegate delegate,
                                  BatchConcurrencyCapabilities recipeCapabilities) {
        if (delegate == null) return Decision.deny("delegate unavailable", null);

        // 并行能力由委托自身的结构化契约推断，旧的全局禁用列表和
        // AUTO/OFF 策略不再参与判定。
        BatchConcurrencyCapabilities runtimeCapabilities =
                delegate.concurrencyCapabilities();
        BatchConcurrencyCapabilities capabilities = recipeCapabilities != null
                ? runtimeCapabilities == null
                ? recipeCapabilities
                : intersectCapabilities(recipeCapabilities, runtimeCapabilities)
                : runtimeCapabilities;
        if (capabilities == null) {
            return Decision.deny("delegate has no concurrency capability", null);
        }
        if (capabilities.materials()
                != BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED) {
            return Decision.deny("materials are not fully chain-reserved", capabilities);
        }
        if (capabilities.cleanup()
                != BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE) {
            return Decision.deny("cleanup is not separable/offline-safe", capabilities);
        }
        if (capabilities.preparation()
                == BatchConcurrencyCapabilities.PreparationContract.UNKNOWN) {
            return Decision.deny("preparation contract is unknown", capabilities);
        }
        if (capabilities.outputOwnership()
                == BatchConcurrencyCapabilities.OutputOwnership.AMBIGUOUS
                || capabilities.outputOwnership()
                == BatchConcurrencyCapabilities.OutputOwnership.ENTITY) {
            return Decision.deny("output ownership is ambiguous or entity-owned", capabilities);
        }
        if (capabilities.sideEffects() != BatchConcurrencyCapabilities.SideEffects.NONE
                && capabilities.sideEffects() != BatchConcurrencyCapabilities.SideEffects.MACHINE_LOCAL
                && capabilities.sideEffects() != BatchConcurrencyCapabilities.SideEffects.LOCAL_WORLD_ITEMS
                && capabilities.sideEffects() != BatchConcurrencyCapabilities.SideEffects.ADJACENT_MACHINE
                && capabilities.sideEffects() != BatchConcurrencyCapabilities.SideEffects.INFER) {
            return Decision.deny("delegate has non-local side effects: "
                    + capabilities.sideEffects().name().toLowerCase(Locale.ROOT), capabilities);
        }
        // 猜配方是“同一玩家/同一配方共享候选集”的有状态搜索；两个 worker
        // 同时猜会互相覆盖进度，因此只允许顺序执行。
        if (capabilities.sideEffects() == BatchConcurrencyCapabilities.SideEffects.INFER) {
            return Decision.deny("inference recipes are stateful and must run sequentially", capabilities);
        }
        return Decision.allow(capabilities);
    }

    static BatchConcurrencyCapabilities intersectCapabilities(
            BatchConcurrencyCapabilities planned,
            BatchConcurrencyCapabilities runtime
    ) {
        if (planned == null) return runtime;
        if (runtime == null) return planned;
        // 运行时 delegate 最了解机器真实产物/清理方式；规划期资格表只是按
        // 模组类型给出的保守上界。只要运行时契约明确安全，就采用运行时值，
        // 不能因为“上界”与“实际”不完全相等而把普通机器误判为独占。
        BatchConcurrencyCapabilities.MaterialOwnership materials =
                runtime.materials() == BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED
                        ? runtime.materials()
                        : planned.materials();
        BatchConcurrencyCapabilities.OutputOwnership outputOwnership =
                runtime.outputOwnership() == BatchConcurrencyCapabilities.OutputOwnership.AMBIGUOUS
                        || runtime.outputOwnership() == BatchConcurrencyCapabilities.OutputOwnership.ENTITY
                        ? planned.outputOwnership()
                        : runtime.outputOwnership();
        BatchConcurrencyCapabilities.CleanupContract cleanup =
                runtime.cleanup() == BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE
                        ? runtime.cleanup()
                        : planned.cleanup();
        BatchConcurrencyCapabilities.PreparationContract preparation =
                stricterPreparation(
                        planned.preparation(),
                        runtime.preparation());
        BatchConcurrencyCapabilities.SideEffects sideEffects =
                sideEffectRisk(planned.sideEffects())
                        >= sideEffectRisk(runtime.sideEffects())
                        ? planned.sideEffects()
                        : runtime.sideEffects();
        // 运行时 descriptor 知道真实多方块/底座位置；规划期 offsets 只是
        // 选机器前的最坏范围，不能在执行期继续扩大租约范围。普通机器没有
        // 运行时 support 时，采用配方资格表给出的保守范围（祭坛/仪式）。
        List<BlockPos> support = runtime.supportOffsets().isEmpty()
                ? planned.supportOffsets()
                : runtime.supportOffsets();
        return new BatchConcurrencyCapabilities(
                materials,
                outputOwnership,
                cleanup,
                sideEffects,
                preparation,
                List.copyOf(support)
        );
    }

    private static BatchConcurrencyCapabilities.PreparationContract
    stricterPreparation(
            BatchConcurrencyCapabilities.PreparationContract left,
            BatchConcurrencyCapabilities.PreparationContract right
    ) {
        if (left == BatchConcurrencyCapabilities.PreparationContract.UNKNOWN
                || right
                == BatchConcurrencyCapabilities.PreparationContract.UNKNOWN) {
            return BatchConcurrencyCapabilities.PreparationContract.UNKNOWN;
        }
        return BatchConcurrencyCapabilities.PreparationContract.RETRY_SAFE;
    }

    private static int sideEffectRisk(
            BatchConcurrencyCapabilities.SideEffects sideEffects
    ) {
        return switch (sideEffects) {
            case NONE -> 0;
            case MACHINE_LOCAL -> 1;
            case LOCAL_WORLD_ITEMS -> 2;
            case INFER -> 3;
            case ADJACENT_MACHINE -> 4;
            case PLAYER_TRANSFORM -> 5;
            case WORLD_GLOBAL -> 6;
            case UNKNOWN -> 7;
        };
    }

}
