package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerUtils {

    private static final Map<UUID, Set<Integer>> DEFERRED_INVENTORY_SYNC =
            new ConcurrentHashMap<>();

    private PlayerUtils() {}

    @Nullable
    public static ServerPlayer getOnlinePlayer(MinecraftServer server, UUID playerId) {
        if (server == null || server.getPlayerList() == null) return null;
        return server.getPlayerList().getPlayer(playerId);
    }

    public static boolean isPlayerOnline(ServerPlayer player) {
        return player != null && !player.hasDisconnected();
    }

    /**
     * Insert into the main inventory without ever presenting it with an
     * over-sized stack. Some modded inventory implementations place an input
     * stack into an empty slot verbatim instead of enforcing its item limit.
     */
    public static ItemStack insertIntoPlayerInventory(ServerPlayer player, ItemStack stack) {
        List<ItemStack> before = snapshotInventory(player);
        ItemStack remainder = insertMaxSizedChunks(stack, player.getInventory()::add);
        player.getInventory().setChanged();
        MaterialSources.invalidateFor(player);
        broadcastInventoryChanges(player, before);
        return remainder;
    }

    static ItemStack insertMaxSizedChunks(ItemStack stack, Consumer<ItemStack> insertion) {
        if (stack.isEmpty()) return ItemStack.EMPTY;

        ItemStack pending = stack.copy();
        int remainderCount = 0;
        int maxStackSize = Math.max(1, pending.getMaxStackSize());
        while (!pending.isEmpty()) {
            ItemStack chunk = pending.split(Math.min(maxStackSize, pending.getCount()));
            insertion.accept(chunk);
            remainderCount += chunk.getCount();
        }
        return remainderCount == 0 ? ItemStack.EMPTY : stack.copyWithCount(remainderCount);
    }

    /** Send a system message to the player, guarding against disconnected state. */
    public static void safeSendMessage(ServerPlayer player, Component message) {
        if (player != null && !player.hasDisconnected()) {
            player.sendSystemMessage(message);
        }
    }

    /** Convenience: resolve player by UUID then send. No-op if offline. */
    public static void safeSendMessage(MinecraftServer server, UUID playerId, Component message) {
        ServerPlayer player = getOnlinePlayer(server, playerId);
        if (player != null && !player.hasDisconnected()) {
            player.sendSystemMessage(message);
        }
    }

    /**
     * Safely gives an item to a player, with fallbacks when the player's chunk
     * is unloaded (which would silently void items). Falls back to RS network
     * insertion, then to world-spawn drop.
     */
    public static void safeGiveToPlayer(ServerPlayer player, ItemStack stack, @Nullable INetwork network) {
        if (stack.isEmpty()) return;
        if (player.level().hasChunkAt(player.blockPosition())) {
            List<ItemStack> before = snapshotInventory(player);
            // Split large stacks into max-size chunks to avoid spawning an
            // excessive number of item entities when the player's inventory
            // is full (e.g. batch crafting 1000 planks → 16 entities, not 1000).
            while (!stack.isEmpty()) {
                int split = Math.min(stack.getMaxStackSize(), stack.getCount());
                ItemStack chunk = stack.split(split);
                ItemHandlerHelper.giveItemToPlayer(player, chunk);
            }
            player.getInventory().setChanged();
            MaterialSources.invalidateFor(player);
            // ItemHandlerHelper updates the inventory, but an already-open custom
            // container is not guaranteed to observe that mutation until its next
            // scheduled sync. Broadcast now so chained crafts can use the result
            // immediately and the client does not render a stale stack.
            broadcastInventoryChanges(player, before);
            return;
        }
        if (network != null) {
            // insertItem returns whatever the network could not store; if we
            // discard it (RS full / no matching storage) those items are voided.
            ItemStack remainder = CraftStorageEndpoints.insertLegacy(network, player, stack, false);
            int stored = stack.getCount() - remainder.getCount();
            if (stored > 0) {
                RSIntegrationMod.LOGGER.warn("[RSI] Refund redirected to RS network (player chunk unloaded): {} x{}",
                    ItemStackUtils.registryId(stack), stored);
            }
            // Fall through to world-spawn drop for anything the network rejected.
            stack = remainder;
        }
        if (!stack.isEmpty()) {
            var spawnLevel = player.getServer().overworld();
            if (spawnLevel == null) return;
            var spawnPos = spawnLevel.getSharedSpawnPos();
            spawnLevel.addFreshEntity(
                new ItemEntity(spawnLevel,
                    spawnPos.getX() + 0.5, spawnPos.getY() + 0.5, spawnPos.getZ() + 0.5, stack));
            RSIntegrationMod.LOGGER.warn("[RSI] Refund dropped at world spawn (player {} in unloaded chunk): {} x{}",
                player.getGameProfile().getName(), ItemStackUtils.registryId(stack), stack.getCount());
        }
    }

    /**
     * Inventory mutations can happen while a custom container is open. Both
     * menus maintain their own last-sent slot snapshots, so updating only the
     * active menu leaves the player inventory client-side with ghost stacks
     * until the screen is reopened.
     */
    public static void broadcastInventoryChanges(ServerPlayer player) {
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastChanges();
        }
    }

    private static void broadcastInventoryChanges(ServerPlayer player, List<ItemStack> before) {
        broadcastInventoryChanges(player);
        if (player.connection == null || player instanceof FakePlayer) return;

        int size = Math.min(before.size(), player.getInventory().getContainerSize());
        for (int slot = 0; slot < size; slot++) {
            ItemStack current = player.getInventory().getItem(slot);
            if (ItemStack.matches(before.get(slot), current)) continue;

            // PLAYER_INVENTORY bypasses the currently open custom menu. This is
            // necessary for virtual crafts whose output arrives while a screen
            // owns a different container id; a normal menu diff can otherwise
            // leave the client rendering a ghost stack until that screen reopens.
            player.connection.send(new ClientboundContainerSetSlotPacket(
                    ClientboundContainerSetSlotPacket.PLAYER_INVENTORY,
                    0, slot, current.copy()));
            DEFERRED_INVENTORY_SYNC.computeIfAbsent(player.getUUID(), ignored ->
                    ConcurrentHashMap.newKeySet()).add(slot);
        }

        // A virtual craft can finish while the client is using an item from
        // the selected hotbar slot to keep a remote GUI open. That GUI may
        // leave the client holding a stale copy even when the server selected
        // slot itself did not change, so always correct the active hand slot.
        int selected = player.getInventory().selected;
        if (selected >= 0 && selected < player.getInventory().getContainerSize()) {
            player.connection.send(new ClientboundContainerSetSlotPacket(
                    ClientboundContainerSetSlotPacket.PLAYER_INVENTORY,
                    0, selected, player.getInventory().getItem(selected).copy()));
            DEFERRED_INVENTORY_SYNC.computeIfAbsent(player.getUUID(), ignored ->
                    ConcurrentHashMap.newKeySet()).add(selected);
        }
    }

    /**
     * Re-send inventory slots after the current server tick's menu broadcast.
     * Remote machine menus can broadcast an older slot snapshot after a craft
     * finishes, so an immediate packet alone is not always the final update the
     * client receives.
     */
    public static void flushDeferredInventorySync(ServerPlayer player) {
        if (player == null || player.connection == null || player instanceof FakePlayer) return;
        Set<Integer> slots = DEFERRED_INVENTORY_SYNC.remove(player.getUUID());
        if (slots == null || slots.isEmpty()) return;
        for (int slot : slots) {
            if (slot < 0 || slot >= player.getInventory().getContainerSize()) continue;
            player.connection.send(new ClientboundContainerSetSlotPacket(
                    ClientboundContainerSetSlotPacket.PLAYER_INVENTORY,
                    0, slot, player.getInventory().getItem(slot).copy()));
        }
    }

    private static List<ItemStack> snapshotInventory(ServerPlayer player) {
        int size = player.getInventory().getContainerSize();
        List<ItemStack> snapshot = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            snapshot.add(player.getInventory().getItem(slot).copy());
        }
        return snapshot;
    }
}
