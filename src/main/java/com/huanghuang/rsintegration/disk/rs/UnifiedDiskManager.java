package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.config.RSStorageConfig;
import com.huanghuang.rsintegration.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.disk.core.UnifiedDiskSummary;
import com.huanghuang.rsintegration.disk.core.UnifiedDiskCore.Limits;
import com.huanghuang.rsintegration.disk.persistence.DiskFileStore;
import com.huanghuang.rsintegration.disk.persistence.DiskFileStore.Manifest;
import com.huanghuang.rsintegration.disk.persistence.DiskFileStore.Saved;
import com.huanghuang.rsintegration.disk.persistence.DiskFileStore.Snapshot;
import com.refinedmods.refinedstorage.apiimpl.API;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 每服务器一个管理器；只有一份冻结快照在后台保存，队列不会无限增长。 */
public final class UnifiedDiskManager {
    private static final Logger LOGGER = LogManager.getLogger(UnifiedDiskManager.class);
    private static final Map<MinecraftServer, UnifiedDiskManager> SERVERS = new IdentityHashMap<>();
    public static final class Entry {
        public final UUID id;
        public UnifiedDiskCore core;
        public Manifest manifest;
        public String error;
        private UnifiedDiskSummary summary;
        Entry(UUID id) { this.id = id; }
    }
    private record Pending(Entry entry, Snapshot snapshot, CompletableFuture<Saved> result) {}
    private final MinecraftServer server;
    private final DiskFileStore files;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RSI unified disk writer");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<UUID, Integer> tooltipRequests = new HashMap<>();
    private final UnifiedMountCoordinator mounts = new UnifiedMountCoordinator();
    private Pending pending;
    private final UUID worldId;
    private final boolean enabled;
    private final String identityError;

    private UnifiedDiskManager(MinecraftServer server) {
        this.server = server;
        files = new DiskFileStore(server.getWorldPath(LevelResource.ROOT).resolve("data/rs_integration/unified_disks"));
        UUID identity = null;
        String failure = null;
        try { identity = files.worldIdentity(); }
        catch (IOException e) { failure = e.getMessage(); LOGGER.error("[RSI] 统一盘世界身份不可用，禁用挂载并保留全部文件", e); }
        worldId = identity; identityError = failure;
        enabled = identity != null && RSStorageConfig.enabled(RSStorageConfig.UNIFIED_DISK);
    }

    public static UnifiedDiskManager get(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (!server.isSameThread()) throw new IllegalStateException("统一盘管理器必须在服务器主线程访问");
        return SERVERS.computeIfAbsent(server, UnifiedDiskManager::new);
    }

    public static UnifiedDiskManager existing(MinecraftServer server) { return SERVERS.get(server); }
    public UUID worldId() { return worldId; }
    public boolean enabled() { return enabled; }
    public UnifiedMountCoordinator mounts() { return mounts; }

    public List<UUID> savedDiskIds() throws IOException {
        if (identityError != null) throw new IOException(identityError);
        return files.savedDiskIds();
    }

    /** 找回原库存的物理凭据；不创建空库存、不更换 UUID、不复制库存。 */
    public void restoreIdentity(UUID id, ItemStack replacement) throws IOException {
        if (!enabled()) throw new IOException("统一盘已关闭或世界身份不可用");
        if (!(replacement.getItem() instanceof UnifiedDiskItem item) || replacement.getCount() != 1
                || replacement.hasTag()) throw new IOException("找回必须使用新的空白归墟盘");
        Entry known = entry(id);
        if (known.core == null) throw new IOException("原库存不可用：" + known.error);
        if (!id.equals(known.core.diskId) || !worldId().equals(known.core.worldId)) {
            throw new IOException("原库存身份不匹配");
        }
        item.setIdentity(replacement, id, worldId());
    }

    public boolean allowTooltipRequest(UUID player) {
        int tick = server.getTickCount();
        Integer previous = tooltipRequests.get(player);
        if (previous != null && tick - previous < 10) return false;
        tooltipRequests.put(player, tick);
        return true;
    }

    public void forgetTooltipPlayer(UUID player) { tooltipRequests.remove(player); }

    public UnifiedDiskSummary summary(UUID id) {
        Entry known = entries.get(id);
        if (known != null && known.core == null && known.summary != null) return known.summary;
        Entry entry = entry(id);
        if (entry.core == null) return null;
        entry.summary = UnifiedDiskSummary.from(entry.core);
        return entry.summary;
    }

    public Entry entry(UUID id) {
        Entry entry = entries.computeIfAbsent(id, Entry::new);
        if (identityError != null) { entry.error = identityError; return entry; }
        if (entry.core != null || entry.error != null) return entry;
        try {
            entry.manifest = files.manifest(id);
            entry.core = files.load(worldId, id);
        } catch (Exception e) {
            entry.error = e.getMessage();
            LOGGER.error("[RSI] 统一盘 {} 不可用，保留原始文件，拒绝创建空库存", id, e);
        }
        return entry;
    }

    public UnifiedDiskRoot create(UUID owner) throws IOException {
        if (!enabled) throw new IOException("统一盘已关闭");
        UUID id = UUID.randomUUID();
        Limits limits = new Limits(RSStorageConfig.diskLimit(RSStorageConfig.DISK_ITEM_ENTRIES),
                RSStorageConfig.diskLimit(RSStorageConfig.DISK_FLUID_ENTRIES),
                RSStorageConfig.diskLimit(RSStorageConfig.DISK_ENTRY_BYTES),
                RSStorageConfig.diskLimit(RSStorageConfig.DISK_PAYLOAD_BYTES));
        UnifiedDiskCore core = new UnifiedDiskCore(worldId, id, owner, limits);
        Saved saved = files.save(Snapshot.freeze(core), null);
        Entry entry = new Entry(id);
        entry.core = core;
        entry.manifest = saved.manifest();
        entries.put(id, entry);
        return new UnifiedDiskRoot(this, id, worldId, owner);
    }

    public UnifiedDiskRoot resolve(ItemStack stack, ServerLevel level) {
        if (!enabled || !(stack.getItem() instanceof UnifiedDiskItem item) || !item.isValid(stack)
                || stack.getTag().getInt("Format") != 1 || !worldId.equals(item.worldId(stack))) return null;
        UUID id = item.getId(stack);
        var disk = API.instance().getStorageDiskManager(level).get(id);
        if (disk instanceof UnifiedDiskRoot root) {
            return root.compatible() && id.equals(root.id()) && worldId.equals(root.worldId()) ? root : null;
        }
        if (disk != null) return null;
        Entry entry = entry(id);
        if (entry.core == null) return null;
        UnifiedDiskRoot root = new UnifiedDiskRoot(this, id, worldId, entry.core.owner);
        API.instance().getStorageDiskManager(level).set(id, root);
        API.instance().getStorageDiskManager(level).markForSaving();
        return root;
    }

    public void tick() {
        mounts.sweep();
        if (pending != null && pending.result.isDone()) finish();
        if (pending == null && server.getTickCount() % 100 == 0) {
            for (Entry entry : entries.values()) {
                if (entry.core != null && entry.core.dirty()) { schedule(entry); break; }
            }
        }
        // 非挂载的干净核心可逐出；UUID 代理及磁盘文件继续保留。
        if (server.getTickCount() % 1200 == 0) {
            for (Entry entry : entries.values()) {
                if (entry.core != null && !entry.core.dirty() && !mounts.mounted(entry.id)
                        && (pending == null || pending.entry != entry)) {
                    entry.summary = UnifiedDiskSummary.from(entry.core);
                    entry.core = null;
                }
            }
        }
    }

    private void schedule(Entry entry) {
        Snapshot snapshot = Snapshot.freeze(entry.core);
        Manifest previous = entry.manifest;
        pending = new Pending(entry, snapshot, CompletableFuture.supplyAsync(() -> {
            try { return files.save(snapshot, previous); }
            catch (IOException e) { throw new IllegalStateException(e); }
        }, writer));
    }

    private void finish() {
        Pending completed = pending;
        pending = null;
        try {
            Saved saved = completed.result.join();
            completed.entry.manifest = saved.manifest();
            completed.entry.core.items.acknowledge(completed.snapshot.items());
            completed.entry.core.fluids.acknowledge(completed.snapshot.fluids());
        } catch (RuntimeException e) {
            LOGGER.error("[RSI] 统一盘 {} 保存失败，保留 dirty 与旧检查点，等待重试", completed.entry.id, e);
        }
    }

    public void flush() {
        if (pending != null) finish();
        for (Entry entry : entries.values()) {
            if (entry.core != null && entry.core.dirty()) { schedule(entry); finish(); }
        }
    }

    public static void stop(MinecraftServer server) {
        UnifiedDiskManager manager = SERVERS.get(server);
        if (manager == null) return;
        manager.flush();
        manager.writer.shutdown();
        manager.mounts.clear();
        SERVERS.remove(server);
    }

    public String diagnostic(UUID id) {
        Entry entry = entry(id);
        if (entry.core == null) return "盘=" + id + "，不可用=" + entry.error;
        return "盘=" + id + "，世界=" + worldId + "，物品种类=" + entry.core.items.size()
                + "，流体种类=" + entry.core.fluids.size() + "，载荷=" + entry.core.payloadBytes()
                + "，dirty=" + entry.core.dirty() + "，保存中=" + (pending != null && pending.entry == entry)
                + "，提交=" + entry.manifest.commit() + "，挂载=" + mounts.location(id);
    }
}
