package com.huanghuang.rsintegration.storage.disk.rs;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.ItemStackUtils;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.AccessType;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskContainerContext;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskListener;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.apiimpl.network.node.diskdrive.DiskDriveNetworkNode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;

/** 物品和流体共用的虚拟供给盘，生命周期由在线磁盘仓的物理槽位决定。 */
public final class InfiniteDisk<T> implements IStorageDisk<T> {
    private static final ResourceLocation ID = new ResourceLocation(RSIntegrationMod.MOD_ID, "infinite_disk");

    private record ResourceType<T>(Class<T> stackClass, T empty, Function<ResourceLocation, T> resolve, Function<T, T> copy,
                                   ToIntFunction<T> amount, ObjIntConsumer<T> setAmount,
                                   BiPredicate<T, T> sameResource, Function<DiskDriveNetworkNode, IStorageDisk<?>[]> slots) {
        T withAmount(T stack, int size) {
            if (size <= 0 || amount.applyAsInt(stack) <= 0) return empty;
            T result = copy.apply(stack);
            setAmount.accept(result, size);
            return result;
        }

        T cast(Object stack) { return stackClass.cast(stack); }
    }

    private static final ResourceType<ItemStack> ITEM = new ResourceType<>(ItemStack.class, ItemStack.EMPTY,
            id -> ForgeRegistries.ITEMS.containsKey(id) ? new ItemStack(ForgeRegistries.ITEMS.getValue(id)) : ItemStack.EMPTY,
            ItemStack::copy, ItemStack::getCount, ItemStack::setCount,
            (preset, stack) -> ItemStack.isSameItemSameTags(preset, ItemStackUtils.normalizeEmptyTag(stack)),
            DiskDriveNetworkNode::getItemDisks);
    private static final ResourceType<FluidStack> FLUID = new ResourceType<>(FluidStack.class, FluidStack.EMPTY,
            id -> ForgeRegistries.FLUIDS.containsKey(id) ? new FluidStack(ForgeRegistries.FLUIDS.getValue(id), 1) : FluidStack.EMPTY,
            FluidStack::copy, FluidStack::getAmount, FluidStack::setAmount,
            (preset, stack) -> preset.getFluid() == stack.getFluid() && (stack.getTag() == null || stack.getTag().isEmpty()),
            DiskDriveNetworkNode::getFluidDisks);

    private final DiskDriveNetworkNode drive;
    private final int slot;
    private final InfiniteDiskItem item;
    private final ResourceLocation resource;
    private final ResourceType<T> type;
    private final T template;

    private InfiniteDisk(DiskDriveNetworkNode drive, int slot, InfiniteDiskItem item, ResourceLocation resource,
                         ResourceType<T> type, T template) {
        this.drive = drive;
        this.slot = slot;
        this.item = item;
        this.resource = resource;
        this.type = type;
        this.template = template;
    }

    public static boolean mount(ItemStack stack, int slot, IStorageDisk<ItemStack>[] items,
                                IStorageDisk<FluidStack>[] fluids) {
        if (!(stack.getItem() instanceof InfiniteDiskItem item)) return false;
        items[slot] = null;
        fluids[slot] = null;
        // 磁盘操作器和其他离线读盘路径不能从虚拟盘取料。
        if (UnifiedMountCoordinator.caller() instanceof DiskDriveNetworkNode drive) {
            ResourceLocation resource = item.resource(stack);
            if (resource != null) {
                switch (item.getType()) {
                    case ITEM -> items[slot] = create(drive, slot, stack, item, resource, ITEM);
                    case FLUID -> fluids[slot] = create(drive, slot, stack, item, resource, FLUID);
                }
            }
        }
        return true;
    }

    private static <T> InfiniteDisk<T> create(DiskDriveNetworkNode drive, int slot, ItemStack stack,
                                              InfiniteDiskItem item, ResourceLocation resource, ResourceType<T> type) {
        T template = type.resolve.apply(resource);
        if (type.amount.applyAsInt(template) <= 0) return null;
        item.writeRecord(stack);
        return new InfiniteDisk<>(drive, slot, item, resource, type, template);
    }

    public boolean online(INetwork network) {
        if (network == null || drive.getNetwork() != network || !drive.isActive()
                || type.slots.apply(drive)[slot] != this || getAccessType() == AccessType.INSERT) return false;
        ItemStack physical = drive.getDisks().getStackInSlot(slot);
        return physical.is(item) && resource.equals(item.resource(physical)) && network.canRun();
    }

    public T empty() { return type.empty; }

    public static boolean supplies(INetwork network, ItemStack stack) {
        if (network == null || stack.isEmpty()) return false;
        return supplies(network, network.getItemStorageCache(), stack, ITEM);
    }

    public static boolean supplies(INetwork network, FluidStack stack) {
        if (network == null || stack.isEmpty()) return false;
        return supplies(network, network.getFluidStorageCache(), stack, FLUID);
    }

    private static <T> boolean supplies(INetwork network, IStorageCache<T> cache, T stack, ResourceType<T> type) {
        if (cache == null) return false;
        for (var storage : cache.getStorages()) {
            if (storage instanceof InfiniteDisk<?> disk && disk.type == type && disk.online(network)
                    && type.sameResource.test(type.cast(disk.template), stack)) return true;
        }
        return false;
    }

    public static List<FluidStack> onlineFluids(INetwork network) {
        if (network == null || !network.canRun() || network.getFluidStorageCache() == null) return List.of();
        return onlineResources(network, network.getFluidStorageCache(), FLUID);
    }

    private static <T> List<T> onlineResources(INetwork network, IStorageCache<T> cache, ResourceType<T> type) {
        List<T> result = new ArrayList<>();
        for (var storage : cache.getStorages()) {
            if (storage instanceof InfiniteDisk<?> disk && disk.type == type && disk.online(network)) {
                T stack = type.withAmount(type.cast(disk.template), 1);
                if (result.stream().noneMatch(known -> type.sameResource.test(known, stack))) result.add(stack);
            }
        }
        return List.copyOf(result);
    }

    @Override public Collection<T> getStacks() {
        return online(drive.getNetwork()) ? List.of(type.withAmount(template, Integer.MAX_VALUE)) : List.of();
    }

    @Override public T extract(T source, int size, int flags, Action action) {
        if (size <= 0 || type.amount.applyAsInt(source) <= 0 || !type.sameResource.test(template, source)
                || (flags & IComparer.COMPARE_QUANTITY) != 0 && type.amount.applyAsInt(source) != Integer.MAX_VALUE
                || !online(drive.getNetwork())) return empty();
        return type.withAmount(template, size);
    }

    @Override public T insert(T source, int size, Action action) { return type.withAmount(source, size); }

    @Override public AccessType getAccessType() {
        return drive.getAccessType() == AccessType.INSERT ? AccessType.INSERT : AccessType.EXTRACT;
    }

    @Override public int getStored() { return 0; }
    @Override public int getCapacity() { return 0; }
    @Override public int getPriority() { return drive.getPriority(); }
    @Override public UUID getOwner() { return drive.getOwner(); }
    @Override public int getCacheDelta(int storedPreInsertion, int size, T remainder) { return 0; }
    @Override public void setSettings(IStorageDiskListener listener, IStorageDiskContainerContext context) {}
    @Override public ResourceLocation getFactoryId() { return ID; }
    @Override public CompoundTag writeToNbt() { return new CompoundTag(); }
}
