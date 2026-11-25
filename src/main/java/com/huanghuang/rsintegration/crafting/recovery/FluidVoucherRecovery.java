package com.huanghuang.rsintegration.crafting.recovery;

import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.MaterialTransferAuditLedger;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.storage.StorageDiagnosticCode;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageVoucherInsert;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Function;

final class FluidVoucherRecovery {
    enum RestoreResult { STORED, PARTIAL, NO_SPACE, NO_PERMISSION, INVALID, UNAVAILABLE, UNKNOWN }
    private static final String ATTEMPT = "RecoveryAttempt";

    private FluidVoucherRecovery() {}

    static RestoreResult use(ServerPlayer player, ItemStack voucher) {
        if (locked(voucher)) return RestoreResult.UNKNOWN;
        CraftStorageEndpoint endpoint;
        try {
            endpoint = StorageRestockSupport.resolve(player).filter(candidate ->
                    "beyonddimensions".equals(candidate.session().reference().backendId().value())).orElse(null);
        } catch (RuntimeException | LinkageError unavailable) {
            endpoint = null;
        }
        if (endpoint != null) return restoreEndpoint(player, voucher, endpoint);
        HitResult hit = player.pick(5.0D, 0.0F, false);
        INetwork network = hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                ? RSIntegrationNetwork.resolveNetworkStrict(player.server, player.level().dimension(), block.getBlockPos())
                : null;
        return restore(player, voucher, network != null ? network : RSIntegrationNetwork.resolveCurrentNetworkFromPlayer(player));
    }

    @Nullable
    static RestoreResult atBlock(ServerPlayer player, ItemStack voucher, BlockPos pos) {
        INetwork network = RSIntegrationNetwork.resolveNetworkStrict(player.server, player.level().dimension(), pos);
        return network == null ? null : restore(player, voucher, network);
    }

    static RestoreResult restore(ServerPlayer player, ItemStack voucher, @Nullable INetwork network) {
        return recover(player, voucher, fluid -> {
            var result = RefinedStorageVoucherInsert.insert(network, player, fluid);
            return new Receipt(result.status(), (int) result.transferredAmount());
        });
    }

    static RestoreResult restoreEndpoint(ServerPlayer player, ItemStack voucher, CraftStorageEndpoint endpoint) {
        return recover(player, voucher, fluid -> {
            ItemStack token;
            try {
                if (endpoint == null) return new Receipt(StorageOperationStatus.UNAVAILABLE, 0);
                if (!endpoint.session().hasPermission(player, StoragePermission.INSERT)) {
                    return new Receipt(StorageOperationStatus.DENIED, 0);
                }
                token = FluidTokenItem.token(fluid);
            } catch (RuntimeException | LinkageError unavailable) {
                return new Receipt(StorageOperationStatus.UNAVAILABLE, 0);
            }
            var result = CraftStorageEndpoint.insertInternal(endpoint, player, token, false);
            if (result == null || result.kind() != StorageOperationResult.Kind.INSERT
                    || result.mode() != StorageOperationMode.PERFORM || result.requestedAmount() != fluid.getAmount()
                    || result.transferredAmount().isEmpty()) return Receipt.unknown();
            ItemStack remaining = result.remainder().orElse(null);
            if (remaining == null || !remaining.isEmpty() && !FluidTokenItem.fluid(remaining).isFluidEqual(fluid)
                    || result.transferredAmount().getAsLong() != (long) fluid.getAmount() - remaining.getCount()) {
                return Receipt.unknown();
            }
            // BD 在实际 insert 前准备失败时仍持有全部流体，凭证可以重试。
            if (result.status() == StorageOperationStatus.FAILED
                    && (result.diagnosticCode() == StorageDiagnosticCode.BACKEND_EXCEPTION
                    || result.diagnosticCode() == StorageDiagnosticCode.PERMISSION_CHECK_FAILED)
                    && remaining.getCount() == fluid.getAmount()) {
                return new Receipt(StorageOperationStatus.UNAVAILABLE, 0);
            }
            return new Receipt(result.status(), fluid.getAmount() - remaining.getCount());
        });
    }

    private record Receipt(StorageOperationStatus status, int accepted) {
        static Receipt unknown() { return new Receipt(StorageOperationStatus.INDETERMINATE, 0); }
    }

    private static boolean locked(ItemStack voucher) {
        return voucher.hasTag() && voucher.getTag().hasUUID(ATTEMPT);
    }

    private static RestoreResult recover(ServerPlayer player, ItemStack voucher, Function<FluidStack, Receipt> insert) {
        if (!(voucher.getItem() instanceof FluidRefundItem) || voucher.getCount() != 1) return RestoreResult.INVALID;
        if (locked(voucher)) return RestoreResult.UNKNOWN;
        var raw = FluidRefundItem.rawFluid(voucher);
        FluidStack fluid = FluidStack.loadFluidStackFromNBT(raw);
        int amount = raw.getInt("Amount");
        if (fluid.isEmpty() || amount <= 0 || amount != fluid.getAmount()) return RestoreResult.INVALID;
        Receipt receipt;
        try {
            receipt = insert.apply(fluid);
        } catch (RuntimeException | LinkageError afterInsert) {
            receipt = Receipt.unknown();
        }
        int accepted = receipt.accepted();
        RestoreResult outcome = switch (receipt.status()) {
            case SUCCESS -> accepted == amount ? RestoreResult.STORED : RestoreResult.UNKNOWN;
            case PARTIAL -> accepted > 0 && accepted < amount ? RestoreResult.PARTIAL : RestoreResult.UNKNOWN;
            case REJECTED -> accepted == 0 ? RestoreResult.NO_SPACE : RestoreResult.UNKNOWN;
            case DENIED -> accepted == 0 ? RestoreResult.NO_PERMISSION : RestoreResult.UNKNOWN;
            case INVALID_REQUEST, UNAVAILABLE -> accepted == 0 ? RestoreResult.UNAVAILABLE : RestoreResult.UNKNOWN;
            default -> RestoreResult.UNKNOWN;
        };
        if (outcome == RestoreResult.UNKNOWN) {
            UUID attempt = UUID.randomUUID();
            voucher.getOrCreateTag().putUUID(ATTEMPT, attempt);
            player.getInventory().setChanged();
            MaterialTransferAuditLedger.get(player.server).recordUnknown(attempt, attempt, player.getUUID(),
                    MaterialTransferAuditLedger.ResourceType.FLUID, ForgeRegistries.FLUIDS.getKey(fluid.getFluid()),
                    fluid.getTag(), amount, "fluid_voucher_insert_unknown", System.currentTimeMillis());
        } else if (outcome == RestoreResult.STORED) voucher.setCount(0);
        else if (outcome == RestoreResult.PARTIAL) raw.putInt("Amount", amount - accepted);
        else return outcome;
        MaterialSources.invalidateFor(player);
        FluidRecoveryDelivery.sync(player);
        return outcome;
    }
}
