package com.huanghuang.rsintegration.disk.core;

import com.huanghuang.rsintegration.util.ItemStackUtils;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** 主线程拥有的 512 槽分块列式表；精确查询不扫描同类型 NBT 变体。 */
public final class ResourceTable {
    public static final int PAGE_SIZE = 512;
    public record Handle(long epoch, int slot, int generation) {}
    public record PageSnapshot(int index, long keyRevision, long amountRevision,
                               byte[][] keys, int[] generations, long[] orders, int[] amounts) {}
    /** 缺失注册项只保留原始身份，不进入可用库存索引。 */
    public record QuarantinedResource(FrozenKey.Kind kind, ResourceLocation resource, byte[] payload) {
        public QuarantinedResource { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }
    public record DuplicateRepair(int keptSlot, int mergedSlot, FrozenKey.Kind kind, ResourceLocation resource) {}
    private static final class Page {
        final FrozenKey[] keys = new FrozenKey[PAGE_SIZE];
        final int[] amounts = new int[PAGE_SIZE];
        final int[] generations = new int[PAGE_SIZE];
        final long[] orders = new long[PAGE_SIZE];
        final int[] previous = new int[PAGE_SIZE];
        final int[] next = new int[PAGE_SIZE];
        final int[] livePosition = new int[PAGE_SIZE];
        long keyRevision, amountRevision, savedKeys = -1, savedAmounts = -1;
        int dirtyPosition = -1;
    }
    private static final class Bucket { int head = -1, tail = -1; }
    private final Object2IntOpenHashMap<FrozenKey> exact = new Object2IntOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<Object, Bucket> types = new Reference2ObjectOpenHashMap<>();
    private final Reference2IntOpenHashMap<Object> plain = new Reference2IntOpenHashMap<>();
    private final List<Page> pages = new ArrayList<>();
    private final IntArrayList live = new IntArrayList();
    private final IntArrayList free = new IntArrayList();
    private final IntArrayList dirtyPages = new IntArrayList();
    private final Map<Integer, QuarantinedResource> quarantined = new HashMap<>();
    private final List<DuplicateRepair> duplicateRepairs = new ArrayList<>();
    private final Set<Integer> repairedKeyPages = new HashSet<>();
    private final Set<Integer> repairedAmountPages = new HashSet<>();
    private final int maxEntries;
    private final long epoch;
    private int allocated;
    // 仅用于全盘展示的汇总；每个槽的库存和 RS 接口仍是 int。
    private long displayTotal;
    private long quarantinedTotal;
    private long revision, nextOrder;

    public ResourceTable(int maxEntries, long epoch) {
        this.maxEntries = maxEntries;
        this.epoch = epoch;
        exact.defaultReturnValue(-1);
        plain.defaultReturnValue(-1);
    }

    public int size() { return live.size(); }
    public int quarantinedSize() { return quarantined.size(); }
    public int occupiedSize() { return size() + quarantinedSize(); }
    public QuarantinedResource quarantined(int slot) { return quarantined.get(slot); }
    public List<DuplicateRepair> duplicateRepairs() { return List.copyOf(duplicateRepairs); }
    public List<ResourceLocation> quarantinedIds() {
        return quarantined.values().stream().map(QuarantinedResource::resource).distinct().toList();
    }
    // 最大条目数乘以单项 int 上限仍远小于 long 上限；从精确展示汇总截断即可。
    public int total() { return (int) Math.min(Integer.MAX_VALUE, displayTotal); }
    public long displayTotal() { return displayTotal; }
    public long storedTotal() { return displayTotal + quarantinedTotal; }
    public int capacity() { return maxEntries; }
    public long revision() { return revision; }
    public int pageCount() { return pages.size(); }
    public int amount(FrozenKey key) { int slot = exact.getInt(key); return slot < 0 ? 0 : amount(slot); }
    public int amount(int slot) { return page(slot).amounts[offset(slot)]; }
    public FrozenKey key(int slot) { return page(slot).keys[offset(slot)]; }
    public int first(Object type) { Bucket bucket = types.get(type); return bucket == null ? -1 : bucket.head; }
    public int next(int slot) { return page(slot).next[offset(slot)]; }
    public int exactSlot(FrozenKey key) { return exact.getInt(key); }
    int plainSlot(Object type) { return plain.getInt(type); }
    public int exactSlot(ItemStack source) {
        if (source.isEmpty()) return -1;
        ItemStack normalized = ItemStackUtils.normalizeEmptyTag(source);
        int slot = FrozenKey.plainItem(normalized) ? plain.getInt(normalized.getItem())
                : exact.getInt(FrozenKey.queryItem(normalized));
        // 兼容已经保存的空 tag 条目；新插入路径不会再创建这类条目。
        return slot >= 0 || normalized == source || source.getTag() == null
                ? slot : exact.getInt(FrozenKey.queryItem(source));
    }
    public int exactSlot(FluidStack source) {
        if (source.isEmpty()) return -1;
        return source.getTag() == null ? plain.getInt(source.getFluid()) : exact.getInt(FrozenKey.queryFluid(source));
    }
    public boolean canCreate() { return !free.isEmpty() || allocated < maxEntries; }

    public int insert(FrozenKey key, int requested, boolean perform) {
        if (!key.stored()) throw new IllegalArgumentException("查询身份不能作为库存模板");
        return insert(key, exact.getInt(key), requested, perform);
    }

    int insert(FrozenKey key, int slot, int requested, boolean perform) {
        if (requested <= 0) return 0;
        if (slot < 0 && !canCreate()) return 0;
        int accepted = Math.min(requested, Integer.MAX_VALUE - (slot < 0 ? 0 : amount(slot)));
        if (!perform || accepted == 0) return accepted;
        if (slot < 0) {
            nextOrder = Math.incrementExact(nextOrder);
            slot = allocate();
            addKey(slot, key, nextOrder);
        }
        setAmount(slot, amount(slot) + accepted);
        return accepted;
    }

    int insertExisting(int slot, int requested, boolean perform) {
        if (requested <= 0 || key(slot) == null) return 0;
        int accepted = Math.min(requested, Integer.MAX_VALUE - amount(slot));
        if (perform && accepted > 0) setAmount(slot, amount(slot) + accepted);
        return accepted;
    }

    public int extract(int slot, int requested, boolean perform) {
        if (slot < 0 || requested <= 0 || key(slot) == null) return 0;
        int taken = Math.min(requested, amount(slot));
        if (!perform || taken == 0) return taken;
        setAmount(slot, amount(slot) - taken);
        if (amount(slot) == 0) removeKey(slot);
        return taken;
    }

    public Handle handle(FrozenKey key) {
        int slot = exact.getInt(key);
        return slot < 0 ? null : new Handle(epoch, slot, page(slot).generations[offset(slot)]);
    }

    public boolean valid(Handle handle) {
        return handle != null && handle.epoch == epoch && handle.slot >= 0 && handle.slot < allocated
                && key(handle.slot) != null && page(handle.slot).generations[offset(handle.slot)] == handle.generation;
    }

    public void forEach(BiConsumer<FrozenKey, Integer> consumer) {
        for (int i = 0; i < live.size(); i++) { int slot = live.getInt(i); consumer.accept(key(slot), amount(slot)); }
    }

    public boolean dirty() { return !dirtyPages.isEmpty(); }

    public List<PageSnapshot> snapshot(boolean all) {
        List<PageSnapshot> result = new ArrayList<>();
        for (int position = 0; position < (all ? pages.size() : dirtyPages.size()); position++) {
            int i = all ? position : dirtyPages.getInt(position);
            Page page = pages.get(i);
            if (!all && page.keyRevision == page.savedKeys && page.amountRevision == page.savedAmounts) continue;
            byte[][] keys = null;
            if (all || page.keyRevision != page.savedKeys) {
                keys = new byte[PAGE_SIZE][];
                for (int j = 0; j < PAGE_SIZE; j++) {
                    if (page.keys[j] != null) keys[j] = page.keys[j].payload();
                    else if (!quarantined.isEmpty()) {
                        QuarantinedResource missing = quarantined.get(i * PAGE_SIZE + j);
                        if (missing != null) keys[j] = missing.payload();
                    }
                }
            }
            result.add(new PageSnapshot(i, page.keyRevision, page.amountRevision, keys,
                    keys == null ? null : page.generations.clone(), keys == null ? null : page.orders.clone(), page.amounts.clone()));
        }
        return result;
    }

    public void acknowledge(List<PageSnapshot> snapshots) {
        for (PageSnapshot snapshot : snapshots) {
            Page page = pages.get(snapshot.index);
            if (snapshot.keys != null) page.savedKeys = snapshot.keyRevision;
            page.savedAmounts = snapshot.amountRevision;
            if (page.savedKeys == page.keyRevision && page.savedAmounts == page.amountRevision && page.dirtyPosition >= 0) {
                int position = page.dirtyPosition;
                int moved = dirtyPages.removeInt(dirtyPages.size() - 1);
                if (position < dirtyPages.size()) {
                    dirtyPages.set(position, moved); pages.get(moved).dirtyPosition = position;
                }
                page.dirtyPosition = -1;
            }
        }
    }

    public boolean restore(int slot, FrozenKey key, int generation, long order, int amount) {
        if (!key.stored()) throw new IllegalArgumentException("查询身份不能恢复为库存模板");
        if (slot < 0 || slot >= maxEntries || amount <= 0 || generation <= 0 || order <= 0) {
            throw new IllegalArgumentException("统一盘槽记录非法");
        }
        ensurePage(slot);
        if (key(slot) != null || quarantined.containsKey(slot)) throw new IllegalArgumentException("统一盘槽记录重复");
        int keptSlot = exact.getInt(key);
        if (keptSlot >= 0) {
            int keptAmount = amount(keptSlot);
            if (amount > Integer.MAX_VALUE - keptAmount) {
                throw new IllegalArgumentException("统一盘重复资源数量汇总超过单条上限");
            }
            setAmount(keptSlot, keptAmount + amount);
            duplicateRepairs.add(new DuplicateRepair(keptSlot, slot, key.kind(), key.resourceId()));
            repairedKeyPages.add(slot / PAGE_SIZE);
            repairedAmountPages.add(slot / PAGE_SIZE);
            repairedAmountPages.add(keptSlot / PAGE_SIZE);
            nextOrder = Math.max(nextOrder, order);
            return false;
        }
        allocated = Math.max(allocated, slot + 1);
        page(slot).generations[offset(slot)] = generation;
        addKey(slot, key, order);
        nextOrder = Math.max(nextOrder, order);
        setAmount(slot, amount);
        return true;
    }

    public void restoreQuarantined(int slot, QuarantinedResource resource, int generation, long order, int amount) {
        if (slot < 0 || slot >= maxEntries || amount <= 0 || generation <= 0 || order <= 0
                || resource.payload.length == 0 || resource.payload.length > FrozenKey.MAX_BYTES) {
            throw new IllegalArgumentException("统一盘隔离记录非法");
        }
        ensurePage(slot);
        if (key(slot) != null || quarantined.containsKey(slot)) throw new IllegalArgumentException("统一盘槽记录重复");
        allocated = Math.max(allocated, slot + 1);
        Page page = page(slot);
        int at = offset(slot);
        page.generations[at] = generation;
        page.orders[at] = order;
        page.amounts[at] = amount;
        quarantined.put(slot, resource);
        quarantinedTotal += amount;
        nextOrder = Math.max(nextOrder, order);
        page.keyRevision = ++revision;
        page.amountRevision = ++revision;
        markDirty(slot / PAGE_SIZE, page);
    }

    public void finishRestore(int slots, int[] generations) {
        if (slots < allocated || slots > maxEntries || generations.length != slots) throw new IllegalArgumentException("统一盘槽布局非法");
        allocated = slots;
        for (int slot = 0; slot < slots; slot++) {
            ensurePage(slot);
            int generation = generations[slot];
            if (generation < 0) throw new IllegalArgumentException("统一盘槽代数非法");
            if (key(slot) == null && (quarantined.isEmpty() || !quarantined.containsKey(slot))) {
                page(slot).generations[offset(slot)] = generation;
                if (generation < Integer.MAX_VALUE) free.add(slot);
            }
        }
        for (Page page : pages) { page.savedKeys = page.keyRevision; page.savedAmounts = page.amountRevision; page.dirtyPosition = -1; }
        dirtyPages.clear();
        for (int pageIndex : repairedKeyPages) {
            Page page = pages.get(pageIndex);
            page.keyRevision = ++revision;
            markDirty(pageIndex, page);
        }
        for (int pageIndex : repairedAmountPages) {
            Page page = pages.get(pageIndex);
            page.amountRevision = ++revision;
            markDirty(pageIndex, page);
        }
    }

    private int allocate() {
        int slot = free.isEmpty() ? allocated++ : free.removeInt(free.size() - 1);
        ensurePage(slot);
        page(slot).generations[offset(slot)]++;
        return slot;
    }

    private void ensurePage(int slot) {
        while (pages.size() <= slot / PAGE_SIZE) {
            Page page = new Page(); pages.add(page); markDirty(pages.size() - 1, page);
        }
    }
    private void markDirty(int index, Page page) {
        if (page.dirtyPosition < 0) { page.dirtyPosition = dirtyPages.size(); dirtyPages.add(index); }
    }
    private Page page(int slot) { return pages.get(slot / PAGE_SIZE); }
    private int offset(int slot) { return slot % PAGE_SIZE; }

    private void addKey(int slot, FrozenKey key, long order) {
        Page page = page(slot);
        int at = offset(slot);
        page.keys[at] = key;
        page.orders[at] = order;
        page.livePosition[at] = live.size();
        live.add(slot);
        exact.put(key, slot);
        if (key.plain()) plain.put(key.type(), slot);
        Bucket bucket = types.computeIfAbsent(key.type(), ignored -> new Bucket());
        page.previous[at] = bucket.tail;
        page.next[at] = -1;
        if (bucket.tail >= 0) page(bucket.tail).next[offset(bucket.tail)] = slot;
        else bucket.head = slot;
        bucket.tail = slot;
        page.keyRevision = ++revision;
        markDirty(slot / PAGE_SIZE, page);
    }

    private void removeKey(int slot) {
        Page page = page(slot);
        int at = offset(slot);
        FrozenKey key = page.keys[at];
        exact.removeInt(key);
        if (key.plain()) plain.removeInt(key.type());
        Bucket bucket = types.get(key.type());
        int previous = page.previous[at], next = page.next[at];
        if (previous < 0) bucket.head = next; else page(previous).next[offset(previous)] = next;
        if (next < 0) bucket.tail = previous; else page(next).previous[offset(next)] = previous;
        if (bucket.head < 0) types.remove(key.type());
        int position = page.livePosition[at];
        int moved = live.removeInt(live.size() - 1);
        if (position < live.size()) { live.set(position, moved); page(moved).livePosition[offset(moved)] = position; }
        page.keys[at] = null;
        if (page.generations[at] < Integer.MAX_VALUE) free.add(slot);
        page.keyRevision = ++revision;
        markDirty(slot / PAGE_SIZE, page);
    }

    private void setAmount(int slot, int value) {
        Page page = page(slot);
        displayTotal += (long) value - page.amounts[offset(slot)];
        page.amounts[offset(slot)] = value;
        page.amountRevision = ++revision;
        markDirty(slot / PAGE_SIZE, page);
    }
}
