package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageFluidKey;
import com.huanghuang.rsintegration.storage.StorageFluidOperationResult;
import com.huanghuang.rsintegration.storage.StorageFluidSnapshot;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StoredFluid;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 直接读取和交付任务账户中的类型化流体余额，不从公共库存补取。 */
public final class TaskFluidAccess {
    private static final StorageBackendId ACCOUNT = new StorageBackendId("task_account");
    private final RuntimeMaterialAccount account;
    private final boolean authorized;

    private TaskFluidAccess(RuntimeMaterialAccount account, boolean authorized) {
        this.account = account;
        this.authorized = authorized;
    }

    /** 仅没有任务账户时返回 null；身份不匹配或冻结的账户仍阻止回退到网络。 */
    @Nullable
    public static TaskFluidAccess capture(ServerPlayer player, @Nullable CraftStorageEndpoint endpoint) {
        RuntimeMaterialAccount account = RuntimeMaterialAccount.current();
        return account == null ? null : new TaskFluidAccess(account,
                account.belongsTo(player) && account.usesEndpoint(endpoint));
    }

    public boolean usable() { return authorized && account.usable(); }

    public StorageFluidSnapshot snapshot() {
        List<StoredFluid> fluids = new ArrayList<>();
        if (usable()) account.heldFluids().forEach((key, amount) -> {
            FluidStack fluid = key.toStack(1);
            if (!fluid.isEmpty() && amount > 0L) {
                fluids.add(new StoredFluid(StorageFluidKey.fromStack(ACCOUNT, fluid), amount));
            }
        });
        return new StorageFluidSnapshot(ACCOUNT, fluids);
    }

    /** 同一身份可按 int 分片，但必须整份需求满足才扣减。模拟和失败均不改余额。 */
    public StorageFluidOperationResult extract(StorageFluidKey key, long amount, boolean simulate) {
        var mode = simulate ? StorageOperationMode.SIMULATE : StorageOperationMode.PERFORM;
        if (!usable() || amount <= 0L || key == null || !ACCOUNT.equals(key.backendId())) {
            return StorageFluidOperationResult.extracted(mode, Math.max(0L, amount), List.of());
        }
        FluidStack requested = key.toStack(1);
        if (requested.isEmpty()) return StorageFluidOperationResult.extracted(mode, amount, List.of());
        List<FluidStack> extracted = account.takeFluid(requested, amount, simulate);
        if (extracted.isEmpty()) return StorageFluidOperationResult.extracted(mode, amount, List.of());
        return StorageFluidOperationResult.extracted(mode, amount, extracted);
    }

    /** 已确认未交付或已从机器取回的实物回到原账户，不能依赖退款时的线程作用域。 */
    public void refund(List<FluidStack> fluids) {
        if (!authorized) throw new IllegalStateException("任务流体账户身份不匹配");
        for (FluidStack fluid : fluids) {
            if (fluid != null && !fluid.isEmpty()) account.addFluid(fluid);
        }
    }

    public void markUncertain(FluidStack fluid, long amount, String reason) {
        if (!authorized) throw new IllegalStateException("任务流体账户身份不匹配");
        account.markTransferUncertain(fluid, amount, reason);
    }

    public void markUncertain(List<FluidStack> fluids, String reason) {
        if (!authorized) throw new IllegalStateException("任务流体账户身份不匹配");
        account.markTransfersUncertain(fluids, reason);
    }

    public void markUncertain(UUID transferId, List<FluidStack> fluids, String reason) {
        if (!authorized) throw new IllegalStateException("任务流体账户身份不匹配");
        account.markTransfersUncertain(transferId, fluids, reason);
    }

    public void markUncertain(long amount, String reason) {
        if (!authorized) throw new IllegalStateException("任务流体账户身份不匹配");
        account.markTransferUncertain(null, amount, reason);
    }

    public void markUncertain() { markUncertain(0L, "流体转移结果不明"); }
}
