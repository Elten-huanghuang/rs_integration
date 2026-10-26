package com.huanghuang.rsintegration.api.machine;

import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 第三方机器可被玩家绑定的物理方块声明。
 *
 * <p>绑定发现不能根据机器集成 ID 的 namespace 猜测方块。一个集成可以声明
 * 多个方块 ID 或方块类名，以覆盖同一机器协议下的不同等级或兼容方块。</p>
 */
public record MachineBinding(
        List<ResourceLocation> blockIds,
        List<String> blockClassNames,
        Set<ResourceLocation> acceptedProcesses,
        boolean supportsGui
) {
    public MachineBinding {
        blockIds = blockIds == null ? List.of() : List.copyOf(blockIds);
        blockClassNames = blockClassNames == null
                ? List.of() : List.copyOf(blockClassNames);
        acceptedProcesses = acceptedProcesses == null
                ? Set.of() : Set.copyOf(acceptedProcesses);
        if (blockIds.isEmpty() && blockClassNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "machine binding requires a block id or block class");
        }
        if (acceptedProcesses.isEmpty()) {
            throw new IllegalArgumentException(
                    "machine binding requires at least one accepted process");
        }
        if (blockIds.stream().anyMatch(Objects::isNull)
                || blockClassNames.stream().anyMatch(
                name -> name == null || name.isBlank())
                || acceptedProcesses.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "machine binding must not contain null or blank entries");
        }
    }

    public static MachineBinding blocks(
            Set<ResourceLocation> acceptedProcesses,
            boolean supportsGui,
            ResourceLocation... blockIds
    ) {
        return new MachineBinding(
                Arrays.asList(blockIds), List.of(),
                acceptedProcesses, supportsGui);
    }

    public static MachineBinding blockClasses(
            Set<ResourceLocation> acceptedProcesses,
            boolean supportsGui,
            String... blockClassNames
    ) {
        return new MachineBinding(
                List.of(), Arrays.asList(blockClassNames),
                acceptedProcesses, supportsGui);
    }
}
