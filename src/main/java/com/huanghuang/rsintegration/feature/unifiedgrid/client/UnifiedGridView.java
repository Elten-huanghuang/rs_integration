package com.huanghuang.rsintegration.feature.unifiedgrid.client;

import com.huanghuang.rsintegration.mods.rs.RSGridSearchCache;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerBucketSupport;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.magic.MagicResourceStack;
import com.huanghuang.rsintegration.client.storage.MagicStackGridStack;
import com.huanghuang.rsintegration.feature.unifiedgrid.GridResourceKind;
import com.huanghuang.rsintegration.feature.unifiedgrid.UnifiedGridActionPacket;
import com.huanghuang.rsintegration.feature.unifiedgrid.UnifiedGridEntry;
import com.huanghuang.rsintegration.feature.unifiedgrid.UnifiedGridState;
import com.huanghuang.rsintegration.feature.unifiedgrid.UnifiedGridUpdatePacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.api.network.grid.IGridTab;
import com.refinedmods.refinedstorage.api.util.IFilter;
import com.refinedmods.refinedstorage.integration.jei.IngredientTracker;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import com.refinedmods.refinedstorage.screen.grid.filtering.GridFilterParser;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import com.refinedmods.refinedstorage.screen.grid.sorting.IGridSorter;
import com.refinedmods.refinedstorage.screen.grid.sorting.IdGridSorter;
import com.refinedmods.refinedstorage.screen.grid.sorting.SortingDirection;
import com.refinedmods.refinedstorage.screen.grid.view.IGridView;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath.clampToInt;

/** 复用原版条目绘制；变化只修改受影响的对象，排序合并到客户端 tick。 */
public final class UnifiedGridView implements IGridView {
    private final GridScreen screen;
    private final UnifiedGridState state = new UnifiedGridState();
    private final List<Int2ObjectLinkedOpenHashMap<Row>> rows = new ArrayList<>(GridResourceKind.count());
    private final Map<UUID, IGridStack> aliases = new HashMap<>();
    private final List<Consumer<IGridStack>> listeners = new ArrayList<>();
    private final List<Consumer<QuantityChange>> quantityListeners = new ArrayList<>();
    private final int[] lastResyncTick;
    private List<IGridStack> visible = new ArrayList<>();
    private boolean canCraft;
    private boolean dirty = true;
    private final Path preferencesPath;
    /** 过滤档位下标；{@link #FILTER_LEVELS} 是它的唯一取值来源。 */
    private int filter;
    private int lastSortTick = -5;

    /**
     * 过滤档位：全部 / 物品 / 流体与能量。
     *
     * <p>物品、流体与全部魔法资源各自计量（个数 / mB / 点），没有统一尺度，所以这里
     * 只做「看哪几类」，不试图把量归一到同一刻度。魔法资源之间不互相过滤：同一次
     * 灵魂仪式只吃灵魂、Botania 机只吃魔力，按大类展示更省事。</p>
     */
    public static final int FILTER_ALL = 0;
    public static final int FILTER_ITEMS = 1;
    public static final int FILTER_FLUIDS_AND_MAGIC = 2;
    public static final int FILTER_LEVELS = 3;

    public UnifiedGridView(GridScreen screen) {
        this.screen = screen;
        this.preferencesPath = UnifiedGridPreferences.defaultPath();
        this.filter = UnifiedGridPreferences.loadFilter(preferencesPath, FILTER_LEVELS);
        this.lastResyncTick = new int[GridResourceKind.count()];
        Arrays.fill(this.lastResyncTick, -20);
        for (GridResourceKind kind : GridResourceKind.values()) {
            rows.add(kind.id(), new Int2ObjectLinkedOpenHashMap<>());
        }
    }

    public void apply(UnifiedGridUpdatePacket packet) {
        UUID previous = state.session();
        List<UnifiedGridEntry> updates = packet.entries();
        UnifiedGridState.Result result = state.apply(packet, updates);
        if (previous != null && !previous.equals(state.session())) {
            rows.forEach(Map::clear);
            aliases.clear();
            rowIds.clear();
            visible.clear();
            invalidateIngredients();
            RSGridSearchCache.onGridReset(screen, this);
        }
        if (result == UnifiedGridState.Result.RESYNC) {
            request(packet.kind(), 0, UnifiedGridActionPacket.Action.RESYNC, 0);
            if (Minecraft.getInstance().player != null)
                lastResyncTick[packet.kind().id()] = Minecraft.getInstance().player.tickCount;
            return;
        }
        if (result == UnifiedGridState.Result.IGNORED) return;
        canCraft = packet.canCraft();
        Int2ObjectLinkedOpenHashMap<Row> kindRows = rows.get(packet.kind().id());
        if (result == UnifiedGridState.Result.RESET) {
            if (packet.kind() == GridResourceKind.item()) invalidateIngredients();
            for (Row row : kindRows.values()) removeAliases(row);
            kindRows.clear();
            for (UnifiedGridEntry entry : state.entries(packet.kind())) put(packet.kind(), entry);
            RSGridSearchCache.onGridReset(screen, this);
            dirty = true;
        } else if (result == UnifiedGridState.Result.DELTA) {
            for (UnifiedGridEntry update : updates) {
                Row old = kindRows.get(update.serial());
                if (update.removed()) {
                    if (old != null) {
                        if (packet.kind() == GridResourceKind.item()) invalidateIngredients();
                        removeAliases(old);
                        kindRows.remove(update.serial());
                        RSGridSearchCache.onGridDelta(screen, this, old.primary);
                        dirty = true;
                    }
                } else if (update.metadata()) {
                    if (packet.kind() == GridResourceKind.item()) invalidateIngredients();
                    if (old != null) removeAliases(old);
                    Row row = put(packet.kind(), state.get(packet.kind(), update.serial()));
                    if (old != null && !old.primary.getId().equals(row.primary.getId()))
                        RSGridSearchCache.onGridDelta(screen, this, old.primary);
                    RSGridSearchCache.onGridDelta(screen, this, row.primary);
                    dirty = true;
                } else if (old != null) {
                    long before = old.primary.isCraftable() ? 0 : getQuantity(old.primary);
                    if (update.amount() > 0) {
                        if (old.primary instanceof LongItemGridStack item) item.setZeroed(false);
                        if (old.primary instanceof LongFluidGridStack fluid) fluid.setZeroed(false);
                    }
                    if (!old.primary.isCraftable()) {
                        // 跨过 0 的增减要重排：空资源不占格，有量时才需要出现。
                        if ((before > 0) != (update.amount() > 0)) dirty = true;
                        setQuantity(old.primary, update.amount());
                    }
                    old.primary.setTrackerEntry(update.tracker());
                    if (old.crafting != null) old.crafting.setTrackerEntry(update.tracker());
                    QuantityChange change = new QuantityChange(old.primary, before, update.amount());
                    quantityListeners.forEach(listener -> listener.accept(change));
                    int sorting = screen.getGrid().getSortingType();
                    if (sorting == IGrid.SORTING_TYPE_QUANTITY || sorting == IGrid.SORTING_TYPE_LAST_MODIFIED) dirty = true;
                }
            }
        }
    }

    private Row put(GridResourceKind kind, UnifiedGridEntry entry) {
        IGridStack primary = stack(kind, entry, entry.storedId() == null);
        IGridStack crafting = entry.craftableId() == null ? null : primary.isCraftable() ? primary : stack(kind, entry, true);
        Row row = new Row(kind, entry.serial(), primary, crafting);
        rows.get(kind.id()).put(entry.serial(), row);
        aliases.put(primary.getId(), primary);
        rowIds.put(primary.getId(), row);
        if (crafting != null) aliases.put(crafting.getId(), crafting);
        if (crafting != null) rowIds.put(crafting.getId(), row);
        return row;
    }

    private IGridStack stack(GridResourceKind kind, UnifiedGridEntry entry, boolean crafting) {
        UUID id = crafting ? entry.craftableId() : entry.storedId();
        UUID other = crafting ? entry.storedId() : entry.craftableId();
        IGridStack stack;
        if (kind == GridResourceKind.item()) {
            ItemStack template = ((ItemStack) entry.template()).copyWithCount(1);
            stack = new LongItemGridStack(id, other, template, crafting,
                    entry.tracker(), entry.amount());
        } else if (kind == GridResourceKind.fluid()) {
            FluidStack template = ((FluidStack) entry.template()).copy();
            template.setAmount(1);
            stack = new LongFluidGridStack(id, other, template,
                    entry.tracker(), crafting, entry.amount());
        } else {
            long amount = crafting ? 1 : Math.max(1, entry.amount());
            stack = new MagicStackGridStack(kind.magicKind(), id, other, amount,
                    entry.capacity(), entry.tracker(), crafting);
        }
        if (!crafting && entry.amount() == 0) stack.setQuantity(0);
        return stack;
    }

    private static void setQuantity(IGridStack stack, long quantity) {
        if (stack instanceof MagicStackGridStack magic) magic.setLongQuantity(quantity);
        else if (stack instanceof LongItemGridStack item) item.setLongQuantity(quantity);
        else if (stack instanceof LongFluidGridStack fluid) fluid.setLongQuantity(quantity);
        else stack.setQuantity(clampToInt(quantity));
    }

    private static long getQuantity(IGridStack stack) {
        if (stack instanceof MagicStackGridStack magic) return magic.getLongQuantity();
        if (stack instanceof LongItemGridStack item) return item.getLongQuantity();
        if (stack instanceof LongFluidGridStack fluid) return fluid.getLongQuantity();
        return stack.getQuantity();
    }

    private void removeAliases(Row row) {
        aliases.remove(row.primary.getId());
        rowIds.remove(row.primary.getId());
        if (row.crafting != null) aliases.remove(row.crafting.getId());
        if (row.crafting != null) rowIds.remove(row.crafting.getId());
    }

    public void tick() {
        int tick = Minecraft.getInstance().player == null ? 0 : Minecraft.getInstance().player.tickCount;
        for (GridResourceKind kind : GridResourceKind.values()) {
            if (state.needsResync(kind) && tick - lastResyncTick[kind.id()] >= 20) {
                request(kind, 0, UnifiedGridActionPacket.Action.RESYNC, 0);
                lastResyncTick[kind.id()] = tick;
            }
        }
        if (dirty && tick - lastSortTick >= 5 && screen.canSort()) {
            forceSort();
            lastSortTick = tick;
        }
    }

    public void cycleFilter() {
        filter = (filter + 1) % FILTER_LEVELS;
        UnifiedGridPreferences.saveFilter(preferencesPath, filter, FILTER_LEVELS);
        forceSort();
    }

    /** 当前过滤档位，供侧按钮选图标。 */
    public int filter() { return filter; }

    public Component filterLabel() {
        return Component.translatable(switch (filter) {
            case FILTER_ITEMS -> "rs_integration.unified_grid.filter.items";
            case FILTER_FLUIDS_AND_MAGIC -> "rs_integration.unified_grid.filter.fluids";
            default -> "rs_integration.unified_grid.filter.all";
        });
    }

    public Row row(IGridStack stack) {
        return stack == null ? null : rowIds.get(stack.getId());
    }

    public boolean canEmptyCarried(ItemStack carried) {
        return filter != FILTER_ITEMS && !FluidContainerBucketSupport.getFluid(carried).isEmpty();
    }

    private final Map<UUID, Row> rowIds = new HashMap<>();

    public void request(GridResourceKind kind, int serial, UnifiedGridActionPacket.Action action, int flags) {
        if (state.session() == null || (action != UnifiedGridActionPacket.Action.RESYNC && !state.ready(kind))) return;
        NetworkHandler.CHANNEL.sendToServer(new UnifiedGridActionPacket(screen.getMenu().containerId,
                state.session(), kind, state.epoch(kind), serial, action, flags));
    }

    @Override public List<IGridStack> getStacks() { return visible; }
    @Override public IGridStack get(UUID id) { return aliases.get(id); }
    @Override public Collection<IGridStack> getAllStacks() {
        // JEI 需要库存和可合成两种角色；同一资源在显示列表里只占一格。
        return Collections.unmodifiableCollection(aliases.values());
    }
    @Override public void setStacks(List<IGridStack> stacks) { /* 混合视图仅接受自己的协议。 */ }
    @Override public void postChange(IGridStack stack, int quantity) { /* 拒绝无会话身份的旧包。 */ }
    @Override public void setCanCraft(boolean value) { canCraft = value; }
    @Override public boolean canCraft() { return canCraft; }
    @Override public void sort() { dirty = true; if (screen.canSort()) forceSort(); }
    @Override public void addDeltaListener(Consumer<IGridStack> listener) { listeners.add(listener); }
    // 原版打开合成数量子界面也会调用 removed；菜单还在，必须继续接收库存变化。
    @Override public void removed() { invalidateIngredients(); }

    public void addQuantityListener(Consumer<QuantityChange> listener) { quantityListeners.add(listener); }

    private void invalidateIngredients() {
        IngredientTracker.invalidate();
        listeners.clear();
        quantityListeners.clear();
    }

    @Override public void forceSort() {
        if (!RSGridSearchCache.beforeForceSort(screen, this)) return;
        IGrid grid = screen.getGrid();
        if (!grid.isGridActive()) {
            visible = new ArrayList<>();
            screen.updateScrollbar();
            return;
        }
        List<IFilter> filters = grid.getFilters();
        int tab = grid.getTabSelected();
        List<IGridTab> tabs = grid.getTabs();
        if (tab >= 0 && tab < tabs.size()) filters = tabs.get(tab).getFilters();
        Predicate<IGridStack> search = GridFilterParser.getFilters(null, screen.getSearchFieldText(), List.of());
        List<IGridStack> next = new ArrayList<>();
        for (GridResourceKind kind : GridResourceKind.values()) {
            for (Row row : rows.get(kind.id()).values()) {
                if (!passesFilter(kind)) continue;
                // 数量为 0 的魔法资源不展示：条目本身一直存在，但空资源不该占一格。
                if (kind.isMagic() && row.primary.getQuantity() <= 0) continue;
                IGridStack displayed = grid.getViewType() == IGrid.VIEW_TYPE_CRAFTABLES ? row.crafting : row.primary;
                if (displayed == null || (grid.getViewType() == IGrid.VIEW_TYPE_NON_CRAFTABLES && row.crafting != null)) continue;
                if (search.test(displayed) && UnifiedGridFilters.matches(filters, displayed)) next.add(displayed);
            }
        }
        next.sort(comparator(grid));
        visible = next;
        dirty = false;
        screen.updateScrollbar();
    }

    /** 当前档位是否展示该类别。 */
    private boolean passesFilter(GridResourceKind kind) {
        return switch (filter) {
            case FILTER_ITEMS -> kind.isItem();
            case FILTER_FLUIDS_AND_MAGIC -> !kind.isItem();
            default -> true;
        };
    }

    private Comparator<IGridStack> comparator(IGrid grid) {
        SortingDirection direction = grid.getSortingDirection() == IGrid.SORTING_DIRECTION_DESCENDING
                ? SortingDirection.DESCENDING : SortingDirection.ASCENDING;
        int type = grid.getSortingType();
        Comparator<IGridStack> compare;
        if (type == IGrid.SORTING_TYPE_QUANTITY) {
            Comparator<IGridStack> quantities = Comparator.comparingInt(IGridStack::getQuantity);
            if (direction == SortingDirection.DESCENDING) quantities = quantities.reversed();
            compare = Comparator.comparingInt(this::kindId).thenComparing(quantities);
        } else if (type == IGrid.SORTING_TYPE_ID) {
            // 类型分组固定，组内复用 RS 原生注册表整数 ID 排序及方向规则。
            IGridSorter ids = new IdGridSorter();
            compare = Comparator.comparingInt(this::kindId)
                    .thenComparing((left, right) -> ids.compare(left, right, direction));
        } else {
            compare = (left, right) -> GridScreen.getDefaultSorter().compare(left, right, direction);
            for (IGridSorter sorter : GridScreen.getSorters()) if (sorter.isApplicable(grid))
                compare = ((Comparator<IGridStack>) (left, right) -> sorter.compare(left, right, direction)).thenComparing(compare);
        }
        return compare.thenComparingInt(this::kindId).thenComparing(this::registryName).thenComparing(IGridStack::getId);
    }

    /** 排序分组序号；未知条目排在最后。 */
    private int kindId(IGridStack stack) {
        Row row = rowIds.get(stack.getId());
        return row == null ? GridResourceKind.count() : row.kind().id();
    }

    /** 稳定身份；协议只在会话内比较类别编号，跨端比较用注册名。 */
    private String registryName(IGridStack stack) {
        Object ingredient = stack.getIngredient();
        if (ingredient instanceof ItemStack item) {
            return item.getItem().builtInRegistryHolder().key().location().toString();
        }
        if (ingredient instanceof FluidStack fluid) {
            return fluid.getFluid().builtInRegistryHolder().key().location().toString();
        }
        if (ingredient instanceof MagicResourceStack magic) {
            return "rs_integration:" + magic.kind().path();
        }
        return stack.getId().toString();
    }

    public record Row(GridResourceKind kind, int serial, IGridStack primary, IGridStack crafting) { }
    public record QuantityChange(IGridStack stack, long before, long after) { }
}
