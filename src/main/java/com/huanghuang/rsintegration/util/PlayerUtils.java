package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftFluidRecovery;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
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

    /** 先尝试玩家背包，只把真实剩余量写入存储，存储也无法接收时才掉落。 */
    public static void safeGiveToPlayer(ServerPlayer player, ItemStack stack, @Nullable INetwork network) {
        safeGiveToStorage(player, stack,
                network == null ? null : CraftStorageEndpoints.fromLegacyNetwork(network));
    }

    /**
     * 后端无关的安全交付：玩家背包优先，背包满或区块未加载时写入所选存储端点。
     * 存储仍无法接收的剩余物才进入世界掉落，绝不把“背包满”直接当成最终结果。
     */
    public static void safeGiveToStorage(ServerPlayer player, ItemStack stack,
                                         @Nullable CraftStorageEndpoint endpoint) {
        if (stack.isEmpty()) return;
        if (InkFluidSupport.isToken(stack)) {
            CraftFluidRecovery.retainToEndpoint(player, stack, endpoint);
            return;
        }

        ItemStack leftover = stack.copy();
        boolean playerChunkLoaded = player.level().hasChunkAt(player.blockPosition());
        if (playerChunkLoaded) {
            leftover = insertIntoPlayerInventory(player, leftover);
        }

        if (!leftover.isEmpty() && endpoint != null) {
            StorageOperationResult result = endpoint.insert(player, leftover, false);
            if (result.remainder().isEmpty()) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI] Storage outcome is unknown; not retrying to avoid duplication: {} x{}",
                        ItemStackUtils.registryId(leftover), leftover.getCount());
                return;
            }
            ItemStack before = leftover.copy();
            leftover = result.remainder().orElseThrow();
            int stored = before.getCount() - leftover.getCount();
            if (stored > 0) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI] Player inventory could not accept item; redirected {} x{} to storage",
                        ItemStackUtils.registryId(before), stored);
            }
        }

        if (!leftover.isEmpty()) {
            ServerLevel dropLevel = playerChunkLoaded ? player.serverLevel() : null;
            BlockPos dropPos = playerChunkLoaded ? player.blockPosition() : null;
            if (!playerChunkLoaded && endpoint != null) {
                dropLevel = endpoint.location()
                        .flatMap(location -> location.level(player.getServer()))
                        .orElse(null);
                dropPos = endpoint.location()
                        .map(StorageNetworkLocation::position)
                        .orElse(null);
            }
            if (dropLevel == null || dropPos == null) {
                dropLevel = player.getServer().overworld();
                if (dropLevel == null) return;
                dropPos = dropLevel.getSharedSpawnPos();
            }
            dropLevel.addFreshEntity(new ItemEntity(dropLevel,
                    dropPos.getX() + 0.5, dropPos.getY() + 0.5, dropPos.getZ() + 0.5, leftover));
            RSIntegrationMod.LOGGER.warn(
                    "[RSI] Item dropped at {} (player inventory and storage both rejected it): {} x{}",
                    dropPos, player.getGameProfile().getName(),
                    ItemStackUtils.registryId(leftover), leftover.getCount());
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
