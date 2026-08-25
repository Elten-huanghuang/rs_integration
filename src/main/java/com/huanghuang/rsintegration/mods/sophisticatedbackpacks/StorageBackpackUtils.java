package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageResolutionResult;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.util.ExternalItemProgressSuppression;
import com.huanghuang.rsintegration.util.RsOperationPlayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.items.IItemHandler;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.FilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidUpgradeWrapper;

import java.util.List;

/** Sophisticated Backpacks storage operations through the backend-neutral session. */
public final class StorageBackpackUtils {
    private static final StorageBackendId RS = new StorageBackendId("refinedstorage");
    public static final String BACKEND_TAG = "RSIStorageBackend";
    public static final String NETWORK_TAG = "RSIStorageNetwork";

    private StorageBackpackUtils() {}

    /** Reads the current backend-qualified binding, including old RS tags. */
    public static StorageReference readReference(net.minecraft.nbt.CompoundTag tag) {
        if (tag == null) return null;
        if (tag.contains(BACKEND_TAG, net.minecraft.nbt.Tag.TAG_STRING)
                && tag.contains(NETWORK_TAG, net.minecraft.nbt.Tag.TAG_STRING)) {
            String backend = tag.getString(BACKEND_TAG);
            String network = tag.getString(NETWORK_TAG);
            if (!backend.isBlank() && !network.isBlank()) {
                try { return new StorageReference(new StorageBackendId(backend), network); }
                catch (IllegalArgumentException ignored) { return null; }
            }
        }
        if (tag.contains("RSBlockPos", net.minecraft.nbt.Tag.TAG_LONG)
                && tag.contains("RSBlockDimension", net.minecraft.nbt.Tag.TAG_STRING)) {
            ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("RSBlockDimension"));
            if (dimension != null) {
                BlockPos pos = BlockPos.of(tag.getLong("RSBlockPos"));
                return new StorageReference(RS, "v1|" + dimension + "@"
                        + pos.getX() + "," + pos.getY() + "," + pos.getZ());
            }
        }
        return null;
    }

    public static void writeReference(net.minecraft.nbt.CompoundTag tag, StorageReference reference) {
        tag.putString(BACKEND_TAG, reference.backendId().value());
        tag.putString(NETWORK_TAG, reference.networkId());
    }

    public static boolean insertItem(ContentsFilterLogic filter, ItemEntity entity,
                              BlockPos position, ResourceKey<Level> dimension,
                              List<VoidUpgradeWrapper> voidUpgrades, boolean voidUpgrade) {
        return insertItem(filter, entity, legacyReference(position, dimension), voidUpgrades, voidUpgrade);
    }

    public static boolean insertItem(ContentsFilterLogic filter, ItemEntity entity,
                                     StorageReference reference,
                                     List<VoidUpgradeWrapper> voidUpgrades, boolean voidUpgrade) {
        ItemStack stack = entity.getItem();
        if (stack.isEmpty() || !filter.matchesFilter(stack)) return false;
        if (voidUpgrade && matchesVoidFilter(stack, voidUpgrades)) {
            ExternalItemProgressSuppression.suppress();
            entity.discard();
            return true;
        }
        ServerPlayer player = RsOperationPlayerContext.current();
        StorageSession session = resolve(player, reference);
        if (session == null) return false;
        StorageOperationResult result = session.insert(player, stack.copy(), false);
        if (!result.remainder().isPresent()) return false;
        ItemStack remainder = result.remainder().orElseThrow();
        entity.setItem(remainder);
        if (result.status() == StorageOperationStatus.SUCCESS) {
            entity.discard();
            return true;
        }
        return false;
    }

    public static ItemStack pickupItem(ContentsFilterLogic filter, Level level, ItemStack stack,
                                boolean simulate, BlockPos position, ResourceKey<Level> dimension,
                                List<VoidUpgradeWrapper> voidUpgrades, boolean voidUpgrade) {
        return pickupItem(filter, level, stack, simulate, legacyReference(position, dimension),
                voidUpgrades, voidUpgrade);
    }

    public static ItemStack pickupItem(ContentsFilterLogic filter, Level level, ItemStack stack,
                                       boolean simulate, StorageReference reference,
                                       List<VoidUpgradeWrapper> voidUpgrades, boolean voidUpgrade) {
        if (stack.isEmpty() || !filter.matchesFilter(stack)) return stack;
        if (voidUpgrade && matchesVoidFilter(stack, voidUpgrades)) {
            ExternalItemProgressSuppression.suppress();
            return ItemStack.EMPTY;
        }
        ServerPlayer player = RsOperationPlayerContext.current();
        StorageSession session = resolve(player, reference);
        if (session == null) return stack;
        StorageOperationResult result = session.insert(player, stack.copy(), simulate);
        if (!result.remainder().isPresent()) return stack;
        return result.remainder().orElseThrow();
    }

    private static boolean matchesVoidFilter(ItemStack stack, List<VoidUpgradeWrapper> upgrades) {
        for (VoidUpgradeWrapper upgrade : upgrades) {
            if (upgrade.isEnabled() && upgrade.getFilterLogic().matchesFilter(stack)) return true;
        }
        return false;
    }

    public static StorageSession resolve(ServerPlayer player, BlockPos position,
                                          ResourceKey<Level> dimension) {
        if (player == null || player.server == null) return null;
        StorageReference reference = legacyReference(position, dimension);
        StorageResolutionResult result = RSIntegrationMod.STORAGE_BACKENDS.registry()
                .resolve(reference, player);
        return result.resolved() ? result.session().orElse(null) : null;
    }

    private static StorageReference legacyReference(BlockPos position, ResourceKey<Level> dimension) {
        String networkId = "v1|" + dimension.location() + "@"
                + position.getX() + "," + position.getY() + "," + position.getZ();
        return new StorageReference(RS, networkId);
    }

    public static StorageSession resolve(ServerPlayer player, StorageReference reference) {
        if (player == null || reference == null || player.server == null) return null;
        StorageResolutionResult result = RSIntegrationMod.STORAGE_BACKENDS.registry()
                .resolve(reference, player);
        return result.resolved() ? result.session().orElse(null) : null;
    }

    /**
     * Restocks matching stacks into a backpack using the selected storage
     * backend. The snapshot is only a candidate list; every extraction and
     * return uses the same authorized live session.
     */
    public static List<ItemStack> handleRestock(ContentsFilterLogic filter,
                                                  IStorageWrapper storageWrapper,
                                                  ServerPlayer player,
                                                  StorageReference reference) {
        List<ItemStack> restocked = new java.util.ArrayList<>();
        if (filter == null || storageWrapper == null || player == null || reference == null) {
            return restocked;
        }
        StorageSession session = resolve(player, reference);
        if (session == null || !session.hasPermission(player,
                com.huanghuang.rsintegration.storage.StoragePermission.EXTRACT)) return restocked;
        StorageSnapshotResult snapshot = session.snapshotItems(player);
        if (!snapshot.successful()) return restocked;
        IItemHandler backpackInv = storageWrapper.getInventoryForUpgradeProcessing();
        for (StoredItem stored : snapshot.snapshot().orElseThrow().items()) {
            ItemStack candidate = stored.stack();
            if (candidate.isEmpty() || !filter.matchesFilter(candidate)) continue;
            int amount = (int) Math.min(Math.min(stored.amount(), candidate.getMaxStackSize()), 64L);
            if (amount <= 0) continue;
            StorageOperationResult extraction = session.extractExact(
                    player, session.itemKey(candidate), amount, false);
            if (extraction.extractedStacks().isEmpty()) continue;
            ItemStack extracted = merge(extraction.extractedStacks());
            ItemStack remaining = extracted.copy();
            try {
                for (int slot = 0; slot < backpackInv.getSlots() && !remaining.isEmpty(); slot++) {
                    remaining = backpackInv.insertItem(slot, remaining, false);
                }
            } catch (RuntimeException ex) {
                returnAfterLocalFailure(session, player, remaining);
                continue;
            }
            int inserted = extracted.getCount() - remaining.getCount();
            if (inserted > 0) restocked.add(extracted.copyWithCount(inserted));
            if (!remaining.isEmpty()) {
                StorageOperationResult returned = session.insert(player, remaining, false);
                if (returned.remainder().isPresent() && !returned.remainder().orElseThrow().isEmpty()) {
                    player.getInventory().placeItemBackInInventory(returned.remainder().orElseThrow());
                }
            }
        }
        return restocked;
    }

    private static ItemStack merge(List<ItemStack> stacks) {
        ItemStack merged = ItemStack.EMPTY;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            if (merged.isEmpty()) merged = stack.copy();
            else if (ItemStack.isSameItemSameTags(merged, stack)) merged.grow(stack.getCount());
        }
        return merged;
    }

    public static StorageOperationResult extractExact(StorageSession session, ServerPlayer player,
                                                       ItemStack template, int amount, boolean simulate) {
        if (session == null || player == null || template == null || template.isEmpty() || amount <= 0) {
            return null;
        }
        return session.extractExact(player, session.itemKey(template), amount, simulate);
    }

    public static StorageOperationResult extractMatching(StorageSession session, ServerPlayer player,
                                                          Ingredient ingredient, int amount, boolean simulate) {
        if (session == null || player == null || ingredient == null || amount <= 0) return null;
        return session.extractMatching(player, ingredient, amount, simulate);
    }

    /**
     * Return a stack extracted for a local backpack operation. If the backend
     * reports a known remainder, only that remainder is handed to the player;
     * an indeterminate insert is deliberately not duplicated.
     */
    public static void returnAfterLocalFailure(StorageSession session, ServerPlayer player,
                                               ItemStack stack) {
        if (session == null || player == null || stack == null || stack.isEmpty()) return;
        try {
            StorageOperationResult returned = session.insert(player, stack.copy(), false);
            if (returned.remainder().isPresent()) {
                ItemStack remainder = returned.remainder().orElse(ItemStack.EMPTY);
                if (!remainder.isEmpty()) {
                    player.getInventory().placeItemBackInInventory(remainder);
                }
            }
        } catch (RuntimeException ignored) {
            // The local operation failed before the backend could provide a
            // reliable settlement result; preserve the extracted item locally.
            player.getInventory().placeItemBackInInventory(stack.copy());
        }
    }
}
