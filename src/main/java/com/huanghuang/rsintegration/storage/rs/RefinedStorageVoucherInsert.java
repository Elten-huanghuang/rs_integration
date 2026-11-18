package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.NetworkOutputReception;
import com.huanghuang.rsintegration.storage.StorageDiagnosticCode;
import com.huanghuang.rsintegration.storage.StorageFluidOperationResult;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fluids.FluidStack;

import java.util.List;

/** 对已确定的目标网络恢复凭证；不依赖物品存储缓存是否可用。 */
public final class RefinedStorageVoucherInsert {
    private RefinedStorageVoucherInsert() {}

    public static StorageFluidOperationResult insert(INetwork network, ServerPlayer player, FluidStack fluid) {
        try {
            if (network == null || !network.canRun()) return refused(fluid, StorageOperationStatus.UNAVAILABLE);
            var security = network.getSecurityManager();
            if (security != null && !security.hasPermission(Permission.INSERT, player)) {
                return refused(fluid, StorageOperationStatus.DENIED);
            }
        } catch (RuntimeException | LinkageError unavailable) {
            return refused(fluid, StorageOperationStatus.UNAVAILABLE);
        }
        FluidStack remainder = NetworkOutputReception.internal(
                () -> network.insertFluid(fluid.copy(), fluid.getAmount(), Action.PERFORM));
        if (remainder == null || !remainder.isEmpty() && !remainder.isFluidEqual(fluid)
                || remainder.getAmount() > fluid.getAmount()) {
            return new StorageFluidOperationResult(StorageOperationResult.Kind.INSERT, StorageOperationMode.PERFORM,
                    StorageOperationStatus.INDETERMINATE, fluid.getAmount(), 0, false, List.of(), List.of(),
                    null, StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
        }
        var result = StorageFluidOperationResult.inserted(StorageOperationMode.PERFORM, fluid, remainder);
        if (result.transferredAmount() > 0) {
            try {
                var tracker = network.getFluidStorageTracker();
                if (tracker != null) {
                    FluidStack stored = fluid.copy();
                    stored.setAmount((int) result.transferredAmount());
                    tracker.changed(player, stored);
                }
            } catch (RuntimeException | LinkageError trackingFailed) {
                RSIntegrationMod.LOGGER.warn("流体凭证已入网，但 RS tracker 更新失败", trackingFailed);
            }
        }
        return result;
    }

    private static StorageFluidOperationResult refused(FluidStack fluid, StorageOperationStatus status) {
        return StorageFluidOperationResult.failedInsert(StorageOperationMode.PERFORM, fluid, status,
                StorageDiagnosticCode.NONE);
    }
}
