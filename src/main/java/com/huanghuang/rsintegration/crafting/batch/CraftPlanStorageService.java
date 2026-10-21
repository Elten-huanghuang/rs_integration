package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.endpoint.SessionCraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.storage.StorageNetworkDescriptor;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/** 计划预览的存储端点选择和无回退策略。 */
final class CraftPlanStorageService {
    private CraftPlanStorageService() {}

    record Context(
            @Nullable CraftStorageEndpoint endpoint,
            @Nullable StorageReference reference,
            List<StorageNetworkDescriptor> networks,
            @Nullable IntegrationDiagnosticCode failure) {
        boolean failed() {
            return failure != null;
        }
    }

    @Nullable
    static CraftStorageEndpoint resolveEndpoint(
            ServerPlayer player, @Nullable ResourceLocation dimension,
            @Nullable BlockPos position, @Nullable ModType modType) {
        // 先验证机器绑定，避免客户端伪造坐标选择其他玩家的机器。
        if (dimension != null && position != null) {
            ResourceKey<Level> key = ResourceKey.create(
                    Registries.DIMENSION, dimension);
            if (AltarBindingRegistry.isBound(key, position, player)) {
                var endpoint = CraftStorageEndpoints.resolveBinding(
                        player, key, position);
                if (endpoint.isPresent()) return endpoint.orElseThrow();
            }
        }
        // 与预览和规划使用同一个当前端点解析器。
        var current = StorageRestockSupport.resolve(player);
        if (current.isPresent()) return current.orElseThrow();
        // 再尝试同类型的其他已绑定机器。
        if (modType != null && modType != ModType.GENERIC && !modType.isVirtual()) {
            for (AltarBindingRegistry.BoundMachine machine :
                    AltarBindingRegistry.getBoundMachinesForType(player, modType)) {
                if (machine.dim().equals(dimension)
                        && machine.pos().equals(position)) continue;
                ResourceKey<Level> key = ResourceKey.create(
                        Registries.DIMENSION, machine.dim());
                var endpoint = CraftStorageEndpoints.resolveBinding(
                        player, key, machine.pos());
                if (endpoint.isPresent()) return endpoint.orElseThrow();
            }
        }
        return null;
    }

    static Context resolvePlanContext(
            ServerPlayer player, @Nullable ResourceLocation dimension,
            @Nullable BlockPos position, @Nullable ModType modType,
            @Nullable StorageReference requestedReference) {
        CraftStorageEndpoint contextEndpoint =
                StorageRestockSupport.resolve(player).orElse(null);
        CraftStorageEndpoint planningEndpoint = null;
        StorageReference selectedReference = requestedReference;
        if (selectedReference != null) {
            var resolved = CraftStorageEndpoints.resolve(selectedReference, player);
            if (resolved.isEmpty()) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Storage] selected plan reference unavailable backend={} id={} player={}; no fallback",
                        selectedReference.backendId(), selectedReference.networkId(),
                        player.getGameProfile().getName());
                return new Context(null, selectedReference, List.of(),
                        IntegrationDiagnosticCode.NETWORK_UNAVAILABLE);
            }
            planningEndpoint = resolved.orElseThrow();
            // 端点可能刷新了旧引用，必须把规范化引用带回快照和执行请求。
            selectedReference = planningEndpoint.session().reference();
        } else if (contextEndpoint != null
                && !CraftStorageEndpoints.isRefinedStorage(contextEndpoint)) {
            planningEndpoint = contextEndpoint;
            selectedReference = planningEndpoint.session().reference();
        } else {
            planningEndpoint = resolveEndpoint(
                    player, dimension, position, modType);
            if (planningEndpoint != null) {
                selectedReference = planningEndpoint.session().reference();
            } else {
                var defaults = contextEndpoint != null
                        ? List.of(contextEndpoint.session())
                        : RSIntegrationMod.STORAGE_BACKENDS.registry()
                        .resolveDefaultSessionsForPlayer(player);
                if (!defaults.isEmpty()) {
                    planningEndpoint = new SessionCraftStorageEndpoint(defaults.get(0));
                    selectedReference = defaults.get(0).reference();
                }
            }
            if (planningEndpoint == null) {
                // 某些后端有可访问网络但未选择主网络，需从发现结果建立端点。
                for (StorageNetworkDescriptor descriptor :
                        RSIntegrationMod.STORAGE_BACKENDS.registry()
                                .discoverNetworksForPlayer(player)) {
                    var discoveredEndpoint = CraftStorageEndpoints.resolve(
                            descriptor.reference(), player);
                    if (discoveredEndpoint.isPresent()) {
                        planningEndpoint = discoveredEndpoint.orElseThrow();
                        selectedReference = descriptor.reference();
                        RSIntegrationMod.LOGGER.info(
                                "[RSI-Storage] selected discovered default network backend={} id={} player={}",
                                selectedReference.backendId(), selectedReference.networkId(),
                                player.getGameProfile().getName());
                        break;
                    }
                }
            }
        }

        List<StorageNetworkDescriptor> networks =
                RSIntegrationMod.STORAGE_BACKENDS.registry()
                        .discoverNetworksForPlayer(player);
        RSIntegrationMod.debug(
                "[RSI-Storage] plan network choices player={} selected={} choices={}",
                player.getGameProfile().getName(), selectedReference,
                networks.stream()
                        .map(descriptor -> descriptor.reference().backendId().value()
                                + "@" + descriptor.reference().networkId())
                        .toList());
        if (RSIntegrationConfig.REQUIRE_RS_NETWORK_FOR_RECIPE_TREE.get()
                && planningEndpoint == null) {
            return new Context(null, selectedReference, networks,
                    IntegrationDiagnosticCode.NETWORK_UNAVAILABLE);
        }
        return new Context(
                planningEndpoint, selectedReference, networks, null);
    }
}
