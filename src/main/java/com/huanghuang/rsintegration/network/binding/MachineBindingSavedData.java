package com.huanghuang.rsintegration.network.binding;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端权威机器绑定索引。
 *
 * <p>终端物品只保存 {@code bindingBookId}。绑定条目按书 UUID 和坐标分组，
 * 每个坐标准备一个稳定的 {@link MachineEndpointId}。读路径返回按书缓存的
 * 不可变快照，异步配方规划可以直接读取，不会反复解析 ItemStack NBT。</p>
 */
public final class MachineBindingSavedData extends SavedData {
    static final String NAME = "rsi_machine_bindings";
    private static final int FORMAT_VERSION = 3;
    private static final Map<MinecraftServer, MachineBindingSavedData> ACTIVE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final Map<UUID, BookData> books = new ConcurrentHashMap<>();
    private final Map<LocationKey, Set<UUID>> booksByLocation = new HashMap<>();
    private final Map<UUID, Set<UUID>> booksByOwner = new HashMap<>();
    private long nextSequence;
    private long nextRevision;

    MachineBindingSavedData() {}

    /** 只能在服务端主线程首次加载 SavedData。 */
    public static MachineBindingSavedData get(MinecraftServer server) {
        MachineBindingSavedData active = peek(server);
        if (active != null) return active;
        MachineBindingSavedData loaded = server.overworld().getDataStorage().computeIfAbsent(
                MachineBindingSavedData::load, MachineBindingSavedData::new, NAME);
        ACTIVE.put(server, loaded);
        return loaded;
    }

    /**
     * 获取已经加载的索引。异步合成线程只使用这个入口，避免在非主线程
     * 首次加载 SavedData 或触碰 DataStorage。
     */
    @Nullable
    public static MachineBindingSavedData peek(MinecraftServer server) {
        return ACTIVE.get(server);
    }

    static void release(MinecraftServer server) {
        ACTIVE.remove(server);
    }

    public List<BindingStorage.BindingEntry> bindings(UUID bookId) {
        BookData book = books.get(bookId);
        return book == null ? List.of() : book.entries();
    }

    public List<IndexedEndpoint> endpoints(UUID bookId) {
        BookData book = books.get(bookId);
        return book == null ? List.of() : book.endpoints();
    }

    public Set<UUID> booksForOwner(UUID ownerId) {
        Set<UUID> owned = booksByOwner.get(ownerId);
        return owned == null ? Set.of() : Set.copyOf(owned);
    }

    /** 按登记顺序返回玩家的全部端点；主要用于诊断和后续离线恢复。 */
    public List<IndexedEndpoint> endpointsForOwner(UUID ownerId) {
        Set<UUID> owned = booksByOwner.get(ownerId);
        if (owned == null || owned.isEmpty()) return List.of();
        List<IndexedEndpoint> result = new ArrayList<>();
        for (UUID bookId : owned) {
            result.addAll(endpoints(bookId));
        }
        result.sort(Comparator.comparingLong(IndexedEndpoint::sequence));
        return List.copyOf(result);
    }

    public int size(UUID bookId) {
        BookData book = books.get(bookId);
        if (book == null) return 0;
        synchronized (book) {
            return book.endpoints.size();
        }
    }

    public boolean claimOwner(UUID bookId, UUID ownerId) {
        BookData book = books.get(bookId);
        if (book == null) return false;
        synchronized (book) {
            if (!book.owners.add(ownerId)) return false;
        }
        booksByOwner.computeIfAbsent(ownerId, ignored -> new LinkedHashSet<>()).add(bookId);
        setDirty();
        return true;
    }

    /**
     * 添加或更新一个绑定。同一本书的同一坐标保持 endpointId 不变。
     *
     * @return true 表示客户端可见内容发生变化
     */
    public boolean put(UUID bookId, MachineEndpointId endpointId,
                       BindingStorage.BindingEntry entry) {
        return put(bookId, null, endpointId, entry);
    }

    public boolean put(UUID bookId, @Nullable UUID ownerId,
                       MachineEndpointId endpointId,
                       BindingStorage.BindingEntry entry) {
        BookData book = books.computeIfAbsent(bookId, BookData::new);
        synchronized (book) {
            if (ownerId != null && book.owners.add(ownerId)) {
                booksByOwner.computeIfAbsent(
                        ownerId, ignored -> new LinkedHashSet<>()).add(bookId);
                setDirty();
            }

            LocationKey location = new LocationKey(entry.dim(), entry.pos());
            BindingStorage.BindingEntry normalized = entry.compactDisplayStack();
            Endpoint previous = book.endpoints.get(location);
            long sequence = previous == null ? ++nextSequence : previous.sequence;
            MachineEndpointId stableId = previous == null ? endpointId : previous.endpointId;
            if (previous != null && previous.endpointId.equals(stableId)
                    && previous.entry.equals(normalized)) {
                return false;
            }
            Endpoint next = new Endpoint(
                    stableId, normalized, sequence, ++nextRevision);

            book.endpoints.put(location, next);
            book.invalidate();
            booksByLocation.computeIfAbsent(
                    location, ignored -> new LinkedHashSet<>()).add(bookId);
            setDirty();
            return true;
        }
    }

    public boolean remove(UUID bookId, ResourceLocation dim, BlockPos pos) {
        BookData book = books.get(bookId);
        if (book == null) return false;
        LocationKey location = new LocationKey(dim, pos);
        synchronized (book) {
            if (book.endpoints.remove(location) == null) return false;
            book.invalidate();
        }
        removeLocationBook(location, bookId);
        if (size(bookId) == 0) removeBook(bookId, book);
        setDirty();
        return true;
    }

    public int removeEverywhere(ResourceLocation dim, BlockPos pos) {
        LocationKey location = new LocationKey(dim, pos);
        Set<UUID> affected = booksByLocation.remove(location);
        if (affected == null || affected.isEmpty()) return 0;

        int removed = 0;
        for (UUID bookId : List.copyOf(affected)) {
            BookData book = books.get(bookId);
            if (book == null) continue;
            synchronized (book) {
                if (book.endpoints.remove(location) != null) removed++;
                book.invalidate();
            }
            if (size(bookId) == 0) removeBook(bookId, book);
        }
        if (removed > 0) setDirty();
        return removed;
    }

    public boolean has(UUID bookId, ResourceLocation dim, BlockPos pos) {
        BookData book = books.get(bookId);
        if (book == null) return false;
        synchronized (book) {
            return book.endpoints.containsKey(new LocationKey(dim, pos));
        }
    }

    @Nullable
    public MachineEndpointId endpointId(UUID bookId, ResourceLocation dim, BlockPos pos) {
        BookData book = books.get(bookId);
        if (book == null) return null;
        synchronized (book) {
            Endpoint endpoint = book.endpoints.get(new LocationKey(dim, pos));
            return endpoint == null ? null : endpoint.endpointId();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("version", FORMAT_VERSION);
        tag.putLong("sequence", nextSequence);
        tag.putLong("revision", nextRevision);

        ListTag encodedBooks = new ListTag();
        for (Map.Entry<UUID, BookData> bookEntry : books.entrySet()) {
            BookData book = bookEntry.getValue();
            CompoundTag encodedBook = new CompoundTag();
            synchronized (book) {
                if (book.endpoints.isEmpty()) continue;
                encodedBook.putUUID("id", bookEntry.getKey());
                if (!book.owners.isEmpty()) {
                    ListTag owners = new ListTag();
                    for (UUID ownerId : book.owners) {
                        CompoundTag encodedOwner = new CompoundTag();
                        encodedOwner.putUUID("id", ownerId);
                        owners.add(encodedOwner);
                    }
                    encodedBook.put("owners", owners);
                }

                ListTag endpoints = new ListTag();
                for (Endpoint endpoint : book.endpoints.values()) {
                    endpoints.add(endpoint.save());
                }
                encodedBook.put("endpoints", endpoints);
            }
            encodedBooks.add(encodedBook);
        }
        tag.put("books", encodedBooks);
        return tag;
    }

    static MachineBindingSavedData load(CompoundTag tag) {
        MachineBindingSavedData data = new MachineBindingSavedData();
        data.nextSequence = Math.max(0L, tag.getLong("sequence"));
        data.nextRevision = Math.max(0L, tag.getLong("revision"));

        ListTag encodedBooks = tag.getList("books", Tag.TAG_COMPOUND);
        for (int bookIndex = 0; bookIndex < encodedBooks.size(); bookIndex++) {
            CompoundTag encodedBook = encodedBooks.getCompound(bookIndex);
            if (!encodedBook.hasUUID("id")) continue;
            UUID bookId = encodedBook.getUUID("id");
            BookData book = data.books.computeIfAbsent(bookId, BookData::new);

            ListTag owners = encodedBook.getList("owners", Tag.TAG_COMPOUND);
            for (int ownerIndex = 0; ownerIndex < owners.size(); ownerIndex++) {
                CompoundTag encodedOwner = owners.getCompound(ownerIndex);
                if (!encodedOwner.hasUUID("id")) continue;
                UUID ownerId = encodedOwner.getUUID("id");
                book.owners.add(ownerId);
                data.booksByOwner.computeIfAbsent(
                        ownerId, ignored -> new LinkedHashSet<>()).add(bookId);
            }

            ListTag endpoints = encodedBook.getList("endpoints", Tag.TAG_COMPOUND);
            for (int endpointIndex = 0; endpointIndex < endpoints.size(); endpointIndex++) {
                Endpoint endpoint = Endpoint.load(endpoints.getCompound(endpointIndex));
                if (endpoint == null) continue;
                LocationKey location = new LocationKey(
                        endpoint.entry.dim(), endpoint.entry.pos());
                book.endpoints.put(location, endpoint);
                data.booksByLocation.computeIfAbsent(
                        location, ignored -> new LinkedHashSet<>()).add(bookId);
                data.nextSequence = Math.max(data.nextSequence, endpoint.sequence);
                data.nextRevision = Math.max(data.nextRevision, endpoint.revision);
            }
            book.invalidate();
            if (book.endpoints.isEmpty()) data.removeBook(bookId, book);
        }
        return data;
    }

    private void removeBook(UUID bookId, BookData book) {
        books.remove(bookId, book);
        Set<UUID> owners;
        synchronized (book) {
            owners = Set.copyOf(book.owners);
        }
        for (UUID ownerId : owners) {
            Set<UUID> owned = booksByOwner.get(ownerId);
            if (owned == null) continue;
            owned.remove(bookId);
            if (owned.isEmpty()) booksByOwner.remove(ownerId);
        }
    }

    private void removeLocationBook(LocationKey location, UUID bookId) {
        Set<UUID> owners = booksByLocation.get(location);
        if (owners == null) return;
        owners.remove(bookId);
        if (owners.isEmpty()) booksByLocation.remove(location);
    }

    private record LocationKey(ResourceLocation dim, BlockPos pos) {
        private LocationKey {
            pos = pos.immutable();
        }
    }

    public record IndexedEndpoint(
            UUID bookId,
            MachineEndpointId endpointId,
            BindingStorage.BindingEntry entry,
            long sequence,
            long revision
    ) {}

    private record Endpoint(
            MachineEndpointId endpointId,
            BindingStorage.BindingEntry entry,
            long sequence,
            long revision
    ) {
        private Endpoint {
            entry = entry.compactDisplayStack().copy();
        }

        private CompoundTag save() {
            CompoundTag tag = entry.toTag();
            tag.putUUID("endpointId", endpointId.value());
            tag.putLong("sequence", sequence);
            tag.putLong("revision", revision);
            return tag;
        }

        @Nullable
        private static Endpoint load(CompoundTag tag) {
            BindingStorage.BindingEntry entry = BindingStorage.BindingEntry.fromTag(tag);
            if (entry == null || !tag.hasUUID("endpointId")) return null;
            return new Endpoint(
                    new MachineEndpointId(tag.getUUID("endpointId")),
                    entry,
                    Math.max(0L, tag.getLong("sequence")),
                    Math.max(0L, tag.getLong("revision")));
        }
    }

    private static final class BookData {
        private final UUID bookId;
        private final LinkedHashMap<LocationKey, Endpoint> endpoints = new LinkedHashMap<>();
        private final Set<UUID> owners = new LinkedHashSet<>();
        @Nullable
        private volatile List<BindingStorage.BindingEntry> cachedEntries;
        @Nullable
        private volatile List<IndexedEndpoint> cachedEndpoints;

        private BookData(UUID bookId) {
            this.bookId = bookId;
        }

        private List<BindingStorage.BindingEntry> entries() {
            synchronized (this) {
                List<BindingStorage.BindingEntry> cached = cachedEntries;
                if (cached == null) {
                    List<BindingStorage.BindingEntry> entries = new ArrayList<>(endpoints.size());
                    for (Endpoint endpoint : endpoints.values()) {
                        entries.add(endpoint.entry());
                    }
                    cached = List.copyOf(entries);
                    cachedEntries = cached;
                }
                return cached;
            }
        }

        private List<IndexedEndpoint> endpoints() {
            synchronized (this) {
                List<IndexedEndpoint> cached = cachedEndpoints;
                if (cached == null) {
                    List<IndexedEndpoint> entries = new ArrayList<>(endpoints.size());
                    for (Endpoint endpoint : endpoints.values()) {
                        entries.add(new IndexedEndpoint(bookId, endpoint.endpointId(),
                                endpoint.entry(), endpoint.sequence(), endpoint.revision()));
                    }
                    cached = List.copyOf(entries);
                    cachedEndpoints = cached;
                }
                return cached;
            }
        }

        private void invalidate() {
            cachedEntries = null;
            cachedEndpoints = null;
        }
    }
}
