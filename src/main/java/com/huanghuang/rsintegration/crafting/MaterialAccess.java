package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageSnapshotStatus;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 节点和委托显式持有的取料来源。外部端点始终只代表公共存储。 */
public final class MaterialAccess {
    public static final MaterialAccess EXTERNAL = new MaterialAccess(null);
    @Nullable private final RuntimeMaterialAccount account;

    MaterialAccess(@Nullable RuntimeMaterialAccount account) { this.account = account; }

    public ExtractionLedger newLedger() { return new ExtractionLedger(account); }

    private boolean authorized(ServerPlayer player, @Nullable CraftStorageEndpoint endpoint) {
        return account != null && account.belongsTo(player) && account.usesEndpoint(endpoint);
    }

    public Map<StackKey, Long> available(ServerPlayer player, @Nullable CraftStorageEndpoint endpoint) {
        if (account == null) return MaterialSources.listAllAvailable(player, endpoint);
        return authorized(player, endpoint) && account.usable() ? account.available(player) : Map.of();
    }

    public StorageSnapshotResult snapshot(ServerPlayer player, CraftStorageEndpoint endpoint) {
        if (account != null && (!authorized(player, endpoint) || !account.usable())) {
            return StorageSnapshotResult.failure(StorageSnapshotStatus.UNAVAILABLE);
        }
        var external = endpoint.snapshot(player);
        if (account == null || !external.successful()) return external;
        var snapshot = external.snapshot().orElseThrow();
        List<StoredItem> items = new ArrayList<>(snapshot.items());
        account.heldMaterials().forEach((key, amount) ->
                items.add(new StoredItem(endpoint.session().itemKey(key.toStack()), amount)));
        return StorageSnapshotResult.success(new StorageSnapshot(snapshot.backendId(), items));
    }

    public StorageOperationResult extractExact(ServerPlayer player, CraftStorageEndpoint endpoint,
                                             ItemStack stack, long amount, boolean simulate) {
        return account == null ? endpoint.extractExact(player, stack, amount, simulate)
                : extract(player, endpoint, Ingredient.of(stack), stack, amount, simulate);
    }

    public StorageOperationResult extractMatching(ServerPlayer player, CraftStorageEndpoint endpoint,
                                                 Ingredient ingredient, long amount, boolean simulate) {
        return account == null ? endpoint.extractMatching(player, ingredient, amount, simulate)
                : extract(player, endpoint, ingredient, null, amount, simulate);
    }

    private StorageOperationResult extract(ServerPlayer player, CraftStorageEndpoint endpoint,
                                           Ingredient ingredient, @Nullable ItemStack exact, long amount, boolean simulate) {
        if (!authorized(player, endpoint) || !account.usable()) return StorageOperationResult.extracted(
                simulate ? StorageOperationMode.SIMULATE : StorageOperationMode.PERFORM, amount, List.of());
        return ExtractionLedger.takeFromAccount(account, player, ingredient, exact, amount, simulate);
    }

    public StorageOperationResult refund(ServerPlayer player, CraftStorageEndpoint endpoint, ItemStack stack) {
        if (account == null) return CraftStorageEndpoint.insertInternal(endpoint, player, stack, false);
        if (!authorized(player, endpoint)) throw new IllegalStateException("材料退款账户身份不符");
        account.add(stack);
        return StorageOperationResult.inserted(StorageOperationMode.PERFORM, stack, ItemStack.EMPTY);
    }

    @Nullable
    public TaskFluidAccess fluids(ServerPlayer player, @Nullable CraftStorageEndpoint endpoint) {
        return TaskFluidAccess.capture(account, player, endpoint);
    }
}
