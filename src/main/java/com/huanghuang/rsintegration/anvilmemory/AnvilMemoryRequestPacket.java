package com.huanghuang.rsintegration.anvilmemory;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.function.Supplier;

public record AnvilMemoryRequestPacket(Action action, String adapterId, int memoryIndex) {
    public enum Action { SYNC, RESTOCK, SWAP, IPN_RESTOCK, REMEMBER_RESULT, TOGGLE_LOCK }

    public static void encode(AnvilMemoryRequestPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.action); buf.writeUtf(packet.adapterId, 64); buf.writeVarInt(packet.memoryIndex);
    }

    public static AnvilMemoryRequestPacket decode(FriendlyByteBuf buf) {
        return new AnvilMemoryRequestPacket(buf.readEnum(Action.class), buf.readUtf(64), buf.readVarInt());
    }

    public static void handle(AnvilMemoryRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> run(context.getSender(), packet));
        context.setPacketHandled(true);
    }

    private static void run(ServerPlayer player, AnvilMemoryRequestPacket request) {
        if (player == null) return;
        AnvilMemoryAdapter adapter = AnvilMemoryAdapters.find(player.containerMenu);
        if (!valid(adapter, request.adapterId)) {
            send(player, AnvilMemorySyncPacket.invalid(request.adapterId));
            return;
        }
        if (request.action == Action.SYNC) {
            AnvilMemoryNetworkHandler.sendSync(player, adapter);
        } else if (request.action == Action.REMEMBER_RESULT) {
            rememberResultMaterial(player, adapter);
        } else if (request.action == Action.SWAP) {
            swap(player, adapter);
        } else if (request.action == Action.TOGGLE_LOCK) {
            toggleLock(player, adapter, request.memoryIndex);
        } else if (request.action == Action.IPN_RESTOCK) {
            if (!RSIntegrationConfig.ANVIL_MEMORY_IPN_COMPAT.get()) return;
            restock(player, adapter, 0, false);
        } else {
            restock(player, adapter, request.memoryIndex);
        }
    }

    private static void rememberResultMaterial(ServerPlayer player, AnvilMemoryAdapter adapter) {
        var result = player.containerMenu.getSlot(adapter.resultSlot());
        ItemStack material = adapter.rememberedMaterial(player.containerMenu);
        if (result.getItem().isEmpty() || !result.mayPickup(player) || material.isEmpty()) return;
        AnvilMemoryData.remember(player, adapter.id(), material,
                RSIntegrationConfig.ANVIL_MEMORY_REMEMBER_NBT.get());
        AnvilMemoryNetworkHandler.sendSync(player, adapter);
    }

    private static boolean valid(AnvilMemoryAdapter adapter, String requestedId) {
        return RSIntegrationConfig.ENABLE_ANVIL_MEMORY.get() && adapter != null
                && adapter.id().equals(requestedId)
                && RSIntegrationConfig.ANVIL_MEMORY_ADAPTERS.get().contains(adapter.id());
    }

    private static void swap(ServerPlayer player, AnvilMemoryAdapter adapter) {
        var first = player.containerMenu.getSlot(adapter.primarySlot());
        var second = player.containerMenu.getSlot(adapter.materialSlot());
        ItemStack a = first.getItem().copy();
        ItemStack b = second.getItem().copy();
        if ((!b.isEmpty() && !first.mayPlace(b)) || (!a.isEmpty() && !second.mayPlace(a))) {
            send(player, AnvilMemorySyncPacket.resultEntries(adapter.id(), AnvilMemorySyncPacket.Status.OCCUPIED,
                    0, 0, ItemStack.EMPTY, 0, AnvilMemoryData.getEntries(player, adapter.id())));
            return;
        }
        first.set(b); second.set(a);
        player.containerMenu.broadcastChanges();
        send(player, AnvilMemorySyncPacket.resultEntries(adapter.id(), AnvilMemorySyncPacket.Status.SWAPPED,
                0, 0, ItemStack.EMPTY, 0, AnvilMemoryData.getEntries(player, adapter.id())));
    }

    private static void toggleLock(ServerPlayer player, AnvilMemoryAdapter adapter, int index) {
        if (!AnvilMemoryData.toggleLocked(player, adapter.id(), index)) {
            send(player, AnvilMemorySyncPacket.invalid(adapter.id()));
            return;
        }
        AnvilMemoryNetworkHandler.sendSync(player, adapter);
    }

    private static void restock(ServerPlayer player, AnvilMemoryAdapter adapter, int index) {
        restock(player, adapter, index, true);
    }

    private static void restock(ServerPlayer player, AnvilMemoryAdapter adapter, int index,
                                boolean allowInventory) {
        List<AnvilMemoryData.MemoryEntry> entries = AnvilMemoryData.getEntries(player, adapter.id());
        if (index < 0 || index >= entries.size()) {
            send(player, AnvilMemorySyncPacket.invalid(adapter.id())); return;
        }
        ItemStack wanted = entries.get(index).stack();
        var slot = player.containerMenu.getSlot(adapter.materialSlot());
        ItemStack current = slot.getItem();
        if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, wanted)) {
            send(player, AnvilMemorySyncPacket.resultEntries(adapter.id(), AnvilMemorySyncPacket.Status.OCCUPIED,
                    0, 0, wanted, 0, entries)); return;
        }
        if (!slot.mayPlace(wanted)) {
            send(player, AnvilMemorySyncPacket.resultEntries(adapter.id(), AnvilMemorySyncPacket.Status.REJECTED,
                    0, 0, wanted, 0, entries)); return;
        }
        int target = Math.min(slot.getMaxStackSize(wanted),
                RSIntegrationConfig.ANVIL_MEMORY_RESTOCK_TARGET.get());
        int needed = target - current.getCount();
        boolean inventoryFirst = allowInventory
                && RSIntegrationConfig.ANVIL_MEMORY_PREFER_PLAYER_INVENTORY.get();
        int inventory = inventoryFirst ? takeInventory(player, wanted, needed) : 0;
        if (inventory > 0) {
            if (current.isEmpty()) slot.set(wanted.copyWithCount(inventory)); else current.grow(inventory);
            needed -= inventory;
        }
        int fromRs = 0;
        var endpoint = StorageRestockSupport.resolve(player).orElse(null);
        boolean denied = endpoint != null && !StorageRestockSupport.canExtract(endpoint, player);
        if (needed > 0 && endpoint != null && !denied) {
            ItemStack extracted = StorageRestockSupport.extract(endpoint, player, wanted, needed);
            fromRs = extracted.getCount();
            if (fromRs > 0) {
                if (slot.getItem().isEmpty()) slot.set(extracted.copy()); else slot.getItem().grow(fromRs);
                needed -= fromRs;
            }
        }
        if (allowInventory && !inventoryFirst && needed > 0) {
            int afterRs = takeInventory(player, wanted, needed);
            inventory += afterRs;
            if (afterRs > 0) {
                if (slot.getItem().isEmpty()) slot.set(wanted.copyWithCount(afterRs));
                else slot.getItem().grow(afterRs);
                needed -= afterRs;
            }
        }
        player.containerMenu.broadcastChanges();
        AnvilMemorySyncPacket.Status status = needed == 0 ? AnvilMemorySyncPacket.Status.COMPLETE
                : denied ? AnvilMemorySyncPacket.Status.NO_PERMISSION
                : endpoint == null ? AnvilMemorySyncPacket.Status.NO_NETWORK
                : AnvilMemorySyncPacket.Status.PARTIAL;
        ItemStack missingStack = needed > 0 && RSIntegrationConfig.ANVIL_MEMORY_BOOKMARK_MISSING.get()
                ? wanted : ItemStack.EMPTY;
        send(player, AnvilMemorySyncPacket.resultEntries(adapter.id(), status, inventory, fromRs,
                missingStack, needed, AnvilMemoryData.getEntries(player, adapter.id())));
    }

    private static int takeInventory(ServerPlayer player, ItemStack wanted, int needed) {
        int taken = 0;
        for (int i = 0; i < player.getInventory().items.size() && taken < needed; i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (!ItemStack.isSameItemSameTags(stack, wanted)) continue;
            int move = Math.min(stack.getCount(), needed - taken);
            stack.shrink(move); taken += move;
        }
        return taken;
    }

    private static void send(ServerPlayer player, AnvilMemorySyncPacket packet) {
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
