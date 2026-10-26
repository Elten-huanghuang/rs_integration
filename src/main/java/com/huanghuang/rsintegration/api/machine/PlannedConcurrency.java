package com.huanghuang.rsintegration.api.machine;

import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities.CleanupContract;
import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities.MaterialOwnership;
import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities.OutputOwnership;
import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities.PreparationContract;
import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities.SideEffects;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 机器族在绑定具体方块前就能确定的并发合同。
 *
 * <p>声明必须覆盖该 process 下所有候选机器，并使用最保守的副作用和
 * 支持范围。supportOffsets 是选择具体机器前的最坏位置集合，只供规划期
 * 冲突检测；选中机器后，执行租约以 descriptor.supportScope 的真实位置为准。
 * 运行期 descriptor 仍会再次校验，不能靠该声明绕过安全检查。</p>
 */
public record PlannedConcurrency(
        MaterialOwnership materials,
        OutputOwnership outputOwnership,
        CleanupContract cleanup,
        SideEffects sideEffects,
        PreparationContract preparation,
        List<BlockPos> supportOffsets
) {
    public PlannedConcurrency {
        if (materials == null || outputOwnership == null
                || cleanup == null || sideEffects == null
                || preparation == null) {
            throw new IllegalArgumentException(
                    "planned concurrency contract is incomplete");
        }
        supportOffsets = supportOffsets == null
                ? List.of()
                : supportOffsets.stream()
                .map(position -> position == null
                        ? null : position.immutable())
                .toList();
        if (supportOffsets.stream().anyMatch(position -> position == null)) {
            throw new IllegalArgumentException(
                    "support offsets must not contain null");
        }
    }

    public static PlannedConcurrency machineSlot() {
        return new PlannedConcurrency(
                MaterialOwnership.CHAIN_RESERVED,
                OutputOwnership.MACHINE_SLOT,
                CleanupContract.SEPARABLE_OFFLINE,
                SideEffects.MACHINE_LOCAL,
                PreparationContract.RETRY_SAFE,
                List.of()
        );
    }

    public static PlannedConcurrency delegateResult() {
        return new PlannedConcurrency(
                MaterialOwnership.CHAIN_RESERVED,
                OutputOwnership.DELEGATE_RESULT,
                CleanupContract.SEPARABLE_OFFLINE,
                SideEffects.MACHINE_LOCAL,
                PreparationContract.RETRY_SAFE,
                List.of()
        );
    }
}
