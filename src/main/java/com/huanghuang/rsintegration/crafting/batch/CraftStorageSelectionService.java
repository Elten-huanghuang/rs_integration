package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.endpoint.SessionCraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.storage.StorageNetworkDescriptor;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.storage.StorageSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 统一预览和执行的存储选择规则。
 *
 * <p>显式引用一旦提供便是唯一选择，失效时不允许回退到其他网络。
 * 未指定引用时，沿用当前端点、绑定机器、默认网络和发现网络的顺序。
 * 网络枚举由预览服务按需执行，不属于这一路径。</p>
 */
final class CraftStorageSelectionService {
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("RS Integration");

    private CraftStorageSelectionService() {}

    record Selection(
            @Nullable CraftStorageEndpoint endpoint,
            @Nullable StorageReference reference,
            @Nullable IntegrationDiagnosticCode failure) {
        boolean failed() {
            return failure != null;
        }
    }

    static Selection select(
            ServerPlayer player,
            @Nullable ResourceLocation dimension,
            @Nullable BlockPos position,
            @Nullable ModType modType,
            @Nullable StorageReference requestedReference) {
        if (requestedReference != null) {
            var resolved = CraftStorageEndpoints.resolve(requestedReference, player);
            if (resolved.isEmpty()) {
                LOGGER.warn(
                        "[RSI-Storage] selected reference unavailable; refusing backend fallback backend={} id={} player={}",
                        requestedReference.backendId(), requestedReference.networkId(),
                        player.getGameProfile().getName());
                return new Selection(null, requestedReference,
                        IntegrationDiagnosticCode.NETWORK_UNAVAILABLE);
            }
            CraftStorageEndpoint endpoint = resolved.orElseThrow();
            return selected(endpoint);
        }

        CraftStorageEndpoint context = StorageRestockSupport.resolve(player).orElse(null);
        if (context != null && !CraftStorageEndpoints.isRefinedStorage(context)) {
            return selected(context);
        }

        CraftStorageEndpoint bound = resolveBoundEndpoint(
                player, dimension, position, modType);
        if (bound != null) return selected(bound);

        // 保留当前 RS 端点作为无绑定机器时的上下文；绑定机器存在时优先使用绑定解析结果。
        if (context != null) return selected(context);

        List<StorageSession> defaults =
                RSIntegrationMod.STORAGE_BACKENDS.registry()
                        .resolveDefaultSessionsForPlayer(player);
        if (!defaults.isEmpty()) {
            return selected(new SessionCraftStorageEndpoint(defaults.get(0)));
        }

        for (StorageNetworkDescriptor descriptor :
                RSIntegrationMod.STORAGE_BACKENDS.registry()
                        .discoverNetworksForPlayer(player)) {
            var resolved = CraftStorageEndpoints.resolve(descriptor.reference(), player);
            if (resolved.isPresent()) return selected(resolved.orElseThrow());
        }
        return new Selection(null, null, null);
    }

    @Nullable
    static CraftStorageEndpoint resolveEndpoint(
            ServerPlayer player,
            @Nullable ResourceLocation dimension,
            @Nullable BlockPos position,
            @Nullable ModType modType) {
        return select(player, dimension, position, modType, null).endpoint();
    }

    private static Selection selected(CraftStorageEndpoint endpoint) {
        return new Selection(endpoint, endpoint.session().reference(), null);
    }

    @Nullable
    private static CraftStorageEndpoint resolveBoundEndpoint(
            ServerPlayer player,
            @Nullable ResourceLocation dimension,
            @Nullable BlockPos position,
            @Nullable ModType modType) {
        if (dimension != null && position != null) {
            ResourceKey<Level> key = ResourceKey.create(
                    Registries.DIMENSION, dimension);
            if (AltarBindingRegistry.isBound(key, position, player)) {
                var endpoint = CraftStorageEndpoints.resolveBinding(
                        player, key, position);
                if (endpoint.isPresent()) return endpoint.orElseThrow();
            }
        }
        if (modType == null || modType == ModType.GENERIC || modType.isVirtual()) {
            return null;
        }
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
        return null;
    }
}
