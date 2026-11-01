package com.huanghuang.rsintegration.feature.goety.menu;

import com.Polarice3.Goety.common.blocks.BrewCauldronBlock;
import com.Polarice3.Goety.common.blocks.entities.BrewCauldronBlockEntity;
import com.Polarice3.Goety.common.items.ModItems;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.feature.goety.GoetyCauldronFeature;
import com.huanghuang.rsintegration.mods.common.MachineWaterSupply;
import com.huanghuang.rsintegration.mods.goety.magic.GoetySoulCraftingTask;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.consumer.ConsumerPlanningPreferences;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftingPlanningTimeoutException;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponsePublisher;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
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
    private int materialPreviewRevision = -1;
    private Component message = text("choose");
    private MissingMaterialOrders orders;
    private boolean pendingBrew;
    private StorageLink brewingStorage;
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
        var link = owner == null ? null : RsiStorageLink.resolve(owner).orElse(null);
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
        if (!RSIntegrationConfig.isGoetyCauldronEnabled()) return false;
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
        materialPreviewRevision = -1;
        try {
            if (pendingBrew || orders.busy()) {
                message = text("orders_busy"); sync(player); return;
            }
            if (capacityTier != edit.capacity() || !selected.equals(edit.selected())) {
                capacityTier = edit.capacity(); selected = List.copyOf(edit.selected()); rebuild();
            }
            var link = RsiStorageLink.resolve(player).orElse(null);
            switch (edit.action()) {
                case "preview" -> message = plan.valid() ? text("preview_ready") : plan.error();
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
                case "brew" -> brew(player, link);
                default -> { }
            }
        } catch (RuntimeException | LinkageError failure) {
            pendingBrew = false;
            orders.close(player);
            GoetyCauldronFeature.LOGGER.error("Cauldron action {} failed at {}", edit.action(), position, failure);
            message = text("action_failed");
        }
        sync(player);
    }
    private void addWater(ServerPlayer player) {
        if (target.mode != BrewCauldronBlockEntity.Mode.IDLE
                || GoetyCauldronCompat.hasIngredients(target)) { message = text("busy"); return; }
        int water = target.getBlockState().getValue(BrewCauldronBlock.LEVEL);
        if (water >= 3) { message = text("water_full"); return; }
        boolean free = MachineWaterSupply.isFree("goety_witch_cauldron");
        var link = free ? null : RsiStorageLink.resolve(player).orElse(null);
        if (MachineWaterSupply.fillProperty("goety_witch_cauldron", ((3 - water) * 1000 + 2) / 3,
                free ? null : RsiStorageLink.endpoint(player, link), player, () -> {
                    boolean updated = target.getLevel().setBlockAndUpdate(position,
                            target.getBlockState().setValue(BrewCauldronBlock.LEVEL, 3));
                    if (updated) { target.update = 0; target.markUpdated(); }
                    return updated;
                })) {
            message = text("water_added");
            return;
        }
        // 关闭免费补水且网络缺水时，仍允许使用槽位或背包中的原生水容器。
        while (target.getBlockState().getValue(BrewCauldronBlock.LEVEL) < 3) {
            var stack = containers.getItem(0);
            if (!NativeCauldronActions.water(stack)) {
                stack = player.getInventory().items.stream().filter(NativeCauldronActions::water).findFirst().orElse(ItemStack.EMPTY);
            }
            if (stack.isEmpty()) { message = text("need_water_supply"); return; }
            int before = target.getBlockState().getValue(BrewCauldronBlock.LEVEL);
            var result = NativeCauldronActions.useContainer(player, target, stack.copyWithCount(1));
            if (target.getBlockState().getValue(BrewCauldronBlock.LEVEL) <= before) { message = text("need_water_supply"); return; }
            stack.shrink(1);
            RsiStorageLink.give(player, result);
            containers.setChanged();
            player.getInventory().setChanged();
        }
        message = text("water_added");
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
        return RsiStorageLink.inventoryCount(player, item);
    }
    private boolean readyToBrew(ServerPlayer player, StorageLink link) {
        if (!plan.valid()) { message = plan.error(); return false; }
        if (target.mode != BrewCauldronBlockEntity.Mode.IDLE || GoetyCauldronCompat.isBusy(target)
                || GoetyCauldronCompat.hasIngredients(target)) { message = text("need_empty"); return false; }
        if (target.getBlockState().getValue(BrewCauldronBlock.LEVEL) != 3) { message = text("need_full_water"); return false; }
        if (!target.isHeated()) { message = text("need_heat"); return false; }
        if (!hasLadle(player, link)) { message = text("need_ladle"); return false; }
        var endpoint = RsiStorageLink.endpoint(player, link);
        long souls = SaturatingLongMath.add(GoetySoulCraftingTask.nativeAvailableBrazier(player, target),
                Math.max(0, GoetySoulCraftingTask.availableInEndpoint(player, endpoint)));
        if (souls < Math.max(1, plan.soulCost())) { message = text("need_souls"); return false; }
        return true;
    }
    private boolean hasLadle(ServerPlayer player, StorageLink link) {
        var ladle = ModItems.CAULDRON_LADLE.get();
        return player.getInventory().items.stream().anyMatch(stack -> stack.is(ladle))
                || player.getOffhandItem().is(ladle)
                || link != null && RsiStorageLink.hasTool(player, link, ladle);
    }
    private void brew(ServerPlayer player, StorageLink link) {
        rebuild();
        if (!readyToBrew(player, link)) return;
        if (requirements().stream().allMatch(need ->
                inventoryCount(player, need) + RsiStorageLink.count(player, link, need) >= need.getCount())) {
            feedAndBrew(player, link);
            return;
        }
        if (link == null || !RsiStorageLink.isCurrentDefault(player, link)) { message = text("bind_failed"); return; }
        materialPreviewRevision = revision;
        PlanResponsePublisher.send(player, previewMaterials(player, link, Map.of(), Map.of(),
                PlanningPreferences.empty()), 0L);
    }
    private PlanResponse previewMaterials(ServerPlayer player, StorageLink link,
                                          Map<String, String> forced, Map<String, ItemStack> locks,
                                          PlanningPreferences preferences) {
        var endpoint = RsiStorageLink.endpoint(player, link);
        return BrewMaterialPlan.create(BrewMaterialPlan.targetId(containerId, revision),
                materialNeeds(), plan.output(), MaterialSources.listAllAvailableFresh(player, endpoint),
                player.serverLevel(), player, endpoint, forced, locks, preferences);
    }
    public void handlePlan(ServerPlayer player, BrewNetwork.PlanAction request) {
        if (!stillValid(player) || request.revision() != revision || materialPreviewRevision != revision) {
            failPreview(player, request, text("storage_changed"));
            return;
        }
        long now = player.serverLevel().getGameTime();
        if (requestTick != now) { requestTick = now; requestsThisTick = 0; }
        if (++requestsThisTick > 8 || pendingBrew || orders.busy()) {
            failPreview(player, request, text("orders_busy"));
            return;
        }
        try {
            rebuild();
            var link = RsiStorageLink.resolve(player).orElse(null);
            if (!readyToBrew(player, link)) {
                failPreview(player, request, message);
                return;
            }
            var preview = previewMaterials(player, link, request.forced(), request.locks(), request.preferences());
            if (request.preview()) {
                PlanResponsePublisher.send(player, preview, request.requestId());
                return;
            }
            // 确认时重新规划整批需求，缺基础原料的预览不能变成可执行订单。
            if (!preview.success() || preview.executionBlocked()) {
                message = text("storage_changed");
                return;
            }
            materialPreviewRevision = -1;
            if (link == null || !RsiStorageLink.isCurrentDefault(player, link)) {
                message = text("bind_failed");
                return;
            }
            startMaterialOrders(player, link, request, preview);
        } catch (CraftingPlanningTimeoutException timeout) {
            message = Component.translatable("rsi.plan.failure.planning_timeout");
            failPreview(player, request, message);
        } catch (RuntimeException | LinkageError failure) {
            GoetyCauldronFeature.LOGGER.error("Cauldron material planning failed at {}", position, failure);
            pendingBrew = false;
            orders.close(player);
            message = text("action_failed");
            failPreview(player, request, message);
        } finally {
            sync(player);
        }
    }
    private void failPreview(ServerPlayer player, BrewNetwork.PlanAction request, Component reason) {
        if (request.preview()) PlanResponsePublisher.send(player,
                new PlanResponse(false, "", ItemStack.EMPTY, List.of(), Map.of(), List.of(),
                        "", null, null, 0, 0, 0, List.of(reason), 1), request.requestId());
    }
    private void startMaterialOrders(ServerPlayer player, StorageLink link, BrewNetwork.PlanAction request,
                                     PlanResponse preview) {
        var forced = new LinkedHashMap<String, ResourceLocation>();
        request.preferences().recipeChoices().forEach((key, choice) ->
                forced.put(key, ResourceLocation.parse(choice.serialize())));
        request.forced().forEach((key, value) -> {
            var recipe = ResourceLocation.tryParse(value);
            if (recipe != null) forced.put(key, recipe);
        });
        // 已确认的整批树决定实际分支，逐项下单不能悄悄改选第一条可用配方。
        for (var node : preview.graph().nodes()) {
            var key = CraftingResolver.preferenceKey(node.primaryOutput());
            if (key != null) forced.put(key.toString(), node.recipeId());
        }
        var locks = new LinkedHashMap<String, IntegrationResourceKey>();
        request.preferences().ingredientChoices().forEach((key, stack) ->
                locks.put(key, IntegrationResourceKey.item(stack)));
        request.locks().forEach((key, stack) -> locks.put(key, IntegrationResourceKey.item(stack)));
        var preferences = new ConsumerPlanningPreferences(forced, locks, request.preferences().excludedRecipes());
        message = orders.startAlternatives(player, materialNeeds(), link, preferences);
        pendingBrew = orders.busy() || orders.succeeded();
        brewingStorage = link;
        if (pendingBrew && !orders.busy()) continueBrewing(player);
    }
    private void continueBrewing(ServerPlayer player) {
        if (!pendingBrew || orders.busy()) return;
        pendingBrew = false;
        if (!orders.succeeded()) return;
        if (!RsiStorageLink.isCurrentDefault(player, brewingStorage)) { message = text("bind_failed"); return; }
        // 递归材料到货后重新匹配替代品，并复核环境，不能沿用下单时的库存方案。
        rebuild();
        feedAndBrew(player, brewingStorage);
    }
    private void feedAndBrew(ServerPlayer player, StorageLink link) {
        rebuild();
        if (!readyToBrew(player, link)) return;
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
            GoetySoulCraftingTask soulTask = null;
            try {
                for (var step : plan.sequence()) {
                    target.mode = target.insertItem(step.copy());
                    if (target.mode == BrewCauldronBlockEntity.Mode.FAILED) { message = text("native_failed"); return; }
                }
                soulTask = GoetySoulCraftingTask.reserveCauldron(player, RsiStorageLink.endpoint(player, link),
                        Math.max(1, target.getBrewCost()));
                if (soulTask == null || !soulTask.prepareBeforeStart(target)) { message = text("need_souls"); return; }
                if (!NativeCauldronActions.stir(player, target)) { message = text("cannot_start"); return; }
                CauldronSoulAutomation.retain(target, player, soulTask);
                committed = true;
            } finally {
                if (!committed) {
                    if (soulTask != null) soulTask.close();
                    target.getLevel().setBlockAndUpdate(position, blockBefore);
                    target.load(before); target.markUpdated();
                }
            }
            reserved.clear(); // 预留材料已全部转交给当前坩埚。
            for (var step : plan.sequence()) if (step.hasCraftingRemainingItem()) RsiStorageLink.give(player, step.getCraftingRemainingItem());
            target.markUpdated();
            player.getInventory().setChanged(); message = text("started");
        } finally {
            reserved.stream().filter(s -> !s.isEmpty()).forEach(s -> RsiStorageLink.give(player, s));
            player.getInventory().setChanged();
        }
    }
    @Override public void broadcastChanges() {
        if (target != null && owner != null && owner.containerMenu == this
                && !RSIntegrationConfig.isGoetyCauldronEnabled()) {
            // 关闭菜单会返还暂存物品，并取消尚未完成的缺料订单。
            owner.closeContainer();
            return;
        }
        super.broadcastChanges();
        if (target != null && ++ticks % 10 == 0 && owner != null && owner.containerMenu == this && stillValid(owner)) {
            try {
                orders.tick(owner);
                var orderMessage = orders.takeMessage();
                if (orderMessage != null) message = orderMessage;
                continueBrewing(owner);
            }
            catch (RuntimeException | LinkageError failure) {
                GoetyCauldronFeature.LOGGER.error("RSI material order failed at {}", position, failure);
                pendingBrew = false; orders.close(owner); message = text("order_failed");
            }
            sync(owner);
        }
    }
    public void sync(ServerPlayer player) {
        owner = player;
        var link = RsiStorageLink.resolve(player).orElse(null);
        var counts = stockCounts(player, link);
        if (!orders.busy() && target.mode == BrewCauldronBlockEntity.Mode.IDLE && !counts.equals(plannedStock)) rebuild(counts);
        var data = new CompoundTag();
        data.putInt("revision", revision); data.putInt("capacityTier", capacityTier);
        data.putInt("recommendedTier", plan.capacityTier());
        data.putString("message", Component.Serializer.toJson(message));
        data.putInt("water", Math.min(3, target.getBlockState().getValue(BrewCauldronBlock.LEVEL)));
        data.putString("mode", target.mode.name()); data.putBoolean("heated", target.isHeated());
        data.putBoolean("ordersBusy", orders.busy() || pendingBrew);
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
            pendingBrew = false;
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
