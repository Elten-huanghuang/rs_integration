package com.huanghuang.rsintegration.unifiedgrid;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.magic.MagicResourceDirty;
import com.huanghuang.rsintegration.magic.MagicResourceEndpoint;
import com.huanghuang.rsintegration.magic.MagicResourceInteraction;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.magic.MagicResourceStack;
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

import javax.annotation.Nullable;
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
    private final Channel<ItemStack> items = new Channel<>(GridResourceKind.item());
    private final Channel<FluidStack> fluids = new Channel<>(GridResourceKind.fluid());
    /** 每种已安装的魔法资源一个通道，条目直接查询归墟盘。 */
    private final List<MagicChannel> magic = new ArrayList<>(3);

    public UnifiedGridSession(GridContainerMenu menu, ServerPlayer player) {
        this(menu, player, packet -> packet.send(player));
    }

    UnifiedGridSession(GridContainerMenu menu, ServerPlayer player, Consumer<UnifiedGridUpdatePacket> transport) {
        this.menu = menu;
        this.player = player;
        this.transport = transport;
        for (MagicResourceKind kind : MagicResourceKind.values()) {
            GridResourceKind gridKind = GridResourceKind.ofMagic(kind);
            if (gridKind != null) magic.add(new MagicChannel(gridKind));
        }
    }

    public static boolean supports(IGrid grid) {
        return grid instanceof INetworkAwareGrid
                // 样板终端复用 GridContainerMenu，但槽位和写入协议由 RS 原生处理。
                // 混合会话接管后会屏蔽原生样板同步，因此必须排除 PATTERN。
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
            for (MagicChannel channel : magic) channel.resetRequested = true;
        }
        boolean allowed = current != null && current.getSecurityManager().hasPermission(Permission.AUTOCRAFTING, player);
        if (allowed != canCraft) {
            canCraft = allowed;
            items.resetRequested = true;
            fluids.resetRequested = true;
            for (MagicChannel channel : magic) channel.resetRequested = true;
        }
        if (nextSerial >= Integer.MAX_VALUE - MAX_DIRTY * 2 || items.epoch == Integer.MAX_VALUE
                || fluids.epoch == Integer.MAX_VALUE || anyMagicEpochExhausted()) {
            id = UUID.randomUUID();
            nextSerial = 1;
            items.resetRequested = true;
            fluids.resetRequested = true;
            items.epoch = fluids.epoch = 0;
            for (MagicChannel channel : magic) {
                channel.epoch = 0;
                channel.resetRequested = true;
            }
        }
        try {
            items.prepare();
            fluids.prepare();
            for (MagicChannel channel : magic) channel.prepare();
            // 各类轮流发送，避免大列表阻塞。
            for (int i = 0; i < PACKETS_PER_TICK; i++) {
                items.pump();
                fluids.pump();
                for (MagicChannel channel : magic) channel.pump();
            }
        } catch (RuntimeException exception) {
            // 极端模板超过协议上限时回到原版，不能静默丢掉该资源的显示。
            RSIntegrationMod.LOGGER.warn("混合终端同步失败，当前菜单恢复原版路径", exception);
            transport.accept(new UnifiedGridUpdatePacket(menu.containerId, id, GridResourceKind.item(), 0, 0,
                    true, true, false, false, new byte[0]));
            enabled = false;
            close();
        }
    }

    private boolean anyMagicEpochExhausted() {
        for (MagicChannel channel : magic) if (channel.epoch == Integer.MAX_VALUE) return true;
        return false;
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

        // 魔法资源通道直接查询归墟盘，不走 RS 缓存，单独校验
        if (packet.kind().isMagic()) {
            MagicChannel channel = magicChannel(packet.kind());
            if (channel == null) return;
            if (packet.action() == UnifiedGridActionPacket.Action.RESYNC) {
                if (player.tickCount - channel.lastResync >= 20) {
                    channel.lastResync = player.tickCount;
                    channel.resetRequested = true;
                }
                return;
            }
            if (network == null || ((INetworkAwareGrid) menu.getGrid()).getNetwork() != network
                    || !menu.getGrid().isGridActive() || !network.canRun() || channel.epoch != packet.epoch()
                    || channel.resetRequested || packet.flags() > 7) return;
        } else {
            Channel<?> channel = packet.kind() == GridResourceKind.item() ? items : fluids;
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
        }
        switch (packet.action()) {
            case INSERT_ITEM -> {
                if (packet.kind() == GridResourceKind.item() && packet.serial() == 0 && permitted(Permission.INSERT))
                    network.getItemGridHandler().onInsertHeldItem(player, (packet.flags() & 1) != 0);
            }
            case INSERT_FLUID -> {
                if (packet.kind() == GridResourceKind.fluid() && packet.serial() == 0 && permitted(Permission.INSERT))
                    applyFluidTransfer(UnifiedGridFluidTransfer.empty(network, menu.getCarried()), false, false);
            }
            case INSERT_MAGIC -> {
                if (!packet.kind().isMagic() || packet.serial() != 0 || !permitted(Permission.INSERT)) return;
                MagicChannel channel = magicChannel(packet.kind());
                if (channel == null) return;
                int moved = MagicResourceInteraction.depositToNetwork(
                        player, network, channel.resource, menu.getCarried());
                if (moved > 0) channel.forceRefresh(); // 立即刷新该资源条目
            }
            case FILL_FLUID -> {
                if (packet.kind() != GridResourceKind.fluid() || !permitted(Permission.EXTRACT)) return;
                ServerEntry entry = fluids.bySerial.get(packet.serial());
                if (entry == null || entry.storedId == null) return;
                FluidStack fluid = fluids.cache.getList().get(entry.storedId);
                if (fluid != null) applyFluidTransfer(UnifiedGridFluidTransfer.fill(network, menu.getCarried(), fluid),
                        true, (packet.flags() & IItemGridHandler.EXTRACT_SHIFT) != 0);
            }
            case FILL_MAGIC -> {
                if (!packet.kind().isMagic() || !permitted(Permission.EXTRACT)) return;
                MagicChannel channel = magicChannel(packet.kind());
                if (channel == null || packet.serial() != channel.serial) return;
                int moved = MagicResourceInteraction.withdrawFromNetwork(
                        player, network, channel.resource, menu.getCarried(), channel.lastAmount);
                if (moved > 0) channel.forceRefresh();
            }
            case EXTRACT, SCROLL -> {
                // 魔法资源不支持 EXTRACT/SCROLL，只有物品和流体
                if (packet.kind().isMagic()) return;
                Channel<?> channel = packet.kind() == GridResourceKind.item() ? items : fluids;
                ServerEntry entry = channel.bySerial.get(packet.serial());
                Permission permission = packet.action() == UnifiedGridActionPacket.Action.SCROLL && (packet.flags() & 2) != 0
                        ? Permission.INSERT : Permission.EXTRACT;
                if (entry == null || entry.storedId == null || !permitted(permission)) return;
                // UUID 查询使用当前缓存，不相信客户端数量，也不信任已经换代的 UUID。
                if (channel.cache.getList().get(entry.storedId) == null) return;
                if (packet.kind() == GridResourceKind.item()) {
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

    @Nullable
    private MagicChannel magicChannel(GridResourceKind kind) {
        for (MagicChannel channel : magic) {
            if (channel.gridKind == kind) return channel;
        }
        return null;
    }

    private boolean permitted(Permission permission) {
        return network.getSecurityManager().hasPermission(permission, player);
    }

    private void applyFluidTransfer(UnifiedGridFluidTransfer.Result result, boolean filling, boolean shift) {
        UnifiedGridFluidTransfer.apply(player, network, result, filling, shift);
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
            return kind == GridResourceKind.item() ? network.getItemStorageTracker().get((ItemStack) stack)
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
        private int capacity;
        private StorageTrackerEntry tracker;

        private ServerEntry(int serial, Object template, GridResourceKey key) {
            this.serial = serial;
            this.template = template;
            this.key = key;
        }
        private UnifiedGridEntry update(boolean metadata) {
            return new UnifiedGridEntry(serial, amount, capacity, metadata, storedId, craftableId,
                    metadata ? template : null, tracker);
        }
    }

    private static final class Dirty<T> {
        private final T template;
        private final Set<UUID> ids = new LinkedHashSet<>();
        private Dirty(T template) { this.template = template; }
    }

    /**
     * 魔法资源的轻量级通道：每种资源一个条目，直接查询已挂载的归墟盘，不监听 RS 缓存。
     */
    private final class MagicChannel {
        /** 兜底轮询周期；正常情况下靠 {@link MagicResourceDirty} 的版本号即时推送。 */
        private static final int FALLBACK_POLL_INTERVAL = 100;
        private final GridResourceKind gridKind;
        private final MagicResourceKind resource;
        private int epoch;
        private boolean resetRequested = true;
        private int lastResync = -20;
        private int lastPoll = -FALLBACK_POLL_INTERVAL;
        private long seenVersion = -1;
        private ServerEntry entry;
        private int lastAmount = -1;
        private int lastCapacity = -1;
        private boolean sentInitial;
        private int serial;

        private MagicChannel(GridResourceKind gridKind) {
            this.gridKind = gridKind;
            this.resource = gridKind.magicKind();
        }

        private void prepare() {
            if (!resetRequested) return;
            resetRequested = false;
            sentInitial = false;
            lastAmount = -1;
            lastCapacity = -1;
            lastPoll = player.tickCount; // 立即查询
            seenVersion = -1;            // 重置后无条件重推一次
            epoch++;
            if (entry == null) {
                if (nextSerial == Integer.MAX_VALUE) throw new IllegalStateException("会话条目编号耗尽");
                serial = nextSerial++;
                entry = new ServerEntry(serial, new MagicResourceStack(resource, 1),
                        GridResourceKey.of(gridKind, new MagicResourceStack(resource, 0)));
            }
            entry.storedId = UUID.randomUUID(); // 固定 UUID，不会变化
            entry.craftableId = null;
            entry.tracker = null;
        }

        /** 充放操作后立即刷新条目。 */
        private void forceRefresh() {
            lastPoll = player.tickCount - FALLBACK_POLL_INTERVAL;
            seenVersion = -1;
        }

        private void pump() {
            if (network == null) {
                if (!sentInitial) {
                    send(true, true, new byte[0]);
                    sentInitial = true;
                }
                return;
            }
            if (!resource.available()) return;
            // 其他能量曾在这里漏刷新：魔法资源不走 RS 缓存，没有变更通知，
            // 过去只能每秒轮询，任务预留、溢出写入这类改动要等一个周期才可见。
            // 现在所有写入路径都声明版本号，这里只在版本变化或兜底周期到时查询，
            // 三种资源一视同仁，不再为某一种资源单独补丁。
            long version = MagicResourceDirty.version();
            boolean changed = version != seenVersion;
            boolean fallback = player.tickCount - lastPoll >= FALLBACK_POLL_INTERVAL && sentInitial;
            if (!changed && !fallback) return;
            seenVersion = version;
            lastPoll = player.tickCount;

            int total = MagicResourceEndpoint.available(network, resource);
            int capacity = MagicResourceEndpoint.capacity(network, resource);
            // 数量和网络容量都变化时更新终端，容量来源与绑定方块完全一致。
            if (!sentInitial || total != lastAmount || capacity != lastCapacity) {
                lastAmount = total;
                lastCapacity = capacity;
                entry.amount = total;
                entry.capacity = capacity;
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(256));
                try {
                    entry.update(true).write(buf, gridKind);
                    byte[] payload = new byte[buf.readableBytes()];
                    buf.readBytes(payload);
                    send(!sentInitial, true, payload);
                    sentInitial = true;
                } finally {
                    buf.release();
                }
            }
        }

        private void send(boolean begin, boolean end, byte[] payload) {
            transport.accept(new UnifiedGridUpdatePacket(menu.containerId, id, gridKind,
                    epoch, 0, begin, end, network != null, canCraft, payload));
        }
    }
}
