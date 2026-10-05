package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.huanghuang.rsintegration.disk.core.SaturatedTree;
import com.huanghuang.rsintegration.util.ItemStackUtils;
import com.refinedmods.refinedstorage.api.storage.AccessType;
import com.refinedmods.refinedstorage.api.storage.IStorage;
import com.refinedmods.refinedstorage.api.storage.externalstorage.IExternalStorage;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.api.util.IStackList;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import com.refinedmods.refinedstorage.api.util.StackListResult;
import com.refinedmods.refinedstorage.apiimpl.util.ItemStackList;
import com.refinedmods.refinedstorage.apiimpl.util.FluidStackList;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** 仅有统一盘的网络使用；精确索引与按来源统计避免同类 NBT 扫描及饱和减量丢失。 */
public final class IndexedStackList<T> implements IStackList<T> {
    private final class Entry {
        final FrozenKey key;
        final StackListEntry<T> view;
        final Reference2IntOpenHashMap<Object> sourceSlots = new Reference2IntOpenHashMap<>();
        int[] amounts = new int[2];
        SaturatedTree total = new SaturatedTree(2);
        boolean pending;
        Entry(FrozenKey key, UUID id) {
            this.key = key; view = new StackListEntry<>(id, stack(key, 0));
            sourceSlots.defaultReturnValue(-1);
        }
        void source(Object source, int value) {
            int slot = sourceSlots.getInt(source);
            if (slot < 0) {
                slot = sourceSlots.size(); sourceSlots.put(source, slot);
                if (slot == amounts.length) {
                    int[] expanded = new int[amounts.length * 2];
                    System.arraycopy(amounts, 0, expanded, 0, amounts.length); amounts = expanded;
                    total = new SaturatedTree(amounts.length);
                    for (int i = 0; i < slot; i++) total.set(i, amounts[i]);
                }
            }
            amounts[slot] = value; total.set(slot, value);
        }
        int source(Object source) { int slot = sourceSlots.getInt(source); return slot < 0 ? 0 : amounts[slot]; }
    }
    private final FrozenKey.Kind kind;
    private final Supplier<List<IStorage<T>>> storages;
    private final Object2ObjectLinkedOpenHashMap<FrozenKey, Entry> entries = new Object2ObjectLinkedOpenHashMap<>();
    private final Map<UUID, Entry> ids = new LinkedHashMap<>();
    private final Reference2ObjectOpenHashMap<Object, Object2ObjectLinkedOpenHashMap<FrozenKey, Entry>> types = new Reference2ObjectOpenHashMap<>();
    private final Object anonymous = new Object();
    private boolean rebuilding;
    private Object rebuildSource;
    private IStackList<T> exceptional;

    public IndexedStackList(FrozenKey.Kind kind) { this(kind, null); }
    public IndexedStackList(FrozenKey.Kind kind, Supplier<List<IStorage<T>>> storages) { this.kind = kind; this.storages = storages; }
    public void beginRebuild() { rebuilding = true; }
    public void sourceForRebuild(IStorage<?> source) { rebuilding = true; rebuildSource = source; }
    public void endRebuild() { rebuilding = false; rebuildSource = null; }
    private FrozenKey key(T source) {
        return kind == FrozenKey.Kind.ITEM
                ? FrozenKey.queryItem(ItemStackUtils.normalizeEmptyTag((ItemStack) source))
                : FrozenKey.queryFluid((FluidStack) source);
    }
    private FrozenKey indexedKey(T source) {
        try { return key(source); }
        catch (IllegalArgumentException unsupported) { return null; }
    }
    @SuppressWarnings("unchecked") private IStackList<T> exceptional() {
        if (exceptional == null) exceptional = (IStackList<T>) (kind == FrozenKey.Kind.ITEM ? new ItemStackList() : new FluidStackList());
        return exceptional;
    }
    private Object type(T source) { return kind == FrozenKey.Kind.ITEM ? ((ItemStack) source).getItem() : ((FluidStack) source).getFluid(); }
    private int amount(T source) { return kind == FrozenKey.Kind.ITEM ? ((ItemStack) source).getCount() : ((FluidStack) source).getAmount(); }
    @SuppressWarnings("unchecked") private T stack(FrozenKey key, int amount) { return (T) (kind == FrozenKey.Kind.ITEM ? key.itemStack(amount) : key.fluidStack(amount)); }
    private void amount(T source, int value) { if (kind == FrozenKey.Kind.ITEM) ((ItemStack) source).setCount(value); else ((FluidStack) source).setAmount(value); }

    private Entry ensure(FrozenKey key, UUID id) {
        Entry existing = entries.get(key);
        if (existing != null) return existing;
        if (id == null) id = UUID.randomUUID();
        Entry entry = new Entry(key, id);
        entries.put(key, entry); ids.put(id, entry);
        types.computeIfAbsent(key.type(), ignored -> new Object2ObjectLinkedOpenHashMap<>()).put(key, entry);
        return entry;
    }

    private Entry ensure(T source, FrozenKey query) {
        Entry existing = entries.get(query);
        if (existing != null) return existing;
        try {
            ItemStack normalized = kind == FrozenKey.Kind.ITEM ? ItemStackUtils.normalizeEmptyTag((ItemStack) source) : null;
            FrozenKey stored = kind == FrozenKey.Kind.ITEM ? query.freezeItem(normalized)
                    : query.freezeFluid((FluidStack) source);
            return ensure(stored, null);
        } catch (IllegalArgumentException unsupported) { return null; }
    }

    public void changed(IStorage<T> source, T resource, int change) {
        if (source.getAccessType() == AccessType.INSERT || change == 0) return;
        FrozenKey identity = indexedKey(resource);
        if (identity == null) return;
        Entry entry = ensure(resource, identity);
        if (entry == null) return;
        int old = entry.source(source);
        if (source instanceof IExternalStorage<?>) {
            T current = source.extract(stack(entry.key, 1), Integer.MAX_VALUE, IComparer.COMPARE_NBT, Action.SIMULATE);
            entry.source(source, current == null ? 0 : amount(current));
        } else entry.source(source, change > 0 ? SaturatedTree.add(old, change) : Math.max(0, old + change));
        entry.pending = true;
    }

    private void reconcile(Entry entry) {
        entry.sourceSlots.clear(); entry.amounts = new int[2]; entry.total = new SaturatedTree(2);
        T template = stack(entry.key, 1);
        for (IStorage<T> storage : storages.get()) {
            if (storage.getAccessType() == AccessType.INSERT) continue;
            T found = storage.extract(template, Integer.MAX_VALUE, IComparer.COMPARE_NBT, Action.SIMULATE);
            if (found != null && amount(found) > 0) entry.source(storage, amount(found));
        }
    }

    private StackListResult<T> publish(Entry entry, int before) {
        int after = entry.total.total();
        amount(entry.view.getStack(), after);
        if (after == 0) {
            entries.remove(entry.key); ids.remove(entry.view.getId());
            var bucket = types.get(entry.key.type()); bucket.remove(entry.key);
            if (bucket.isEmpty()) types.remove(entry.key.type());
        }
        entry.pending = false;
        // 删除通知仍携带原资源身份；count=0 的 ItemStack.copy() 会变成 AIR 丢失身份。
        T notification = after == 0 ? stack(entry.key, Math.max(1, before)) : entry.view.getStack();
        return new StackListResult<>(notification, entry.view.getId(), after - before);
    }

    @Override public StackListResult<T> add(T source, int size) {
        if (size <= 0 || amount(source) <= 0) throw new IllegalArgumentException("不能添加空资源");
        FrozenKey identity = indexedKey(source);
        if (identity == null) return exceptional().add(source, size);
        Entry entry = ensure(source, identity);
        if (entry == null) return exceptional().add(source, size);
        int before = amount(entry.view.getStack());
        if (rebuilding || storages == null) {
            Object origin = rebuilding && rebuildSource != null ? rebuildSource : anonymous;
            entry.source(origin, SaturatedTree.add(entry.source(origin), size));
        } else if (!entry.pending) reconcile(entry);
        return publish(entry, before);
    }
    @Override public StackListResult<T> add(T stack) { return add(stack, amount(stack)); }
    @Override public StackListResult<T> remove(T source, int size) {
        if (size <= 0) throw new IllegalArgumentException("移除数量必须为正数");
        FrozenKey identity = indexedKey(source);
        if (identity == null) return exceptional == null ? null : exceptional.remove(source, size);
        Entry entry = entries.get(identity);
        if (entry == null) return null;
        int before = amount(entry.view.getStack());
        if (storages == null) entry.source(anonymous, Math.max(0, entry.source(anonymous) - size));
        else if (!entry.pending) reconcile(entry);
        return publish(entry, before);
    }
    @Override public StackListResult<T> remove(T stack) { return remove(stack, amount(stack)); }
    private Entry find(T source, int flags) {
        if ((flags & IComparer.COMPARE_NBT) != 0) {
            FrozenKey identity = indexedKey(source);
            if (identity == null) return null;
            Entry entry = entries.get(identity);
            return entry != null && ((flags & IComparer.COMPARE_QUANTITY) == 0 || amount(entry.view.getStack()) == amount(source)) ? entry : null;
        }
        var bucket = types.get(type(source));
        if (bucket == null) return null;
        for (Entry entry : bucket.values()) {
            if ((flags & IComparer.COMPARE_QUANTITY) == 0 || amount(entry.view.getStack()) == amount(source)) return entry;
        }
        return null;
    }
    @Override public T get(T source, int flags) { Entry entry = find(source, flags); return entry != null ? entry.view.getStack() : exceptional == null ? null : exceptional.get(source, flags); }
    @Override public StackListEntry<T> getEntry(T source, int flags) { Entry entry = find(source, flags); return entry != null ? entry.view : exceptional == null ? null : exceptional.getEntry(source, flags); }
    @Override public int getCount(T source, int flags) { T found = get(source, flags); return found == null ? 0 : amount(found); }
    @Override public T get(UUID id) { Entry entry = ids.get(id); return entry != null ? entry.view.getStack() : exceptional == null ? null : exceptional.get(id); }
    @Override public void clear() { entries.clear(); ids.clear(); types.clear(); rebuildSource = null; if (exceptional != null) exceptional.clear(); }
    @Override public boolean isEmpty() { return entries.isEmpty() && (exceptional == null || exceptional.isEmpty()); }
    @Override public int size() { return entries.size() + (exceptional == null ? 0 : exceptional.size()); }
    @Override public Collection<StackListEntry<T>> getStacks() { List<StackListEntry<T>> result = new ArrayList<>(size()); for (Entry entry : entries.values()) result.add(entry.view); if (exceptional != null) result.addAll(exceptional.getStacks()); return result; }
    @Override public Collection<StackListEntry<T>> getStacks(T stack) {
        var bucket = types.get(type(stack));
        List<StackListEntry<T>> result = new ArrayList<>();
        if (bucket != null) for (Entry entry : bucket.values()) result.add(entry.view);
        if (exceptional != null) result.addAll(exceptional.getStacks(stack));
        return result;
    }
    @Override public IStackList<T> copy() {
        IndexedStackList<T> copy = new IndexedStackList<>(kind);
        for (Entry entry : entries.values()) {
            IndexedStackList<T>.Entry target = copy.ensure(entry.key, entry.view.getId());
            target.source(copy.anonymous, amount(entry.view.getStack()));
            copy.publish(target, 0);
        }
        if (exceptional != null) copy.exceptional = exceptional.copy();
        return copy;
    }
}
