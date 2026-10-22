package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftFluidRecovery;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.storage.StorageNetworkLocation;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
     * is unloaded (which would silently void items). Falls back to storage
     * insertion, then to a physical drop.
     */
    public static void safeGiveToPlayer(ServerPlayer player, ItemStack stack, @Nullable INetwork network) {
        safeGiveToStorage(player, stack,
                network == null ? null : CraftStorageEndpoints.fromLegacyNetwork(network));
    }

    /** 后端无关版本：玩家背包不可用时优先存入所选存储端点。 */
    public static void safeGiveToStorage(ServerPlayer player, ItemStack stack,
                                         @Nullable CraftStorageEndpoint endpoint) {
        if (stack.isEmpty()) return;
        if (InkFluidSupport.isToken(stack)) {
            CraftFluidRecovery.retainToEndpoint(player, stack, endpoint);
            return;
        }
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
        if (endpoint != null) {
            // insertItem returns whatever the network could not store; if we
            // discard it (storage full / no matching storage) those items are voided.
            ItemStack remainder = endpoint.insert(player, stack, false)
                    .remainder().orElse(ItemStack.EMPTY);
            int stored = stack.getCount() - remainder.getCount();
            if (stored > 0) {
                RSIntegrationMod.LOGGER.warn("[RSI] Refund redirected to storage (player chunk unloaded): {} x{}",
                    ItemStackUtils.registryId(stack), stored);
            }
            // Fall through to world-spawn drop for anything the network rejected.
            stack = remainder;
        }
        if (!stack.isEmpty()) {
            ServerLevel dropLevel = endpoint == null ? null
                    : endpoint.location()
                    .flatMap(location -> location.level(player.getServer()))
                    .orElse(null);
            BlockPos dropPos = endpoint == null ? null
                    : endpoint.location().map(StorageNetworkLocation::position).orElse(null);
            if (dropLevel == null || dropPos == null) {
                dropLevel = player.getServer().overworld();
                if (dropLevel == null) return;
                dropPos = dropLevel.getSharedSpawnPos();
            }
            dropLevel.addFreshEntity(new ItemEntity(dropLevel,
                    dropPos.getX() + 0.5, dropPos.getY() + 0.5, dropPos.getZ() + 0.5, stack));
            RSIntegrationMod.LOGGER.warn(
                    "[RSI] Refund dropped at {} (player {} has unloaded chunk): {} x{}",
                    dropPos, player.getGameProfile().getName(),
                    ItemStackUtils.registryId(stack), stack.getCount());
        }
    }

    /** 无修改前快照时同步全部背包槽，扣料和退款也不能只依赖当前菜单的槽位。 */
    public static void broadcastInventoryChanges(ServerPlayer player) {
        broadcastInventoryChanges(player, null);
    }

    private static void broadcastInventoryChanges(ServerPlayer player, @Nullable List<ItemStack> before) {
        if (player.containerMenu != player.inventoryMenu) {
            // 光标包没有菜单 ID；后台背包菜单只能更新普通槽，不能覆盖当前菜单的光标。
            player.inventoryMenu.setRemoteCarried(player.inventoryMenu.getCarried());
        }
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastChanges();
        }
        if (player.connection == null || player.hasDisconnected() || player instanceof FakePlayer) return;

        Set<Integer> slots = new LinkedHashSet<>();
        int size = player.getInventory().getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack current = player.getInventory().getItem(slot);
            if (before != null && slot < before.size()
                    && ItemStack.matches(before.get(slot), current)) continue;
            slots.add(slot);
        }

        // 远程界面可能仍持有旧的手持物品副本，未改变的选中槽也需要校正。
        int selected = player.getInventory().selected;
        if (selected >= 0 && selected < size) slots.add(selected);
        if (before == null) {
            // 全量内容包即使菜单不活跃也会应用到客户端背包，且不会覆盖当前菜单的光标。
            // 无快照的扣料路径只发一个全量包，避免每次批量合成都发送几十个单槽包。
            player.inventoryMenu.sendAllDataToRemote();
        } else {
            for (int slot : slots) {
                // -2 使用背包槽索引，不受当前菜单是否包含玩家槽或菜单 ID 变化影响。
                player.connection.send(new ClientboundContainerSetSlotPacket(
                        ClientboundContainerSetSlotPacket.PLAYER_INVENTORY,
                        0, slot, player.getInventory().getItem(slot).copy()));
            }
        }
        if (!slots.isEmpty()) {
            DEFERRED_INVENTORY_SYNC.computeIfAbsent(player.getUUID(),
                    ignored -> ConcurrentHashMap.newKeySet()).addAll(slots);
        }
    }

    /**
     * Re-send inventory slots after the current server tick's menu broadcast.
     * Remote machine menus can broadcast an older slot snapshot after a craft
     * finishes, so an immediate packet alone is not always the final update the
     * client receives.
     */
    public static void flushDeferredInventorySync(ServerPlayer player) {
        if (player == null) return;
        Set<Integer> slots = DEFERRED_INVENTORY_SYNC.remove(player.getUUID());
        if (slots == null || slots.isEmpty() || player.connection == null
                || player.hasDisconnected() || player instanceof FakePlayer) return;
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
