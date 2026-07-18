package com.huanghuang.rsintegration.mods.distantworlds;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class LithumAltarFuelHelper {
    public static final int FUEL_SLOT = 0;
    private static final TagKey<Item> FUEL_TAG = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("distant_worlds", "lithum_furnace_fuel"));

    private BlockPos furnacePos;
    private ItemStack baseline = ItemStack.EMPTY;
    private ItemStack fuelType = ItemStack.EMPTY;
    private int insertedCount;

    public boolean findAndLock(net.minecraft.server.level.ServerLevel level, BlockPos corePos) {
        BlockEntity existing = furnacePos == null ? null : level.getBlockEntity(furnacePos);
        if (LithumAltarStructureHelper.isFurnace(existing)) return true;
        furnacePos = null;
        int radius = RSIntegrationConfig.DISTANT_WORLDS_FUEL_SEARCH_RADIUS.get();
        List<BlockPos> candidates = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos candidate = corePos.offset(x, y, z);
                    if (LithumAltarStructureHelper.isFurnace(level.getBlockEntity(candidate))) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble((BlockPos p) -> p.distSqr(corePos))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ));
        for (BlockPos candidate : candidates) {
            BlockEntity be = level.getBlockEntity(candidate);
            IItemHandler handler = handler(be);
            if (handler == null || handler.getSlots() <= FUEL_SLOT) continue;
            ItemStack current = handler.getStackInSlot(FUEL_SLOT);
            if (!current.isEmpty() && !current.is(FUEL_TAG)) continue;
            furnacePos = candidate;
            baseline = current.copy();
            return true;
        }
        return false;
    }

    public boolean ensureFuel(net.minecraft.server.level.ServerLevel level, INetwork network) {
        if (furnacePos == null || network == null) return false;
        BlockEntity furnace = level.getBlockEntity(furnacePos);
        if (!LithumAltarStructureHelper.isFurnace(furnace)) return false;
        IItemHandler handler = handler(furnace);
        if (handler == null || handler.getSlots() <= FUEL_SLOT) return false;
        ItemStack current = handler.getStackInSlot(FUEL_SLOT);
        if (!current.isEmpty() && current.is(FUEL_TAG)) return true;

        ItemStack candidate = selectFuel(network);
        if (candidate.isEmpty()) return false;
        int requested = Math.min(RSIntegrationConfig.DISTANT_WORLDS_FUEL_BATCH_SIZE.get(),
                LithumFuelInventoryLogic.insertionRoom(current, candidate, handler.getSlotLimit(FUEL_SLOT)));
        if (requested <= 0) return false;
        ItemStack simulated = handler.insertItem(FUEL_SLOT, candidate.copyWithCount(requested), true);
        int accepted = requested - simulated.getCount();
        if (accepted <= 0) return false;
        ItemStack simulatedExtract = network.extractItem(candidate.copyWithCount(1), accepted, Action.SIMULATE);
        if (simulatedExtract.getCount() != accepted) return false;
        ItemStack extracted = network.extractItem(candidate.copyWithCount(1), accepted, Action.PERFORM);
        if (extracted.getCount() != accepted) {
            refund(network, extracted);
            return false;
        }
        ItemStack remainder = handler.insertItem(FUEL_SLOT, extracted, false);
        int inserted = extracted.getCount() - remainder.getCount();
        refund(network, remainder);
        if (inserted <= 0) return false;
        fuelType = candidate.copyWithCount(1);
        insertedCount += inserted;
        furnace.setChanged();
        level.sendBlockUpdated(furnacePos, furnace.getBlockState(), furnace.getBlockState(), 3);
        return true;
    }

    public void refundUnused(net.minecraft.server.level.ServerLevel level, INetwork network,
                             net.minecraft.server.level.ServerPlayer player) {
        if (furnacePos == null || insertedCount <= 0) return;
        BlockEntity furnace = level.getBlockEntity(furnacePos);
        IItemHandler handler = handler(furnace);
        if (handler == null) return;
        ItemStack current = handler.getStackInSlot(FUEL_SLOT);
        int count = LithumFuelInventoryLogic.refundableAddedCount(baseline, insertedCount, current);
        if (count <= 0) return;
        ItemStack extracted = handler.extractItem(FUEL_SLOT, count, false);
        ItemStack remainder = refund(network, extracted);
        if (!remainder.isEmpty() && player != null) {
            net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, remainder);
        } else if (!remainder.isEmpty()) {
            net.minecraft.world.Containers.dropItemStack(level, furnacePos.getX() + 0.5,
                    furnacePos.getY() + 1, furnacePos.getZ() + 0.5, remainder);
        }
        insertedCount = Math.max(0, insertedCount - extracted.getCount());
    }

    public BlockPos furnacePos() { return furnacePos; }
    public ItemStack fuelType() { return fuelType.copy(); }

    private static IItemHandler handler(BlockEntity be) {
        return be == null ? null : be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
    }

    private static ItemStack refund(INetwork network, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        return network.insertItem(stack, stack.getCount(), Action.PERFORM);
    }

    private static ItemStack selectFuel(INetwork network) {
        List<? extends String> priority = RSIntegrationConfig.DISTANT_WORLDS_FUEL_PRIORITY.get();
        List<ItemStack> candidates = new ArrayList<>();
        for (var entry : network.getItemStorageCache().getList().getStacks()) {
            ItemStack stack = entry.getStack();
            if (!stack.isEmpty() && stack.is(FUEL_TAG)) candidates.add(stack.copyWithCount(1));
        }
        candidates.sort(Comparator.comparingInt((ItemStack stack) -> {
            ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            int index = key == null ? -1 : priority.indexOf(key.toString());
            return index < 0 ? Integer.MAX_VALUE : index;
        }).thenComparing((ItemStack stack) -> {
            ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            return key == null ? "" : key.toString();
        }));
        return candidates.isEmpty() ? ItemStack.EMPTY : candidates.get(0);
    }
}
