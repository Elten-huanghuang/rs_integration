package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.api.machine.MachineBinding;
import com.huanghuang.rsintegration.api.RSIIntegrationServices;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客户端 JEI 与服务端绑定检查共用的公共机器 process 索引。
 */
public final class ExternalMachineBindingIndex {
    private static volatile Map<ResourceLocation, Set<ResourceLocation>>
            processesByBlockId = Map.of();
    private static volatile Map<String, Set<ResourceLocation>>
            processesByBlockClass = Map.of();

    private ExternalMachineBindingIndex() {}

    public static synchronized void install(
            List<RSIIntegrationServices.MachineRegistration> registrations
    ) {
        Map<ResourceLocation, Set<ResourceLocation>> byBlockId =
                new LinkedHashMap<>();
        Map<String, Set<ResourceLocation>> byBlockClass =
                new LinkedHashMap<>();
        for (RSIIntegrationServices.MachineRegistration registration
                : registrations) {
            for (MachineBinding binding
                    : registration.integration().bindings()) {
                for (ResourceLocation blockId : binding.blockIds()) {
                    byBlockId.computeIfAbsent(
                                    blockId, ignored -> new LinkedHashSet<>())
                            .addAll(binding.acceptedProcesses());
                }
                for (String className : binding.blockClassNames()) {
                    byBlockClass.computeIfAbsent(
                                    className, ignored -> new LinkedHashSet<>())
                            .addAll(binding.acceptedProcesses());
                }
            }
        }
        processesByBlockId = immutableCopy(byBlockId);
        processesByBlockClass = immutableCopy(byBlockClass);
    }

    public static synchronized void clear() {
        processesByBlockId = Map.of();
        processesByBlockClass = Map.of();
    }

    public static boolean supportsProcess(
            @Nullable String blockRegKey,
            @Nullable ResourceLocation processId
    ) {
        if (processId == null || blockRegKey == null) return false;
        ResourceLocation blockId =
                ResourceLocation.tryParse(blockRegKey);
        if (blockId == null) return false;

        Set<ResourceLocation> direct =
                processesByBlockId.get(blockId);
        if (direct != null && direct.contains(processId)) {
            return true;
        }

        Block block = ForgeRegistries.BLOCKS.getValue(blockId);
        return block != null && supportsProcess(
                block.getClass(), processId);
    }

    public static boolean supportsBlock(
            @Nullable String blockRegKey
    ) {
        if (blockRegKey == null) return false;
        ResourceLocation blockId =
                ResourceLocation.tryParse(blockRegKey);
        if (blockId == null) return false;
        if (processesByBlockId.containsKey(blockId)) {
            return true;
        }
        Block block = ForgeRegistries.BLOCKS.getValue(blockId);
        if (block == null) return false;
        for (Class<?> current = block.getClass();
             current != null;
             current = current.getSuperclass()) {
            if (processesByBlockClass.containsKey(
                    current.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean supportsProcess(
            Class<?> blockClass,
            ResourceLocation processId
    ) {
        for (Class<?> current = blockClass;
             current != null;
             current = current.getSuperclass()) {
            Set<ResourceLocation> processes =
                    processesByBlockClass.get(current.getName());
            if (processes != null && processes.contains(processId)) {
                return true;
            }
        }
        return false;
    }

    private static <K> Map<K, Set<ResourceLocation>> immutableCopy(
            Map<K, Set<ResourceLocation>> source
    ) {
        Map<K, Set<ResourceLocation>> result =
                new LinkedHashMap<>();
        source.forEach((key, value) ->
                result.put(key, Set.copyOf(value)));
        return Map.copyOf(result);
    }
}
