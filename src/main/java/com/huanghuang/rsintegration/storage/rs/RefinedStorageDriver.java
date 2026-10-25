package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.storage.StoragePermission;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import java.util.Set;
import net.minecraft.world.item.Item;

import com.huanghuang.rsintegration.storage.StorageItemChangeListener;
import com.huanghuang.rsintegration.storage.StorageItemSubscription;
import java.util.Optional;
import java.util.List;

/** Replaceable boundary around native RS calls. This interface deliberately exposes no RS types. */
interface RefinedStorageDriver {
    boolean isAvailable();

    RefinedStorageSnapshotRead snapshotItems();

    default List<FluidStack> snapshotFluids() {
        return List.of();
    }

    default Optional<StorageItemSubscription> subscribeItemChanges(StorageItemChangeListener listener) {
        return Optional.empty();
    }

    /** Optional candidate filter. Drivers may return a superset; matching remains authoritative. */
    default RefinedStorageSnapshotRead snapshotItems(Set<Item> itemTypes) {
        return snapshotItems();
    }

    /** 返回同一 Key 在宽库存后端上的精确数量；原生 RS 不足时由驱动补充。 */
    default long exactStoredAmount(ItemStack stack, long nativeAmount) {
        return nativeAmount;
    }

    boolean hasPermission(ServerPlayer player, StoragePermission permission);

    ItemStack extract(ItemStack template, int amount, boolean simulate);

    ItemStack insert(ItemStack stack, boolean simulate);

    default FluidStack extractFluid(
            FluidStack template,
            int amount,
            boolean simulate
    ) {
        return FluidStack.EMPTY;
    }

    default FluidStack insertFluid(
            FluidStack stack,
            boolean simulate
    ) {
        return stack;
    }

    default void recordFluidInsertion(
            ServerPlayer player,
            FluidStack accepted
    ) {}

    /** Records the preflight accepted amount for RS change tracking; never use it for settlement. */
    void recordInsertion(ServerPlayer player, ItemStack accepted);
}
