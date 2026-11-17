package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.storage.StorageNetworkDescriptor;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

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
        return CraftStorageSelectionService.resolveEndpoint(
                player, dimension, position, modType);
    }

    static Context resolvePlanContext(
            ServerPlayer player, @Nullable ResourceLocation dimension,
            @Nullable BlockPos position, @Nullable ModType modType,
            @Nullable StorageReference requestedReference) {
        CraftStorageSelectionService.Selection selected =
                CraftStorageSelectionService.select(
                        player, dimension, position, modType, requestedReference);
        if (selected.failed()) {
            return new Context(null, selected.reference(), List.of(),
                    selected.failure());
        }

        List<StorageNetworkDescriptor> networks =
                RSIntegrationMod.STORAGE_BACKENDS.registry()
                        .discoverNetworksForPlayer(player);
        RSIntegrationMod.debug(
                "[RSI-Storage] plan network choices player={} selected={} choices={}",
                player.getGameProfile().getName(), selected.reference(),
                networks.stream()
                        .map(descriptor -> descriptor.reference().backendId().value()
                                + "@" + descriptor.reference().networkId())
                        .toList());
        if (RSIntegrationConfig.REQUIRE_RS_NETWORK_FOR_RECIPE_TREE.get()
                && selected.endpoint() == null) {
            return new Context(null, selected.reference(), networks,
                    IntegrationDiagnosticCode.NETWORK_UNAVAILABLE);
        }
        return new Context(
                selected.endpoint(), selected.reference(), networks, null);
    }
}
