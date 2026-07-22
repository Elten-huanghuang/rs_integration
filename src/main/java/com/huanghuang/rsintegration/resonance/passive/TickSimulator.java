package com.huanghuang.rsintegration.resonance.passive;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;

public final class TickSimulator {

    private static List<WhitelistEntry> whitelist = List.of();
    private static int lastConfigHash = -1;

    private TickSimulator() {}

    public static void simulate(ServerPlayer player, ResonanceDiskWrapper disk) {
        refreshWhitelist();
        if (whitelist.isEmpty()) return;

        // The delegate owns mutable ItemStack instances. Extraction and reinsertion may
        // mutate or reuse them, so each tick must work from an exact, detached snapshot.
        for (ItemStack stack : snapshotStacks(disk.delegate().getStacks())) {
            if (stack.isEmpty()) continue;
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key == null) continue;

            WhitelistEntry entry = findEntry(key.toString());
            if (entry == null) continue;
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

        List<WhitelistEntry> list = new ArrayList<>();
        for (String entry : configList) {
            String[] parts = entry.split("\\|");
            String itemId = parts[0].trim();
            if (itemId.isEmpty()) continue;
            boolean mutates = parts.length > 1 && "mutates".equals(parts[1].trim());
            list.add(new WhitelistEntry(itemId, mutates));
        }
        whitelist = List.copyOf(list);

        for (WhitelistEntry e : whitelist) {
            ResourceLocation rl = ResourceLocation.tryParse(e.itemId);
            if (rl != null) {
                Item item = BuiltInRegistries.ITEM.get(rl);
                if (item != null) PassiveRegistry.register(item);
            }
        }
    }

    @javax.annotation.Nullable
    private static WhitelistEntry findEntry(String itemId) {
        for (WhitelistEntry e : whitelist) {
            if (e.itemId.equals(itemId)) return e;
        }
        return null;
    }

    private record WhitelistEntry(String itemId, boolean mutates) {}
}
