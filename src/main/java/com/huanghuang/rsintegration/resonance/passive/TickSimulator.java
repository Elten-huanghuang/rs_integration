package com.huanghuang.rsintegration.resonance.passive;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.command.PerformanceMonitor;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;

public final class TickSimulator {

    private static Map<Item, WhitelistEntry> whitelist = Map.of();
    private static int lastConfigHash = -1;
    private static final Map<ResonanceDiskWrapper, MatchedStackCache> MATCH_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private TickSimulator() {}

    public static void simulate(ServerPlayer player, ResonanceDiskWrapper disk) {
        refreshWhitelist();
        if (whitelist.isEmpty()) return;

        // The delegate owns mutable ItemStack instances. Extraction and reinsertion may
        // mutate or reuse them, so each tick must work from an exact, detached snapshot.
        for (MatchedStack matched : matchedStacks(disk)) {
            ItemStack stack = matched.stack();
            WhitelistEntry entry = matched.entry();
            if (entry.mutates) {
                int originalSlot = getSlot(stack);
                ItemStack before = stack.copy();
                ResonanceDiskWrapper.rsi$stripSlotTag(before);
                ItemStack after = before.copy();
                after.getItem().inventoryTick(after, player.level(), player, -1, false);
                if ("apotheosis:potion_charm".equals(entry.itemId) && !after.isEmpty()) {
                    after = PotionCharmMutationPolicy.preserveIdentity(before, after);
                }
                ResonanceDiskWrapper.SlotMutationResult result =
                        disk.reconcileSlot(originalSlot, before, after);
                if (result != ResonanceDiskWrapper.SlotMutationResult.SUCCESS) {
                    com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.warn(
                            "[RSI-Passive] Rejected mutation for {} in slot {}: {}",
                            entry.itemId, originalSlot, result);
                }
            } else {
                ItemStack snapshot = stack.copy();
                snapshot.getItem().inventoryTick(
                        snapshot, player.level(), player, -1, false);
            }
        }
    }

    static List<ItemStack> snapshotStacks(Collection<ItemStack> stacks) {
        return stacks.stream().map(ItemStack::copy).toList();
    }

    private static List<MatchedStack> matchedStacks(ResonanceDiskWrapper disk) {
        long revision = disk.contentRevision();
        MatchedStackCache cached = MATCH_CACHE.get(disk);
        if (cached != null && cached.revision() == revision
                && cached.whitelistHash() == lastConfigHash) {
            return cached.stacks();
        }
        List<MatchedStack> stacks = snapshotMatchedStacks(disk.delegate().getStacks());
        MATCH_CACHE.put(disk, new MatchedStackCache(revision, lastConfigHash, stacks));
        return stacks;
    }

    private static List<MatchedStack> snapshotMatchedStacks(Collection<ItemStack> stacks) {
        List<MatchedStack> matched = new ArrayList<>();
        int scanned = 0;
        for (ItemStack stack : stacks) {
            scanned++;
            if (stack.isEmpty()) continue;
            WhitelistEntry entry = whitelist.get(stack.getItem());
            if (entry != null) matched.add(new MatchedStack(stack.copy(), entry));
        }
        PerformanceMonitor.recordResonanceScan(scanned, matched.size());
        return List.copyOf(matched);
    }

    private static int getSlot(ItemStack stack) {
        net.minecraft.nbt.CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains("RSISlot")) return tag.getInt("RSISlot");
        return -1;
    }

    private static void refreshWhitelist() {
        List<? extends String> configList = RSIntegrationConfig.PASSIVE_TICK_ITEMS.get();
        int hash = configList.hashCode();
        if (hash == lastConfigHash) return;
        lastConfigHash = hash;

        Map<Item, WhitelistEntry> entries = new HashMap<>();
        for (String entry : configList) {
            String[] parts = entry.split("\\|");
            String itemId = parts[0].trim();
            if (itemId.isEmpty()) continue;
            boolean mutates = parts.length > 1 && "mutates".equals(parts[1].trim());
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl != null) {
                Item item = BuiltInRegistries.ITEM.get(rl);
                if (item != null) {
                    WhitelistEntry parsed = new WhitelistEntry(itemId, mutates);
                    entries.put(item, parsed);
                    PassiveRegistry.register(item);
                }
            }
        }
        whitelist = Map.copyOf(entries);
    }

    private record WhitelistEntry(String itemId, boolean mutates) {}
    private record MatchedStack(ItemStack stack, WhitelistEntry entry) {}
    private record MatchedStackCache(long revision, int whitelistHash, List<MatchedStack> stacks) {}
}
