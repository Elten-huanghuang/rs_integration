package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryPacket;
import com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryResyncRequestPacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Client-side ordered cache of the active RS/BD network inventory. */
public final class JeiNetworkItemCache {
    public static final JeiNetworkItemCache INSTANCE = new JeiNetworkItemCache();
    private final Map<String, Entry> entries = new HashMap<>();
    private boolean connected;
    private long epoch = -1L;
    private long sequence = -1L;
    @Nullable private StorageReference reference;
    @Nullable private Pending pending;
    private boolean resyncRequested;

    private JeiNetworkItemCache() {}

    public synchronized void accept(JeiNetworkInventoryPacket packet) {
        if (isCompletedOrStale(packet)) return;
        if (pending == null || !pending.matches(packet)) {
            if (!canStart(packet)) {
                requestResync();
                return;
            }
            pending = new Pending(packet.full(), packet.epoch(), packet.sequence(),
                    packet.chunkCount(), packet.reference());
        }
        if (!pending.add(packet)) {
            requestResync();
            return;
        }
        if (!pending.complete()) return;

        List<JeiNetworkInventoryPacket.Entry> completed = pending.flatten();
        if (pending.full) {
            entries.clear();
            applyEntries(completed);
            reference = pending.reference;
            connected = reference != null;
        } else {
            applyEntries(completed);
        }
        epoch = pending.epoch;
        sequence = pending.sequence;
        pending = null;
        resyncRequested = false;
    }

    private boolean isCompletedOrStale(JeiNetworkInventoryPacket packet) {
        if (packet.epoch() < epoch) return true;
        return packet.epoch() == epoch && packet.sequence() <= sequence;
    }

    private boolean canStart(JeiNetworkInventoryPacket packet) {
        if (packet.full()) {
            return packet.epoch() > epoch
                    || packet.epoch() == epoch && packet.sequence() > sequence;
        }
        return packet.epoch() == epoch && packet.sequence() == sequence + 1L;
    }

    private void applyEntries(Iterable<JeiNetworkInventoryPacket.Entry> changes) {
        for (var entry : changes) {
            String key = key(entry.stack());
            if (entry.amount() <= 0) entries.remove(key);
            else entries.put(key, new Entry(entry.stack(), entry.amount()));
        }
    }

    private void requestResync() {
        pending = null;
        if (resyncRequested) return;
        resyncRequested = true;
        NetworkHandler.CHANNEL.sendToServer(new JeiNetworkInventoryResyncRequestPacket(
                Math.max(0L, epoch), Math.max(0L, sequence)));
    }

    public synchronized long amount(ItemStack stack) {
        Entry entry = entries.get(key(stack));
        return entry == null ? 0L : entry.amount;
    }

    /**
     * Amount used by the JEI overlay. JEI commonly renders a tagless
     * representative while a backend exposes tagged display variants.
     */
    public synchronized long amountForDisplay(ItemStack stack) {
        long exact = amount(stack);
        if (exact > 0L || stack == null || stack.isEmpty()
                || (stack.getTag() != null && !stack.getTag().isEmpty())) {
            return exact;
        }
        return amount(stack.getItem());
    }

    /** Sum every stored NBT variant for a plan material that matches by item type. */
    public synchronized long amount(Item item) {
        long total = 0L;
        for (Entry entry : entries.values()) {
            if (!entry.stack.is(item)) continue;
            if (Long.MAX_VALUE - total < entry.amount) return Long.MAX_VALUE;
            total += entry.amount;
        }
        return total;
    }

    public synchronized void clear() {
        entries.clear();
        connected = false;
        epoch = -1L;
        sequence = -1L;
        reference = null;
        pending = null;
        resyncRequested = false;
    }

    public synchronized boolean isConnected() { return connected; }

    public synchronized boolean matches(@Nullable StorageReference expected) {
        return connected && expected != null && expected.equals(reference);
    }

    @Nullable
    public synchronized StorageReference reference() { return reference; }

    public static String key(ItemStack stack) {
        return JeiNetworkInventoryPacket.key(stack);
    }

    private record Entry(ItemStack stack, long amount) {
        private Entry {
            stack = stack.copyWithCount(1);
        }
    }

    private static final class Pending {
        final boolean full;
        final long epoch;
        final long sequence;
        final List<JeiNetworkInventoryPacket.Entry>[] chunks;
        @Nullable final StorageReference reference;
        int received;

        @SuppressWarnings("unchecked")
        Pending(boolean full, long epoch, long sequence, int chunkCount,
                @Nullable StorageReference reference) {
            this.full = full;
            this.epoch = epoch;
            this.sequence = sequence;
            this.chunks = (List<JeiNetworkInventoryPacket.Entry>[]) new List<?>[chunkCount];
            this.reference = reference;
        }

        boolean matches(JeiNetworkInventoryPacket packet) {
            return full == packet.full() && epoch == packet.epoch()
                    && sequence == packet.sequence() && chunks.length == packet.chunkCount()
                    && Objects.equals(reference, packet.reference());
        }

        boolean add(JeiNetworkInventoryPacket packet) {
            if (!matches(packet)) return false;
            int index = packet.chunkIndex();
            if (chunks[index] != null) return chunks[index].equals(packet.entries());
            chunks[index] = List.copyOf(packet.entries());
            received++;
            return true;
        }

        boolean complete() { return received == chunks.length; }

        List<JeiNetworkInventoryPacket.Entry> flatten() {
            List<JeiNetworkInventoryPacket.Entry> result = new ArrayList<>();
            for (List<JeiNetworkInventoryPacket.Entry> chunk : chunks) result.addAll(chunk);
            return result;
        }
    }
}
