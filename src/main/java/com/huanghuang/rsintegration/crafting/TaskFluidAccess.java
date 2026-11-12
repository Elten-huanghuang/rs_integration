package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageFluidKey;
import com.huanghuang.rsintegration.storage.StorageFluidOperationResult;
import com.huanghuang.rsintegration.storage.StorageFluidSnapshot;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StoredFluid;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** 只转换现有任务账户中的流体凭证；不持有第二份余额，不从公共库存补取。 */
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
        if (usable()) account.heldMaterials().forEach((key, amount) -> {
            FluidStack fluid = InkFluidSupport.fluid(key.toStack());
            if (!fluid.isEmpty() && amount > 0L) {
                fluids.add(new StoredFluid(StorageFluidKey.fromStack(ACCOUNT, fluid), amount));
            }
        });
        return new StorageFluidSnapshot(ACCOUNT, fluids);
    }

    /** 同一身份可按 int 分片，但必须整份需求满足才扣减。模拟和失败均不改余额。 */
    public StorageFluidOperationResult extract(StorageFluidKey key, long amount, boolean simulate) {
        var mode = simulate ? StorageOperationMode.SIMULATE : StorageOperationMode.PERFORM;
        if (!usable() || amount <= 0L) return StorageFluidOperationResult.extracted(mode, amount, List.of());
        List<ItemStack> tokens = new ArrayList<>();
        long remaining = amount;
        for (var entry : account.heldMaterials().entrySet()) {
            ItemStack token = entry.getKey().toStack();
            FluidStack fluid = InkFluidSupport.fluid(token);
            if (fluid.isEmpty() || !StorageFluidKey.fromStack(ACCOUNT, fluid).equals(key)) continue;
            long take = Math.min(remaining, entry.getValue());
            remaining -= take;
            while (take > 0L) {
                int part = (int) Math.min(Integer.MAX_VALUE, take);
                tokens.add(token.copyWithCount(part));
                take -= part;
            }
            if (remaining == 0L) break;
        }
        if (remaining > 0L) return StorageFluidOperationResult.extracted(mode, amount, List.of());
        if (!simulate) for (ItemStack token : tokens) {
            if (!account.take(token, token.getCount()).complete()) {
                account.markTransferUncertain();
                throw new IllegalStateException("任务流体账户扣减不完整");
            }
        }
        return StorageFluidOperationResult.extracted(mode, amount, tokens.stream().map(InkFluidSupport::fluid).toList());
    }

    /** 已确认未交付或已从机器取回的实物回到原账户，不能依赖退款时的线程作用域。 */
    public void refund(List<FluidStack> fluids) {
        if (!authorized) throw new IllegalStateException("任务流体账户身份不匹配");
        for (FluidStack fluid : fluids) {
            if (fluid != null && !fluid.isEmpty()) account.add(InkFluidSupport.token(fluid));
        }
    }

    public void markUncertain() { account.markTransferUncertain(); }
}
