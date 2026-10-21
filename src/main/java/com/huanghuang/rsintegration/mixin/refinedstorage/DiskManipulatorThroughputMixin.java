package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.config.RSStorageConfig;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.storage.disk.core.FrozenKey;
import com.huanghuang.rsintegration.storage.disk.core.ResourceTable;
import com.huanghuang.rsintegration.storage.disk.rs.DiskManipulatorBudget;
import com.huanghuang.rsintegration.storage.disk.rs.DiskManipulatorTransferSession;
import com.huanghuang.rsintegration.storage.disk.rs.DiskManipulatorTransferSession.Step;
import com.huanghuang.rsintegration.storage.disk.rs.UnifiedBoundDisk;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import com.refinedmods.refinedstorage.apiimpl.network.node.diskmanipulator.DiskManipulatorNetworkNode;
import com.refinedmods.refinedstorage.inventory.fluid.FluidInventory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/** 将磁盘操纵器的单次搬运转为受时间预算约束的分片批处理。 */
@Mixin(value = DiskManipulatorNetworkNode.class, remap = false)
public abstract class DiskManipulatorThroughputMixin {
    @Shadow
    private void moveDriveToOutput(int slot) {}

    @Unique
    private DiskManipulatorTransferSession<ItemStack> rsi$itemInsert;
    @Unique
    private DiskManipulatorTransferSession<ItemStack> rsi$itemExtract;
    @Unique
    private DiskManipulatorTransferSession<FluidStack> rsi$fluidInsert;
    @Unique
    private DiskManipulatorTransferSession<FluidStack> rsi$fluidExtract;

    @ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target =
            "Lcom/refinedmods/refinedstorage/inventory/item/UpgradeItemHandler;getSpeed()I"), require = 1)
    private int rsi$useBatchSpeed(int original) {
        if (!RSStorageConfig.enabled(RSStorageConfig.FAST_DISK_MANIPULATOR)) {
            rsi$clearSessions();
            return original;
        }
        return 1;
    }

    @Inject(method = "insertItemIntoNetwork", at = @At("HEAD"), cancellable = true, require = 1)
    private void rsi$insertItems(IStorageDisk<ItemStack> disk, CallbackInfo ci) {
        if (!RSStorageConfig.enabled(RSStorageConfig.FAST_DISK_MANIPULATOR)) {
            rsi$itemInsert = null;
            return;
        }
        rsi$batchInsertItems(disk);
        ci.cancel();
    }

    @Inject(method = "extractItemFromNetwork", at = @At("HEAD"), cancellable = true, require = 1)
    private void rsi$extractItems(IStorageDisk<ItemStack> disk, int slot, CallbackInfo ci) {
        if (!RSStorageConfig.enabled(RSStorageConfig.FAST_DISK_MANIPULATOR)) {
            rsi$itemExtract = null;
            return;
        }
        rsi$batchExtractItems(disk, slot);
        ci.cancel();
    }

    @Inject(method = "insertFluidIntoNetwork", at = @At("HEAD"), cancellable = true, require = 1)
    private void rsi$insertFluids(IStorageDisk<FluidStack> disk, int slot, CallbackInfo ci) {
        if (!RSStorageConfig.enabled(RSStorageConfig.FAST_DISK_MANIPULATOR)) {
            rsi$fluidInsert = null;
            return;
        }
        rsi$batchInsertFluids(disk, slot);
        ci.cancel();
    }

    @Inject(method = "extractFluidFromNetwork", at = @At("HEAD"), cancellable = true, require = 1)
    private void rsi$extractFluids(IStorageDisk<FluidStack> disk, int slot, CallbackInfo ci) {
        if (!RSStorageConfig.enabled(RSStorageConfig.FAST_DISK_MANIPULATOR)) {
            rsi$fluidExtract = null;
            return;
        }
        rsi$batchExtractFluids(disk, slot);
        ci.cancel();
    }

    @Unique
    private void rsi$batchInsertItems(IStorageDisk<ItemStack> disk) {
        DiskManipulatorNetworkNode node = rsi$node();
        MinecraftServer server = node.getLevel().getServer();
        INetwork network = node.getNetwork();
        if (server == null || network == null) return;
        DiskManipulatorTransferSession<ItemStack> session = rsi$itemInsert;
        if (session == null || session.disk != disk) {
            session = new DiskManipulatorTransferSession<>(disk, -1);
            rsi$itemInsert = session;
        }
        long now = server.getTickCount();
        if (now < session.cooldownUntil) return;
        boolean progressed = false;
        int executed = 0;
        int maxOps = RSStorageConfig.diskManipulatorMaxOperationsPerTick();
        for (int op = 0; op < maxOps && DiskManipulatorBudget.hasTime(server); op++) {
            Step step = rsi$insertItemStep(disk, session, network);
            executed++;
            if (step == Step.STOP) break;
            if (step == Step.PROGRESS) progressed = true;
        }
        if (executed > 0) {
            session.cooldownUntil = progressed ? 0L
                    : now + RSStorageConfig.diskManipulatorNoProgressCooldownTicks();
        }
    }

    @Unique
    private Step rsi$insertItemStep(IStorageDisk<ItemStack> disk,
                                    DiskManipulatorTransferSession<ItemStack> session, INetwork network) {
        int compare = rsi$node().getCompare();
        int maxItems = RSStorageConfig.diskManipulatorMaxItemsPerOperation();
        if (disk instanceof UnifiedBoundDisk<?> unified) {
            ResourceTable table = unified.table();
            int size = table.size();
            if (size == 0) return Step.STOP;
            if (session.livePosition >= size) {
                if (session.passProgress) {
                    session.livePosition = 0;
                    session.passProgress = false;
                    return Step.SKIP;
                }
                session.livePosition = 0;
                return Step.STOP;
            }
            int position = session.livePosition;
            int slot = table.liveSlot(position);
            FrozenKey key = table.key(slot);
            long amount = table.amount(slot);
            if (key == null || amount <= 0) {
                session.livePosition++;
                return Step.SKIP;
            }
            int request = Math.min(SaturatingLongMath.clampToInt(amount), maxItems);
            ItemStack extracted = disk.extract(key.itemStack(request), request, compare, Action.PERFORM);
            if (extracted.isEmpty()) {
                session.livePosition++;
                return Step.SKIP;
            }
            ItemStack remainder = network.insertItem(extracted, extracted.getCount(), Action.PERFORM);
            if (!remainder.isEmpty()) disk.insert(remainder, remainder.getCount(), Action.PERFORM);
            int moved = extracted.getCount() - (remainder.isEmpty() ? 0 : remainder.getCount());
            if (table.exactSlot(key) < 0) {
                // 删除会把末尾槽位顶到当前位置，下一轮应继续检查同一位置。
            } else if (moved <= 0) {
                session.livePosition++;
            }
            return moved > 0 ? Step.PROGRESS : Step.SKIP;
        }
        if (session.sources == null) session.sources = new ArrayList<>(disk.getStacks());
        if (session.cursor >= session.sources.size()) {
            session.sources = null;
            session.cursor = 0;
            return Step.STOP;
        }
        ItemStack source = session.sources.get(session.cursor);
        if (source.isEmpty()) {
            session.cursor++;
            return Step.SKIP;
        }
        int request = Math.min(source.getCount(), maxItems);
        ItemStack extracted = disk.extract(source, request, compare, Action.PERFORM);
        if (extracted.isEmpty()) {
            session.cursor++;
            return Step.SKIP;
        }
        ItemStack remainder = network.insertItem(extracted, extracted.getCount(), Action.PERFORM);
        if (!remainder.isEmpty()) disk.insert(remainder, remainder.getCount(), Action.PERFORM);
        int moved = extracted.getCount() - (remainder.isEmpty() ? 0 : remainder.getCount());
        if (source.isEmpty() || moved <= 0) session.cursor++;
        return moved > 0 ? Step.PROGRESS : Step.SKIP;
    }

    @Unique
    private void rsi$batchExtractItems(IStorageDisk<ItemStack> disk, int slot) {
        DiskManipulatorNetworkNode node = rsi$node();
        MinecraftServer server = node.getLevel().getServer();
        INetwork network = node.getNetwork();
        if (server == null || network == null) return;
        DiskManipulatorTransferSession<ItemStack> session = rsi$itemExtract;
        if (session == null || session.disk != disk || session.slot != slot) {
            session = new DiskManipulatorTransferSession<>(disk, slot);
            rsi$itemExtract = session;
        }
        long now = server.getTickCount();
        if (now < session.cooldownUntil) return;
        boolean progressed = false;
        int executed = 0;
        int maxOps = RSStorageConfig.diskManipulatorMaxOperationsPerTick();
        for (int op = 0; op < maxOps && DiskManipulatorBudget.hasTime(server); op++) {
            Step step = rsi$extractItemStep(disk, session, network);
            executed++;
            if (step == Step.STOP) {
                moveDriveToOutput(slot);
                rsi$itemExtract = null;
                return;
            }
            if (step == Step.PROGRESS) progressed = true;
        }
        if (executed > 0) {
            session.cooldownUntil = progressed ? 0L
                    : now + RSStorageConfig.diskManipulatorNoProgressCooldownTicks();
        }
    }

    @Unique
    private Step rsi$extractItemStep(IStorageDisk<ItemStack> disk,
                                     DiskManipulatorTransferSession<ItemStack> session, INetwork network) {
        int compare = rsi$node().getCompare();
        if (session.sources == null) {
            session.sources = rsi$itemSources(network);
            session.cursor = 0;
            if (session.sources.isEmpty()) return Step.STOP;
        }
        if (session.cursor >= session.sources.size()) {
            boolean progressed = session.passProgress;
            session.sources = null;
            session.cursor = 0;
            session.passProgress = false;
            if (!progressed) return Step.STOP;
            return Step.SKIP;
        }
        ItemStack source = session.sources.get(session.cursor++);
        if (source.isEmpty()) return Step.SKIP;
        int request = RSStorageConfig.diskManipulatorMaxItemsPerOperation();
        int capacity = disk.getCapacity();
        if (capacity >= 0) {
            int free = capacity - disk.getStored();
            if (free <= 0) return Step.STOP;
            request = Math.min(request, free);
        }
        ItemStack extracted = network.extractItem(source.copy(), request, compare, Action.PERFORM);
        if (extracted.isEmpty()) return Step.SKIP;
        ItemStack remainder = disk.insert(extracted, extracted.getCount(), Action.PERFORM);
        if (!remainder.isEmpty()) network.insertItem(remainder, remainder.getCount(), Action.PERFORM);
        int moved = extracted.getCount() - (remainder.isEmpty() ? 0 : remainder.getCount());
        if (moved > 0) session.passProgress = true;
        return moved > 0 ? Step.PROGRESS : Step.SKIP;
    }

    @Unique
    private List<ItemStack> rsi$itemSources(INetwork network) {
        IItemHandler filters = rsi$node().getItemFilters();
        List<ItemStack> filtered = new ArrayList<>();
        for (int slot = 0; slot < filters.getSlots(); slot++) {
            ItemStack stack = filters.getStackInSlot(slot);
            if (!stack.isEmpty()) filtered.add(stack.copy());
        }
        if (!filtered.isEmpty()) return filtered;
        List<ItemStack> result = new ArrayList<>();
        for (StackListEntry<ItemStack> entry : network.getItemStorageCache().getList().getStacks()) {
            ItemStack stack = entry.getStack();
            if (!stack.isEmpty()) result.add(stack);
        }
        return result;
    }

    @Unique
    private void rsi$batchInsertFluids(IStorageDisk<FluidStack> disk, int slot) {
        DiskManipulatorNetworkNode node = rsi$node();
        MinecraftServer server = node.getLevel().getServer();
        INetwork network = node.getNetwork();
        if (server == null || network == null) return;
        DiskManipulatorTransferSession<FluidStack> session = rsi$fluidInsert;
        if (session == null || session.disk != disk || session.slot != slot) {
            session = new DiskManipulatorTransferSession<>(disk, slot);
            rsi$fluidInsert = session;
        }
        long now = server.getTickCount();
        if (now < session.cooldownUntil) return;
        boolean progressed = false;
        int executed = 0;
        int maxOps = RSStorageConfig.diskManipulatorMaxOperationsPerTick();
        for (int op = 0; op < maxOps && DiskManipulatorBudget.hasTime(server); op++) {
            Step step = rsi$insertFluidStep(disk, session, network);
            executed++;
            if (step == Step.STOP) {
                moveDriveToOutput(slot);
                rsi$fluidInsert = null;
                return;
            }
            if (step == Step.PROGRESS) progressed = true;
        }
        if (executed > 0) {
            session.cooldownUntil = progressed ? 0L
                    : now + RSStorageConfig.diskManipulatorNoProgressCooldownTicks();
        }
    }

    @Unique
    private Step rsi$insertFluidStep(IStorageDisk<FluidStack> disk,
                                     DiskManipulatorTransferSession<FluidStack> session, INetwork network) {
        int compare = rsi$node().getCompare();
        int maxItems = RSStorageConfig.diskManipulatorMaxItemsPerOperation();
        if (disk instanceof UnifiedBoundDisk<?> unified) {
            ResourceTable table = unified.table();
            int size = table.size();
            if (size == 0) return Step.STOP;
            if (session.livePosition >= size) {
                session.livePosition = 0;
                return Step.STOP;
            }
            int position = session.livePosition;
            int slot = table.liveSlot(position);
            FrozenKey key = table.key(slot);
            long amount = table.amount(slot);
            if (key == null || amount <= 0) {
                session.livePosition++;
                return Step.SKIP;
            }
            int request = Math.min(SaturatingLongMath.clampToInt(amount), maxItems);
            FluidStack extracted = disk.extract(key.fluidStack(request), request, compare, Action.PERFORM);
            if (extracted.isEmpty()) {
                session.livePosition++;
                return Step.SKIP;
            }
            FluidStack remainder = network.insertFluid(extracted, extracted.getAmount(), Action.PERFORM);
            if (!remainder.isEmpty()) disk.insert(remainder, remainder.getAmount(), Action.PERFORM);
            int moved = extracted.getAmount() - (remainder.isEmpty() ? 0 : remainder.getAmount());
            if (moved > 0) session.passProgress = true;
            if (table.exactSlot(key) < 0) {
                // 删除会把末尾槽位顶到当前位置。
            } else if (moved <= 0) {
                session.livePosition++;
            }
            return moved > 0 ? Step.PROGRESS : Step.SKIP;
        }
        if (session.sources == null) session.sources = new ArrayList<>(disk.getStacks());
        if (session.cursor >= session.sources.size()) {
            if (session.passProgress) {
                session.passProgress = false;
                session.sources = null;
                session.cursor = 0;
                return Step.SKIP;
            }
            session.sources = null;
            session.cursor = 0;
            return Step.STOP;
        }
        FluidStack source = session.sources.get(session.cursor);
        if (source.isEmpty()) {
            session.cursor++;
            return Step.SKIP;
        }
        int request = Math.min(source.getAmount(), maxItems);
        FluidStack extracted = disk.extract(source, request, compare, Action.PERFORM);
        if (extracted.isEmpty()) {
            session.cursor++;
            return Step.SKIP;
        }
        FluidStack remainder = network.insertFluid(extracted, extracted.getAmount(), Action.PERFORM);
        if (!remainder.isEmpty()) disk.insert(remainder, remainder.getAmount(), Action.PERFORM);
        int moved = extracted.getAmount() - (remainder.isEmpty() ? 0 : remainder.getAmount());
        if (moved > 0) session.passProgress = true;
        if (source.isEmpty() || moved <= 0) session.cursor++;
        return moved > 0 ? Step.PROGRESS : Step.SKIP;
    }

    @Unique
    private void rsi$batchExtractFluids(IStorageDisk<FluidStack> disk, int slot) {
        DiskManipulatorNetworkNode node = rsi$node();
        MinecraftServer server = node.getLevel().getServer();
        INetwork network = node.getNetwork();
        if (server == null || network == null) return;
        DiskManipulatorTransferSession<FluidStack> session = rsi$fluidExtract;
        if (session == null || session.disk != disk || session.slot != slot) {
            session = new DiskManipulatorTransferSession<>(disk, slot);
            rsi$fluidExtract = session;
        }
        long now = server.getTickCount();
        if (now < session.cooldownUntil) return;
        boolean progressed = false;
        int executed = 0;
        int maxOps = RSStorageConfig.diskManipulatorMaxOperationsPerTick();
        for (int op = 0; op < maxOps && DiskManipulatorBudget.hasTime(server); op++) {
            Step step = rsi$extractFluidStep(disk, session, network);
            executed++;
            if (step == Step.STOP) {
                moveDriveToOutput(slot);
                rsi$fluidExtract = null;
                return;
            }
            if (step == Step.PROGRESS) progressed = true;
        }
        if (executed > 0) {
            session.cooldownUntil = progressed ? 0L
                    : now + RSStorageConfig.diskManipulatorNoProgressCooldownTicks();
        }
    }

    @Unique
    private Step rsi$extractFluidStep(IStorageDisk<FluidStack> disk,
                                      DiskManipulatorTransferSession<FluidStack> session, INetwork network) {
        int compare = rsi$node().getCompare();
        if (session.sources == null) {
            session.sources = rsi$fluidSources(network);
            session.cursor = 0;
            if (session.sources.isEmpty()) return Step.STOP;
        }
        if (session.cursor >= session.sources.size()) {
            boolean progressed = session.passProgress;
            session.sources = null;
            session.cursor = 0;
            session.passProgress = false;
            if (!progressed) return Step.STOP;
            return Step.SKIP;
        }
        FluidStack source = session.sources.get(session.cursor++);
        if (source.isEmpty()) return Step.SKIP;
        int request = RSStorageConfig.diskManipulatorMaxItemsPerOperation();
        int capacity = disk.getCapacity();
        if (capacity >= 0) {
            int free = capacity - disk.getStored();
            if (free <= 0) return Step.STOP;
            request = Math.min(request, free);
        }
        FluidStack extracted = network.extractFluid(source.copy(), request, compare, Action.PERFORM);
        if (extracted.isEmpty()) return Step.SKIP;
        FluidStack remainder = disk.insert(extracted, extracted.getAmount(), Action.PERFORM);
        if (!remainder.isEmpty()) network.insertFluid(remainder, remainder.getAmount(), Action.PERFORM);
        int moved = extracted.getAmount() - (remainder.isEmpty() ? 0 : remainder.getAmount());
        if (moved > 0) session.passProgress = true;
        return moved > 0 ? Step.PROGRESS : Step.SKIP;
    }

    @Unique
    private List<FluidStack> rsi$fluidSources(INetwork network) {
        FluidInventory filters = rsi$node().getFluidFilters();
        List<FluidStack> filtered = new ArrayList<>();
        for (int slot = 0; slot < filters.getSlots(); slot++) {
            FluidStack stack = filters.getFluid(slot);
            if (!stack.isEmpty()) filtered.add(stack.copy());
        }
        if (!filtered.isEmpty()) return filtered;
        List<FluidStack> result = new ArrayList<>();
        for (StackListEntry<FluidStack> entry : network.getFluidStorageCache().getList().getStacks()) {
            FluidStack stack = entry.getStack();
            if (!stack.isEmpty()) result.add(stack);
        }
        return result;
    }

    @Unique
    private DiskManipulatorNetworkNode rsi$node() {
        return (DiskManipulatorNetworkNode) (Object) this;
    }

    @Unique
    private void rsi$clearSessions() {
        rsi$itemInsert = null;
        rsi$itemExtract = null;
        rsi$fluidInsert = null;
        rsi$fluidExtract = null;
    }

}
