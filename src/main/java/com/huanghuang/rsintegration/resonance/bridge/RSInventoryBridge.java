package com.huanghuang.rsintegration.resonance.bridge;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.huanghuang.rsintegration.resonance.passive.PassiveEffectEngine;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * Shared utility that merges player inventory with resonance disk contents.
 * Used by Phase 3 mixins to redirect inventory lookups so mod items
 * (Avarice Ring, Nine Sword Book, Pyromancer Staff, etc.) also see
 * items stored in the resonance disk.
 */
public final class RSInventoryBridge {

    private RSInventoryBridge() {}

    private static int rsi$diagCounter;
    private static int rsi$diagFailCounter;

    @Nullable
    public static ResonanceDiskWrapper getResonanceDisk(ServerPlayer player) {
        if (!RSIntegrationConfig.ENABLE_RS_PASSIVE_EFFECTS.get()) {
            if (rsi$diagFailCounter++ < 3)
                RSIntegrationMod.LOGGER.info("[RSI-Bridge] getResonanceDisk: ENABLE_RS_PASSIVE_EFFECTS is false");
            return null;
        }
        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network == null) {
            if (rsi$diagFailCounter++ < 3)
                RSIntegrationMod.LOGGER.info("[RSI-Bridge] getResonanceDisk: resolveNetworkFromPlayer returned null for {} — no RS network item in inventory or no network reachable",
                        player.getName().getString());
            return null;
        }
        ResonanceDiskWrapper disk = PassiveEffectEngine.findResonanceDisk(network);
        if (disk != null) {
            if (rsi$diagCounter++ < 3)
                RSIntegrationMod.LOGGER.info("[RSI-Bridge] getResonanceDisk: found resonance disk (stored={}, capacity={}) for {}",
                        disk.getStored(), disk.getCapacity(), player.getName().getString());
        } else {
            if (rsi$diagFailCounter++ < 3)
                RSIntegrationMod.LOGGER.info("[RSI-Bridge] getResonanceDisk: network resolved but no resonance disk found in storage cache for {} — is a resonance disk inserted and connected?",
                        player.getName().getString());
        }
        return disk;
    }

    public static boolean hasItem(ServerPlayer player, Predicate<ItemStack> predicate) {
        for (ItemStack stack : player.getInventory().items) {
            if (predicate.test(stack)) return true;
        }
        ResonanceDiskWrapper disk = getResonanceDisk(player);
        if (disk != null) {
            for (ItemStack stack : disk.getStacks()) {
                if (!stack.isEmpty() && predicate.test(stack)) return true;
            }
        }
        return false;
    }

    public static int countItems(ServerPlayer player, Predicate<ItemStack> predicate) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (predicate.test(stack)) count += stack.getCount();
        }
        ResonanceDiskWrapper disk = getResonanceDisk(player);
        if (disk != null) {
            for (ItemStack stack : disk.getStacks()) {
                if (!stack.isEmpty() && predicate.test(stack)) count += stack.getCount();
            }
        }
        return count;
    }

    @Nullable
    public static ItemStack findFirst(ServerPlayer player, Predicate<ItemStack> predicate) {
        for (ItemStack stack : player.getInventory().items) {
            if (predicate.test(stack)) return stack.copy();
        }
        ResonanceDiskWrapper disk = getResonanceDisk(player);
        if (disk != null) {
            for (ItemStack stack : disk.getStacks()) {
                if (!stack.isEmpty() && predicate.test(stack)) return stack.copy();
            }
        }
        return null;
    }

    /** Extract one or more matching items from the resonance disk. */
    public static ItemStack extractFromDisk(ServerPlayer player, Predicate<ItemStack> predicate,
                                            int amount, int flags) {
        ResonanceDiskWrapper disk = getResonanceDisk(player);
        if (disk == null) return ItemStack.EMPTY;
        for (ItemStack tagged : disk.delegate().getStacks()) {
            if (tagged.isEmpty()) continue;
            if (!predicate.test(tagged)) continue;
            ItemStack result = disk.delegate().extract(tagged, amount, flags, Action.PERFORM);
            if (!result.isEmpty()) {
                ResonanceDiskWrapper.rsi$stripSlotTag(result);
                return result;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Insert into the resonance disk without slot-tagging (no backpack slot). */
    public static ItemStack insertToDisk(ServerPlayer player, ItemStack stack) {
        ResonanceDiskWrapper disk = getResonanceDisk(player);
        if (disk == null) return stack;
        return disk.delegate().insert(stack.copy(), stack.getCount(), Action.PERFORM);
    }

    /** Get all items from player inventory + resonance disk as a merged list. */
    public static List<ItemStack> getCombinedItems(ServerPlayer player) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty()) result.add(stack);
        }
        ResonanceDiskWrapper disk = getResonanceDisk(player);
        if (disk != null) {
            for (ItemStack stack : disk.getStacks()) {
                if (!stack.isEmpty()) result.add(stack);
            }
        }
        return result;
    }

    /** Get all items from the resonance disk only. */
    public static Collection<ItemStack> getDiskItems(ServerPlayer player) {
        ResonanceDiskWrapper disk = getResonanceDisk(player);
        if (disk == null) return List.of();
        return disk.getStacks();
    }
}
