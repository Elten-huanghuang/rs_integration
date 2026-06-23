package com.huanghuang.rsintegration.util;

import com.mojang.authlib.GameProfile;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.tracker.IStorageTracker;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import com.refinedmods.refinedstorage.apiimpl.API;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.items.IItemHandler;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.AccessLogRecord;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.FilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidUpgradeWrapper;

import java.util.*;
import java.util.stream.Collectors;

public final class RSUtils {

    private RSUtils() {}

    public static Boolean handleRsInsertion(ContentsFilterLogic filter, ItemEntity itemEntity,
                                             UUID backpackUuid, BlockPos rsPos, ResourceKey<Level> dim,
                                             List<VoidUpgradeWrapper> voidUpgrades, boolean voidUpgrade) {
        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) return false;
        if (!filter.matchesFilter(stack)) return false;

        Level level = itemEntity.level();
        INetwork network = getNetwork(level, rsPos, dim);
        if (network == null) return false;

        if (voidUpgrade && !voidUpgrades.isEmpty()) {
            for (VoidUpgradeWrapper voidWrapper : voidUpgrades) {
                if (voidWrapper.isEnabled()) {
                    FilterLogic voidFilter = voidWrapper.getFilterLogic();
                    if (voidFilter.matchesFilter(stack)) {
                        itemEntity.discard();
                        return true;
                    }
                }
            }
        }

        ItemStack remaining = network.insertItem(stack.copy(), stack.getCount(), Action.PERFORM);
        IStorageTracker tracker = network.getItemStorageTracker();
        if (tracker != null) {
            Player fakePlayer = createFakePlayer(level, backpackUuid);
            tracker.changed(fakePlayer, remaining.isEmpty() ? stack.copy() : remaining.copy());
        }

        if (remaining.isEmpty()) {
            itemEntity.discard();
            return true;
        }
        itemEntity.setItem(remaining);
        return false;
    }

    public static ItemStack handleRSPickup(ContentsFilterLogic filter, Level world, ItemStack stack,
                                            boolean simulate, UUID backpackUuid,
                                            BlockPos rsPos, ResourceKey<Level> dim,
                                            List<VoidUpgradeWrapper> voidUpgrades, boolean voidUpgrade) {
        if (stack.isEmpty()) return stack;
        if (!filter.matchesFilter(stack)) return stack;

        INetwork network = getNetwork(world, rsPos, dim);
        if (network == null) return stack;

        if (voidUpgrade && !voidUpgrades.isEmpty()) {
            for (VoidUpgradeWrapper voidWrapper : voidUpgrades) {
                if (voidWrapper.isEnabled()) {
                    FilterLogic voidFilter = voidWrapper.getFilterLogic();
                    if (voidFilter.matchesFilter(stack)) {
                        return ItemStack.EMPTY;
                    }
                }
            }
        }

        Action action = simulate ? Action.SIMULATE : Action.PERFORM;
        ItemStack remaining = network.insertItem(stack.copy(), stack.getCount(), action);

        if (!simulate) {
            IStorageTracker tracker = network.getItemStorageTracker();
            if (tracker != null) {
                Player fakePlayer = createFakePlayer(world, backpackUuid);
                tracker.changed(fakePlayer, remaining.isEmpty() ? stack.copy() : remaining.copy());
            }
        }

        return remaining;
    }

    public static List<ItemStack> handleRSRestock(ContentsFilterLogic filter, IStorageWrapper storageWrapper,
                                                   BlockPos rsPos, ResourceKey<Level> dim, Level level) {
        List<ItemStack> restocked = new ArrayList<>();
        INetwork network = getNetwork(level, rsPos, dim);
        if (network == null) return restocked;

        IItemHandler backpackInv = storageWrapper.getInventoryForUpgradeProcessing();
        int flags = IComparer.COMPARE_NBT;

        IStorageCache<ItemStack> cache = network.getItemStorageCache();
        if (cache == null) return restocked;
        Collection<StackListEntry<ItemStack>> entries = cache.getList().getStacks();
        for (StackListEntry<ItemStack> entry : entries) {
            ItemStack storedStack = entry.getStack();
            if (storedStack.isEmpty()) continue;
            if (!filter.matchesFilter(storedStack)) continue;

            int toExtract = Math.min(storedStack.getMaxStackSize(), 64);
            ItemStack extracted = network.extractItem(storedStack.copy(), toExtract, flags, Action.PERFORM);
            if (extracted.isEmpty()) continue;

            ItemStack remaining = extracted.copy();
            for (int slot = 0; slot < backpackInv.getSlots() && !remaining.isEmpty(); slot++) {
                remaining = backpackInv.insertItem(slot, remaining, false);
            }
            int inserted = extracted.getCount() - remaining.getCount();
            if (inserted > 0) {
                ItemStack result = extracted.copy();
                result.setCount(inserted);
                restocked.add(result);
            }
            if (!remaining.isEmpty()) {
                network.insertItem(remaining, remaining.getCount(), Action.PERFORM);
            }
        }
        return restocked;
    }

    public static INetwork getNetwork(Level level, BlockPos pos, ResourceKey<Level> dim) {
        var server = level.getServer();
        if (server == null) return null;
        ServerLevel serverLevel = server.getLevel(dim);
        if (serverLevel == null) return null;
        return API.instance().getNetworkManager(serverLevel).getNetwork(pos);
    }

    private static Player createFakePlayer(Level level, UUID backpackUuid) {
        String playerName = "Refined Storage";
        if (backpackUuid != null) {
            Map<UUID, AccessLogRecord> logs = BackpackStorage.get().getAccessLogs();
            AccessLogRecord record = logs.get(backpackUuid);
            if (record != null) {
                String name = record.getPlayerName();
                if (name != null && !name.isEmpty()) {
                    playerName = name;
                }
            }
        }
        return new FakePlayer((ServerLevel) level, new GameProfile(UUID.randomUUID(), playerName));
    }
}
