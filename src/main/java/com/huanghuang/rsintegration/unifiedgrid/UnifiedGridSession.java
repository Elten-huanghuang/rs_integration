package com.huanghuang.rsintegration.unifiedgrid;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.refinedmods.refinedstorage.RS;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.network.grid.handler.IItemGridHandler;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener;
import com.refinedmods.refinedstorage.api.storage.tracker.StorageTrackerEntry;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import com.refinedmods.refinedstorage.api.util.StackListResult;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** 菜单会话只在服务器主线程运行，借助 RS 的通知而非每 tick 扫描整个库存。 */
public final class UnifiedGridSession {
    private static final int MAX_DIRTY = 4096;
    private static final int PACKETS_PER_TICK = 4;
    private final GridContainerMenu menu;
    private final ServerPlayer player;
    private final Consumer<UnifiedGridUpdatePacket> transport;
    private UUID id = UUID.randomUUID();
    private int nextSerial = 1;
    private boolean enabled = true;
    private boolean closed;
    private boolean canCraft;
    private INetwork network;
    private int lastRecoveryTick = -20;
    private final Channel<ItemStack> items = new Channel<>(GridResourceKind.ITEM);
    private final Channel<FluidStack> fluids = new Channel<>(GridResourceKind.FLUID);

    public UnifiedGridSession(GridContainerMenu menu, ServerPlayer player) {
        this(menu, player, packet -> packet.send(player));
    }

    UnifiedGridSession(GridContainerMenu menu, ServerPlayer player, Consumer<UnifiedGridUpdatePacket> transport) {
        this.menu = menu;
        this.player = player;
        this.transport = transport;
    }

    public static boolean supports(IGrid grid) {
        return grid instanceof INetworkAwareGrid
                && (grid.getGridType() == GridType.NORMAL || grid.getGridType() == GridType.CRAFTING);
    }

    public boolean enabled() { return enabled && !closed; }

    public void tick() {
        if (!enabled() || player.containerMenu != menu || player.hasDisconnected()) return;
        IGrid grid = menu.getGrid();
        INetwork current = ((INetworkAwareGrid) grid).getNetwork();
        if (!grid.isGridActive() || current == null || !current.canRun()) current = null;
        if (network != current) {
            network = current;
            items.bind(current == null ? null : current.getItemStorageCache());
            fluids.bind(current == null ? null : current.getFluidStorageCache());
        }
        if (current != null && player.server != null && player.tickCount - lastRecoveryTick >= 20
                && permitted(Permission.INSERT)) {
            lastRecoveryTick = player.tickCount;
            UnifiedGridFluidRecovery.get(player).retry(player.getUUID(), current);
        }
        boolean allowed = current != null && current.getSecurityManager().hasPermission(Permission.AUTOCRAFTING, player);
        if (allowed != canCraft) {
            canCraft = allowed;
            items.resetRequested = true;
            fluids.resetRequested = true;
        }
        if (nextSerial >= Integer.MAX_VALUE - MAX_DIRTY * 2 || items.epoch == Integer.MAX_VALUE
                || fluids.epoch == Integer.MAX_VALUE) {
            id = UUID.randomUUID();
            nextSerial = 1;
            items.resetRequested = true;
            fluids.resetRequested = true;
            items.epoch = fluids.epoch = 0;
        }
        try {
            items.prepare();
            fluids.prepare();
            // 两类轮流发送，避免大物品列表阻塞流体首屏。
            for (int i = 0; i < PACKETS_PER_TICK; i++) {
                items.pump();
                fluids.pump();
            }
        } catch (RuntimeException exception) {
            // 极端模板超过协议上限时回到原版，不能静默丢掉该资源的显示。
            RSIntegrationMod.LOGGER.warn("混合终端同步失败，当前菜单恢复原版路径", exception);
            transport.accept(new UnifiedGridUpdatePacket(menu.containerId, id, GridResourceKind.ITEM, 0, 0,
                    true, true, false, false, new byte[0]));
            enabled = false;
            close();
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        items.detach();
        fluids.detach();
        network = null;
    }

    public void handle(UnifiedGridActionPacket packet) {
        if (!enabled() || !id.equals(packet.session()) || player.containerMenu != menu || !menu.stillValid(player)) return;
        Channel<?> channel = packet.kind() == GridResourceKind.ITEM ? items : fluids;
        if (packet.action() == UnifiedGridActionPacket.Action.RESYNC) {
            if (player.tickCount - channel.lastResync >= 20) {
                channel.lastResync = player.tickCount;
                channel.resetRequested = true;
            }
            return;
        }
        if (network == null || ((INetworkAwareGrid) menu.getGrid()).getNetwork() != network
                || !menu.getGrid().isGridActive() || !network.canRun() || channel.epoch != packet.epoch()
                || channel.resetRequested || channel.snapshot != null || packet.flags() > 7) return;
        switch (packet.action()) {
            case INSERT_ITEM -> {
                if (packet.kind() == GridResourceKind.ITEM && packet.serial() == 0 && permitted(Permission.INSERT))
                    network.getItemGridHandler().onInsertHeldItem(player, (packet.flags() & 1) != 0);
            }
            case INSERT_FLUID -> {
                if (packet.kind() == GridResourceKind.FLUID && packet.serial() == 0 && permitted(Permission.INSERT))
                    applyFluidTransfer(UnifiedGridFluidTransfer.empty(network, menu.getCarried()), false, false);
            }
            case FILL_FLUID -> {
                if (packet.kind() != GridResourceKind.FLUID || !permitted(Permission.EXTRACT)) return;
                ServerEntry entry = fluids.bySerial.get(packet.serial());
                if (entry == null || entry.storedId == null) return;
                FluidStack fluid = fluids.cache.getList().get(entry.storedId);
                if (fluid != null) applyFluidTransfer(UnifiedGridFluidTransfer.fill(network, menu.getCarried(), fluid),
                        true, (packet.flags() & IItemGridHandler.EXTRACT_SHIFT) != 0);
            }
            case EXTRACT, SCROLL -> {
                ServerEntry entry = channel.bySerial.get(packet.serial());
                Permission permission = packet.action() == UnifiedGridActionPacket.Action.SCROLL && (packet.flags() & 2) != 0
                        ? Permission.INSERT : Permission.EXTRACT;
                if (entry == null || entry.storedId == null || !permitted(permission)) return;
                // UUID 查询使用当前缓存，不相信客户端数量，也不信任已经换代的 UUID。
                if (channel.cache.getList().get(entry.storedId) == null) return;
                if (packet.kind() == GridResourceKind.ITEM) {
                    if (packet.action() == UnifiedGridActionPacket.Action.SCROLL) {
                        network.getItemGridHandler().onGridScroll(player, entry.storedId,
                                (packet.flags() & 1) != 0, (packet.flags() & 2) != 0);
                    } else {
                        // 原版接口顺序是背包目标槽位、提取标志；-1 表示不指定槽位。
                        network.getItemGridHandler().onExtract(player, entry.storedId, -1, packet.flags());
                    }
                }
            }
            default -> { }
        }
    }

    private boolean permitted(Permission permission) {
        return network.getSecurityManager().hasPermission(permission, player);
    }

    private void applyFluidTransfer(UnifiedGridFluidTransfer.Result result, boolean filling, boolean shift) {
        if (!result.recovery().isEmpty()) {
            UnifiedGridFluidRecovery.get(player).retain(player.getUUID(), result.recovery());
            RSIntegrationMod.LOGGER.warn("终端容器转移留下 {} mB 流体，已保存并等待归还网络", result.recovery().getAmount());
        }
        menu.setCarried(result.cursor());
        if (!result.overflow().isEmpty()) player.getInventory().placeItemBackInInventory(result.overflow());
        else if (filling && shift && result.transferred() > 0 && !result.cursor().isEmpty()) {
            ItemStack copy = result.cursor().copy();
            player.getInventory().add(copy);
            // 未能进入背包的部分留在鼠标上，避免满背包时直接丢弃。
            menu.setCarried(copy);
        }
        if (result.transferred() <= 0) return;
        network.getFluidStorageTracker().changed(player, result.resource().copy());
        if (network.getNetworkItemManager() != null) {
            var config = RS.SERVER_CONFIG.getWirelessFluidGrid();
            network.getNetworkItemManager().drainEnergy(player, filling ? config.getExtractUsage() : config.getInsertUsage());
        }
    }

    private final class Channel<T> implements IStorageCacheListener<T> {
        private final GridResourceKind kind;
        private IStorageCache<T> cache;
        private final Map<GridResourceKey, ServerEntry> rows = new HashMap<>();
        private final Int2ObjectOpenHashMap<ServerEntry> bySerial = new Int2ObjectOpenHashMap<>();
        private final Map<GridResourceKey, Dirty<T>> dirty = new LinkedHashMap<>();
        private final Map<UUID, GridResourceKey> keysById = new HashMap<>();
        private Iterator<UnifiedGridEntry> snapshot;
        private byte[] deferred;
        private boolean resetRequested = true;
        private int epoch;
        private int sequence;
        private int lastResync = -20;

        private Channel(GridResourceKind kind) { this.kind = kind; }

        private void bind(IStorageCache<T> next) {
            if (cache == next) return;
            detach();
            cache = next;
            resetRequested = true;
            if (cache != null) cache.addListener(this);
        }

        private void detach() {
            if (cache != null) cache.removeListener(this);
            cache = null;
            rows.clear();
            bySerial.clear();
            keysById.clear();
            dirty.clear();
            snapshot = null;
            deferred = null;
        }

        @Override public void onAttached() { resetRequested = true; }
        @Override public void onInvalidated() { resetRequested = true; }
        @Override public void onChanged(StackListResult<T> change) { changed(change); }
        @Override public void onChangedBulk(List<StackListResult<T>> changes) { changes.forEach(this::changed); }

        private void changed(StackListResult<T> change) {
            if (closed || resetRequested) return;
            GridResourceKey known = keysById.get(change.getId());
            GridResourceKey key = known == null ? GridResourceKey.of(kind, change.getStack()) : known;
            Dirty<T> update = dirty.computeIfAbsent(key, ignored -> new Dirty<>(change.getStack()));
            update.ids.add(change.getId());
            if (dirty.size() > MAX_DIRTY) {
                dirty.clear();
                resetRequested = true;
            }
        }

        private void prepare() {
            if (!resetRequested && sequence < Integer.MAX_VALUE - 16) return;
            resetRequested = false;
            epoch++;
            sequence = 0;
            dirty.clear();
            rows.clear();
            bySerial.clear();
            keysById.clear();
            deferred = null;
            if (cache != null) {
                // 初始合并直接遍历两个集合；不为每条再扫描同类型 NBT 桶。
                for (StackListEntry<T> stored : cache.getList().getStacks()) {
                    ServerEntry row = rowFor(stored.getStack());
                    row.storedId = stored.getId();
                    keysById.put(stored.getId(), row.key);
                    row.amount = Math.max(0, kind.amount(stored.getStack()));
                    row.tracker = tracker(stored.getStack());
                }
                for (StackListEntry<T> craftable : cache.getCraftablesList().getStacks()) {
                    ServerEntry row = rowFor(craftable.getStack());
                    row.craftableId = craftable.getId();
                    keysById.put(craftable.getId(), row.key);
                }
            }
            List<UnifiedGridEntry> copy = new ArrayList<>(rows.size());
            for (ServerEntry row : rows.values()) copy.add(row.update(true));
            snapshot = copy.iterator();
            send(true, false, new byte[0]);
        }

        private ServerEntry rowFor(T stack) {
            GridResourceKey key = GridResourceKey.of(kind, stack);
            return rows.computeIfAbsent(key, ignored -> {
                if (nextSerial == Integer.MAX_VALUE) throw new IllegalStateException("会话条目编号耗尽");
                ServerEntry row = new ServerEntry(nextSerial++, kind.copyTemplate(stack), key);
                bySerial.put(row.serial, row);
                return row;
            });
        }

        @SuppressWarnings("unchecked")
        private StorageTrackerEntry tracker(T stack) {
            if (network == null) return null;
            return kind == GridResourceKind.ITEM ? network.getItemStorageTracker().get((ItemStack) stack)
                    : network.getFluidStorageTracker().get((FluidStack) stack);
        }

        private UnifiedGridEntry refresh(GridResourceKey key, Dirty<T> update) {
            ServerEntry row = rows.get(key);
            UUID storedId = row == null ? null : row.storedId;
            UUID craftableId = row == null ? null : row.craftableId;
            T stored = storedId == null ? null : cache.getList().get(storedId);
            T craftable = craftableId == null ? null : cache.getCraftablesList().get(craftableId);
            if (stored == null) storedId = null;
            if (craftable == null) craftableId = null;
            // 通知已经带 UUID。复用原版 UUID 哈希索引，不重新扫描同类型 NBT 桶。
            for (UUID changedId : update.ids) {
                T actualStored = cache.getList().get(changedId);
                T actualCraftable = cache.getCraftablesList().get(changedId);
                if (actualStored != null) { stored = actualStored; storedId = changedId; }
                if (actualCraftable != null) { craftable = actualCraftable; craftableId = changedId; }
            }
            if (stored == null && craftable == null) {
                if (row == null) return null;
                rows.remove(key);
                bySerial.remove(row.serial);
                keysById.remove(row.storedId);
                keysById.remove(row.craftableId);
                return new UnifiedGridEntry(row.serial, 0, true, null, null, null, null);
            }
            boolean isNew = row == null;
            if (isNew) row = rowFor(stored == null ? craftable : stored);
            boolean metadata = isNew || !Objects.equals(storedId, row.storedId)
                    || !Objects.equals(craftableId, row.craftableId);
            if (metadata) {
                keysById.remove(row.storedId);
                keysById.remove(row.craftableId);
                if (storedId != null) keysById.put(storedId, key);
                if (craftableId != null) keysById.put(craftableId, key);
            }
            row.storedId = storedId;
            row.craftableId = craftableId;
            row.amount = stored == null ? 0 : Math.max(0, kind.amount(stored));
            row.tracker = tracker(update.template);
            return row.update(metadata);
        }

        private void pump() {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                int count = 0;
                while (count < UnifiedGridUpdatePacket.MAX_ENTRIES) {
                    byte[] encoded = deferred;
                    if (encoded == null) {
                        UnifiedGridEntry change;
                        if (snapshot != null) change = snapshot.hasNext() ? snapshot.next() : null;
                        else {
                            if (dirty.isEmpty() || cache == null) break;
                            Iterator<Map.Entry<GridResourceKey, Dirty<T>>> iterator = dirty.entrySet().iterator();
                            Map.Entry<GridResourceKey, Dirty<T>> entry = iterator.next();
                            change = refresh(entry.getKey(), entry.getValue());
                            iterator.remove();
                            if (change == null) continue;
                        }
                        if (change == null) break;
                        encoded = encode(change);
                    }
                    if (buf.isReadable() && buf.readableBytes() + encoded.length > UnifiedGridUpdatePacket.TARGET_BYTES) {
                        deferred = encoded;
                        break;
                    }
                    deferred = null;
                    buf.writeBytes(encoded);
                    count++;
                    if (buf.readableBytes() >= UnifiedGridUpdatePacket.TARGET_BYTES) break;
                }
                boolean finished = snapshot != null && !snapshot.hasNext() && deferred == null;
                if (buf.isReadable() || finished) {
                    byte[] payload = new byte[buf.readableBytes()];
                    buf.readBytes(payload);
                    send(false, finished, payload);
                }
                if (finished) snapshot = null;
            } finally { buf.release(); }
        }

        private byte[] encode(UnifiedGridEntry entry) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                entry.write(buf, kind);
                if (buf.readableBytes() > UnifiedGridUpdatePacket.MAX_BYTES)
                    throw new IllegalArgumentException("单条终端模板超过传输上限");
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                return bytes;
            } finally { buf.release(); }
        }

        private void send(boolean begin, boolean end, byte[] payload) {
            transport.accept(new UnifiedGridUpdatePacket(menu.containerId, id, kind, epoch, sequence++, begin, end,
                    true, canCraft, payload));
        }
    }

    private static final class ServerEntry {
        private final int serial;
        private final Object template;
        private final GridResourceKey key;
        private UUID storedId;
        private UUID craftableId;
        private int amount;
        private StorageTrackerEntry tracker;

        private ServerEntry(int serial, Object template, GridResourceKey key) {
            this.serial = serial;
            this.template = template;
            this.key = key;
        }
        private UnifiedGridEntry update(boolean metadata) {
            return new UnifiedGridEntry(serial, amount, metadata, storedId, craftableId, metadata ? template : null, tracker);
        }
    }

    private static final class Dirty<T> {
        private final T template;
        private final Set<UUID> ids = new LinkedHashSet<>();
        private Dirty(T template) { this.template = template; }
    }
}
