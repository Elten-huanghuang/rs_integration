package com.huanghuang.rsintegration.feature.goety.menu;

import com.Polarice3.Goety.common.blocks.BrewCauldronBlock;
import com.Polarice3.Goety.common.blocks.entities.BrewCauldronBlockEntity;
import com.Polarice3.Goety.common.items.ModItems;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.feature.goety.GoetyCauldronFeature;
import com.huanghuang.rsintegration.network.gui.RemoteGuiAuth;
import com.huanghuang.rsintegration.api.storage.*;
import com.huanghuang.rsintegration.feature.goety.brewing.*;
import com.huanghuang.rsintegration.feature.goety.integration.*;
import com.huanghuang.rsintegration.feature.goety.network.BrewNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/** 所有编辑与实际物品操作均由服务端菜单校验。 */
public final class WitchCauldronMenu extends AbstractContainerMenu {
    /** 紧凑的分步布局：目录每次只显示一个步骤，玩家物品栏固定在下方。 */
    public static final int WIDTH = 380, HEIGHT = 282;
    private final SimpleContainer containers = new SimpleContainer(3);
    private final BrewCauldronBlockEntity target;
    private final BlockPos position;
    private final boolean remote;
    private BrewCatalog catalog;
    private BrewPlan plan;
    private List<ResourceLocation> selected = List.of();
    private int capacityTier = -1, revision, ticks;
    private long requestTick = Long.MIN_VALUE;
    private int requestsThisTick;
    private Component message = text("choose");
    private MissingMaterialOrders orders;
    private boolean sentCatalog;
    private ServerPlayer owner;
    private Map<ResourceLocation, Long> plannedStock = Map.of();
    public CompoundTag clientState = new CompoundTag();
    public List<BrewCatalog.Entry> clientCatalog = List.of();
    public int clientRevision;

    private WitchCauldronMenu(int id, Inventory inventory, BlockPos pos, BrewCauldronBlockEntity target, boolean remote) {
        super(GoetyRsiMenus.WITCH_CAULDRON.get(), id);
        this.position = pos.immutable(); this.target = target; this.remote = remote;
        addSlot(new Slot(containers, 0, 18, 44) {
            @Override public boolean mayPlace(ItemStack stack) { return NativeCauldronActions.water(stack); }
        });
        addSlot(new Slot(containers, 1, 18, 86) {
            @Override public boolean mayPlace(ItemStack stack) { return stack.is(Items.GLASS_BOTTLE); }
        });
        addSlot(new Slot(containers, 2, 65, 86) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        });
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, 9 + col + row * 9, 100 + col * 18, 190 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 100 + col * 18, 244));
        owner = inventory.player instanceof ServerPlayer server ? server : null;
        if (target != null) {
            catalog = BrewCatalog.discover(target.getLevel()); orders = new MissingMaterialOrders(); rebuild();
        }
    }
    public static WitchCauldronMenu server(int id, Inventory inv, BrewCauldronBlockEntity be) { return server(id, inv, be, true); }
    public static WitchCauldronMenu server(int id, Inventory inv, BrewCauldronBlockEntity be, boolean remote) {
        return new WitchCauldronMenu(id, inv, be.getBlockPos(), be, remote);
    }
    public static WitchCauldronMenu client(int id, Inventory inv, BlockPos pos) { return new WitchCauldronMenu(id, inv, pos, null, false); }
    public BlockPos position() { return position; }
    public BrewPlan plan() { return plan; }
    public boolean isRemote() { return remote; }
    private Map<ResourceLocation, Long> stockCounts(ServerPlayer player, StorageLink link) {
        var wanted = new HashSet<Item>(); catalog.entries().forEach(e -> wanted.add(e.stack().getItem()));
        var snapshot = player == null ? Optional.<StorageSnapshot>empty() : RsiStorageLink.snapshot(player, link, wanted);
        var result = new HashMap<ResourceLocation, Long>();
        for (var e : catalog.entries()) result.put(e.item(), player == null ? 0L
                : inventoryCount(player, e.stack()) + RsiStorageLink.countSnapshot(link, snapshot, e.stack()));
        return Map.copyOf(result);
    }
    private void rebuild() {
        var link = owner == null ? null : RsiStorageLink.resolve(owner, target).orElse(null);
        rebuild(stockCounts(owner, link));
    }
    private void rebuild(Map<ResourceLocation, Long> stock) {
        ToLongFunction<BrewCatalog.Entry> counts = e -> stock.getOrDefault(e.item(), 0L);
        plan = capacityTier < 0 ? BrewPlan.recommend(target.getLevel(), position, target.getBlockState(), catalog, selected, counts)
                : BrewPlan.create(target.getLevel(), position, target.getBlockState(), catalog, capacityTier, selected, counts);
        plannedStock = stock;
    }
    public static Component text(String key) { return Component.translatable("gui.rs_integration." + key); }

    @Override public boolean stillValid(Player player) {
        if (target == null) return true;
        if (!RSIntegrationConfig.ENABLE_GOETY.get()) return false;
        var level = target.getLevel();
        if (player.isSpectator() || level == null || target.isRemoved() || !level.hasChunkAt(position)
                || level.getBlockEntity(position) != target || !level.mayInteract(player, position)) return false;
        if (remote) return player instanceof ServerPlayer server && RemoteGuiAuth.isAuthorized(server, this);
        return player.level() == level && player.distanceToSqr(position.getX() + .5, position.getY() + .5, position.getZ() + .5) <= 64;
    }
    public void handle(ServerPlayer player, BrewNetwork.Edit edit) {
        if (!stillValid(player) || edit.revision() <= revision || edit.capacity() < -1 || edit.capacity() > 32 || edit.selected().size() > 64) return;
        long now = player.serverLevel().getGameTime();
        if (requestTick != now) { requestTick = now; requestsThisTick = 0; }
        if (++requestsThisTick > 8) { revision = edit.revision(); sync(player); return; }
        revision = edit.revision();
        try {
            if (orders.busy() && (capacityTier != edit.capacity() || !selected.equals(edit.selected()))) {
                message = text("orders_busy"); sync(player); return;
            }
            if (capacityTier != edit.capacity() || !selected.equals(edit.selected())) {
                capacityTier = edit.capacity(); selected = List.copyOf(edit.selected()); rebuild();
            }
            var link = RsiStorageLink.resolve(player, target).orElse(null);
            switch (edit.action()) {
                case "preview" -> message = plan.valid() ? text("preview_ready") : plan.error();
                case "bind" -> { message = text(RsiStorageLink.bind(player, target) ? "bound" : "bind_failed"); rebuild(); }
                case "unbind" -> { if (!orders.busy()) { RsiStorageLink.unbind(target); rebuild(); message = text("unbound"); } }
                case "water" -> addWater(player);
                case "bottle" -> bottle(player);
                case "clear" -> {
                    if (target.mode == BrewCauldronBlockEntity.Mode.FAILED && !orders.busy()) {
                        target.mode = target.reset();
                        target.update = 0;
                        target.getLevel().setBlockAndUpdate(position, target.getBlockState().setValue(BrewCauldronBlock.LEVEL, 0));
                        target.markUpdated(); message = text("cleared");
                    }
                }
                case "feed" -> feed(player, link);
                case "stir" -> stir(player, link);
                case "craft" -> {
                    if (!orders.busy()) rebuild();
                    if (!plan.valid()) message = plan.error();
                    else if (link == null || !RsiStorageLink.isCurrentDefault(player, link)) message = text("bind_failed");
                    else message = orders.startAlternatives(player, materialNeeds(), link);
                }
                default -> { }
            }
        } catch (RuntimeException | LinkageError failure) {
            GoetyCauldronFeature.LOGGER.error("Cauldron action {} failed at {}", edit.action(), position, failure);
            message = text("action_failed");
        }
        sync(player);
    }
    private void addWater(ServerPlayer player) {
        var stack = containers.getItem(0);
        if (!NativeCauldronActions.water(stack)) { message = text("need_water_container"); return; }
        if (target.mode != BrewCauldronBlockEntity.Mode.IDLE && target.mode != BrewCauldronBlockEntity.Mode.FAILED) { message = text("busy"); return; }
        if (target.getBlockState().getValue(BrewCauldronBlock.LEVEL) >= 3) { message = text("water_full"); return; }
        var result = NativeCauldronActions.useContainer(player, target, stack.copyWithCount(1));
        stack.shrink(1);
        RsiStorageLink.give(player, result); containers.setChanged(); message = text("water_added");
    }
    private void bottle(ServerPlayer player) {
        if (!containers.getItem(2).isEmpty()) { message = text("take_output"); return; }
        if (!containers.getItem(1).is(Items.GLASS_BOTTLE)) { message = text("need_bottle"); return; }
        if (target.getBlockState().getValue(BrewCauldronBlock.LEVEL) <= 0 || GoetyCauldronCompat.isBusy(target)) {
            message = text("not_finished"); return;
        }
        if (GoetyCauldronCompat.isCrafted(target)) { message = text("not_bottled_recipe"); return; }
        if (target.mode != BrewCauldronBlockEntity.Mode.IDLE && target.mode != BrewCauldronBlockEntity.Mode.COMPLETED) {
            message = text("not_finished"); return;
        }
        var result = NativeCauldronActions.useContainer(player, target, containers.getItem(1).copyWithCount(1));
        containers.removeItem(1, 1); containers.setItem(2, result);
        message = text("bottled");
    }
    public List<ItemStack> requirements() {
        var result = new ArrayList<ItemStack>();
        if (plan == null) return result;
        for (var stack : plan.sequence()) {
            var match = result.stream().filter(s -> ItemStack.isSameItemSameTags(s, stack)).findFirst();
            if (match.isPresent()) match.get().grow(stack.getCount()); else result.add(stack.copy());
        }
        return result;
    }
    public List<MissingMaterialOrders.MaterialNeed> materialNeeds() {
        var grouped = new LinkedHashMap<String, MissingMaterialOrders.MaterialNeed>();
        if (plan == null || !plan.valid()) return List.of();
        for (int i = 0; i < plan.inputs().size(); i++) {
            var rule = plan.inputs().get(i);
            var candidates = catalog.alternatives(rule).stream().map(BrewCatalog.Entry::stack).toList();
            String key = candidates.stream().map(stack -> ForgeRegistries.ITEMS.getKey(stack.getItem()).toString())
                    .sorted().collect(Collectors.joining("|"));
            int amount = plan.sequence().get(i).getCount() + (grouped.containsKey(key) ? grouped.get(key).amount() : 0);
            grouped.put(key, new MissingMaterialOrders.MaterialNeed(candidates, amount));
        }
        return List.copyOf(grouped.values());
    }
    public static int inventoryCount(ServerPlayer player, ItemStack item) {
        int count = 0;
        for (var stack : player.getInventory().items) if (ItemStack.isSameItemSameTags(stack, item)) count += stack.getCount();
        return count;
    }
    private void feed(ServerPlayer player, StorageLink link) {
        if (orders.busy()) { message = text("orders_busy"); return; }
        rebuild(); // 扣取真实材料前，重新检查群系、基座和数据包规则。
        if (!plan.valid()) { message = plan.error(); return; }
        if (target.mode != BrewCauldronBlockEntity.Mode.IDLE || !target.container.stream().allMatch(ItemStack::isEmpty)) { message = text("need_empty"); return; }
        if (target.getBlockState().getValue(BrewCauldronBlock.LEVEL) != 3) { message = text("need_full_water"); return; }
        if (!target.isHeated()) { message = text("need_heat"); return; }
        if (NativeCauldronActions.availableSouls(target) < Math.max(1, plan.soulCost())) { message = text("need_souls"); return; }
        var needs = requirements();
        for (var need : needs) if (inventoryCount(player, need) + RsiStorageLink.count(player, link, need) < need.getCount()) {
            message = Component.translatable("gui.rs_integration.missing_item", need.getHoverName()); return;
        }
        var reserved = new ArrayList<ItemStack>();
        try {
            for (var need : needs) {
                int missing = need.getCount();
                for (var item : player.getInventory().items) if (missing > 0 && ItemStack.isSameItemSameTags(item, need)) {
                    var part = item.split(Math.min(missing, item.getCount())); missing -= part.getCount(); reserved.add(part);
                }
                if (missing > 0) {
                    var extracted = link == null ? List.<ItemStack>of() : RsiStorageLink.extract(player, link, need, missing);
                    if (extracted.isEmpty()) { message = text("storage_changed"); return; }
                    reserved.addAll(extracted);
                }
            }
            var before = target.saveWithFullMetadata();
            var blockBefore = target.getBlockState();
            boolean committed = false;
            try {
                for (var step : plan.sequence()) {
                    target.mode = target.insertItem(step.copy());
                    if (target.mode == BrewCauldronBlockEntity.Mode.FAILED) { message = text("native_failed"); return; }
                }
                committed = true;
            } finally {
                if (!committed) {
                    target.getLevel().setBlockAndUpdate(position, blockBefore);
                    target.load(before); target.markUpdated();
                }
            }
            reserved.clear(); // 预留材料已全部转交给当前坩埚。
            for (var step : plan.sequence()) if (step.hasCraftingRemainingItem()) RsiStorageLink.give(player, step.getCraftingRemainingItem());
            target.markUpdated();
            player.getInventory().setChanged(); message = text("fed");
        } finally {
            reserved.stream().filter(s -> !s.isEmpty()).forEach(s -> RsiStorageLink.give(player, s));
            player.getInventory().setChanged();
        }
    }
    private void stir(ServerPlayer player, StorageLink link) {
        var ladle = new ItemStack(ModItems.CAULDRON_LADLE.get());
        boolean tool = RsiStorageLink.bound(target) ? link != null && RsiStorageLink.hasTool(player, link, ladle.getItem())
                : player.getMainHandItem().is(ladle.getItem()) || player.getOffhandItem().is(ladle.getItem());
        if (!tool) { message = text(RsiStorageLink.bound(target) ? "need_network_ladle" : "need_ladle"); return; }
        if (!target.isHeated() || target.getBlockState().getValue(BrewCauldronBlock.LEVEL) < 3) { message = text("need_heat_water"); return; }
        if (NativeCauldronActions.availableSouls(target) < Math.max(1, target.totalCost - target.soulTime)) { message = text("need_souls"); return; }
        GoetyCauldronCompat.brew(target, player);
        message = text(GoetyCauldronCompat.isBusy(target) ? "started" : "cannot_start");
    }
    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (target != null && ++ticks % 10 == 0 && owner != null && owner.containerMenu == this && stillValid(owner)) {
            try { orders.tick(owner); }
            catch (RuntimeException | LinkageError failure) {
                GoetyCauldronFeature.LOGGER.error("RSI material order failed at {}", position, failure);
                orders.close(owner); message = text("order_failed");
            }
            var orderMessage = orders.takeMessage();
            if (orderMessage != null) message = orderMessage;
            sync(owner);
        }
    }
    public void sync(ServerPlayer player) {
        owner = player;
        var link = RsiStorageLink.resolve(player, target).orElse(null);
        var counts = stockCounts(player, link);
        if (!orders.busy() && target.mode == BrewCauldronBlockEntity.Mode.IDLE && !counts.equals(plannedStock)) rebuild(counts);
        var data = new CompoundTag();
        data.putInt("revision", revision); data.putInt("capacityTier", capacityTier);
        data.putInt("recommendedTier", plan.capacityTier());
        data.putString("message", Component.Serializer.toJson(message));
        data.putInt("water", Math.min(3, target.getBlockState().getValue(BrewCauldronBlock.LEVEL)));
        data.putString("mode", target.mode.name()); data.putBoolean("heated", target.isHeated());
        data.putBoolean("bound", RsiStorageLink.bound(target)); data.putBoolean("ordersBusy", orders.busy());
        data.putBoolean("online", link != null);
        data.putInt("capacity", plan.capacity()); data.putInt("used", plan.used()); data.putInt("souls", plan.soulCost());
        data.putBoolean("valid", plan.valid()); data.put("output", plan.output().save(new CompoundTag()));
        var selection = new ListTag(); selected.forEach(id -> selection.add(StringTag.valueOf(id.toString()))); data.put("selected", selection);
        var needs = new ListTag();
        for (var need : requirements()) {
            var tag = need.save(new CompoundTag());
            long available = counts.getOrDefault(ForgeRegistries.ITEMS.getKey(need.getItem()), 0L);
            tag.putLong("available", available); tag.putInt("needed", need.getCount()); needs.add(tag);
        }
        data.put("needs", needs);
        var stocks = new CompoundTag();
        for (var e : catalog.entries()) {
            stocks.putLong(e.item().toString(), counts.getOrDefault(e.item(), 0L));
        }
        data.put("stocks", stocks);
        if (!sentCatalog) {
            var entries = new ListTag();
            for (var e : catalog.entries()) {
                var tag = new CompoundTag(); tag.putString("item", e.item().toString()); tag.putInt("kind", e.kind());
                tag.putString("group", e.group()); tag.putInt("level", e.level()); tag.putString("name", Component.Serializer.toJson(e.name()));
                tag.putString("equivalence", e.equivalence()); tag.putString("family", e.family()); tag.putString("ingredient", e.ingredient());
                tag.putBoolean("familyAlternatives", e.familyAlternatives()); entries.add(tag);
            }
            data.put("catalog", entries); sentCatalog = true;
        }
        BrewNetwork.sync(player, containerId, data);
    }
    public void accept(CompoundTag data) {
        if (data == null) return;
        if (data.contains("catalog")) {
            var entries = new ArrayList<BrewCatalog.Entry>();
            for (var tag : data.getList("catalog", Tag.TAG_COMPOUND)) {
                var e = (CompoundTag) tag;
                entries.add(new BrewCatalog.Entry(ResourceLocation.parse(e.getString("item")), e.getInt("kind"), e.getString("group"),
                        e.getInt("level"), Component.Serializer.fromJson(e.getString("name")), e.getString("equivalence"), e.getString("family"), e.getString("ingredient"), e.getBoolean("familyAlternatives")));
            }
            clientCatalog = List.copyOf(entries);
        }
        if (data.getInt("revision") >= clientRevision) clientState = data;
    }
    @Override public void removed(Player player) {
        super.removed(player);
        if (player instanceof ServerPlayer server) {
            clearContainer(player, containers);
            if (orders != null) orders.close(server);
            if (remote) RemoteGuiAuth.deauthorize(player.getUUID(), this);
        }
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        var slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        var stack = slot.getItem(); var copy = stack.copy();
        if (index < 3) { if (!moveItemStackTo(stack, 3, slots.size(), true)) return ItemStack.EMPTY; }
        else if (NativeCauldronActions.water(stack)) { if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY; }
        else if (stack.is(Items.GLASS_BOTTLE)) { if (!moveItemStackTo(stack, 1, 2, false)) return ItemStack.EMPTY; }
        else if (index < 30) { if (!moveItemStackTo(stack, 30, 39, false)) return ItemStack.EMPTY; }
        else if (!moveItemStackTo(stack, 3, 30, false)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged(); slot.onTake(player, stack);
        return copy;
    }
}
