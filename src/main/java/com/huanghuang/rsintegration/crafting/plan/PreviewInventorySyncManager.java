package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.batch.CraftPlanningRuntime;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.storage.StorageFluidKey;
import com.huanghuang.rsintegration.storage.StorageItemChangeListener;
import com.huanghuang.rsintegration.storage.StorageItemSubscription;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 服务端线程持有的预览订阅。网络事件只记脏项，tick 末统一更新提示，不调用求解器。 */
public final class PreviewInventorySyncManager {
    private static final Map<SessionKey, Session> SESSIONS = new LinkedHashMap<>();
    private static final Map<UUID, Long> LAST_WATCH_IDS = new HashMap<>();

    private record SessionKey(UUID playerId, long requestId) {}

    private PreviewInventorySyncManager() {}

    public static boolean enabled() {
        var setting = RSIntegrationConfig.CRAFTING_PREVIEW_LIVE_INVENTORY;
        return RSIntegrationConfig.SERVER_SPEC.isLoaded() ? setting.get() : setting.getDefault();
    }

    /** 按玩家和请求保留已发送的结果；仅当前打开的窗口挂原生监听器。 */
    public static void offer(ServerPlayer player, PlanResponse plan, long requestId) {
        if (!enabled() || plan.graph() == null || plan.recipeId().isEmpty()) return;
        offer(player.getUUID(), plan, requestId, player.getServer().getTickCount());
        prune(player.getServer().getTickCount(),
                RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PREVIEW_INVENTORY_PENDING_TICKS),
                RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PLAN_CACHE_MAX_ENTRIES),
                RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PLAN_CACHE_MAX_NODES));
    }

    static Session offer(UUID playerId, PlanResponse plan, long requestId, int tick) {
        SessionKey key = new SessionKey(playerId, requestId);
        Session existing = SESSIONS.get(key);
        if (existing != null) return existing;
        try {
            Session next = new Session(plan, requestId, tick);
            SESSIONS.put(key, next);
            return next;
        } catch (IllegalArgumentException unsupported) {
            // 旧/特殊图若无法给出完整初始材料表，继续使用原预览，不能猜测材料分配。
            RSIntegrationMod.LOGGER.debug("[RSI-preview] Inventory projection unavailable", unsupported);
            return null;
        }
    }

    public static void watch(ServerPlayer player, PlanInventoryWatchPacket packet) {
        if (!enabled()) return;
        watch(player.getUUID(), packet, player.getServer().getTickCount(),
                update -> NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), update),
                () -> CraftPlanningRuntime.publishedPlan(player.getUUID(), packet.requestId()));
    }

    static void watch(UUID playerId, PlanInventoryWatchPacket packet, int tick,
                      Consumer<PlanInventoryUpdatePacket> sender) {
        watch(playerId, packet, tick, sender, () -> null);
    }

    static void watch(UUID playerId, PlanInventoryWatchPacket packet, int tick,
                      Consumer<PlanInventoryUpdatePacket> sender, Supplier<PlanResponse> retainedPlan) {
        Session state = SESSIONS.get(new SessionKey(playerId, packet.requestId()));
        if (packet.open()) {
            if (packet.watchId() <= LAST_WATCH_IDS.getOrDefault(playerId, 0L)) return;
            LAST_WATCH_IDS.put(playerId, packet.watchId());
            for (var entry : SESSIONS.entrySet()) {
                Session other = entry.getValue();
                if (entry.getKey().playerId().equals(playerId) && other != state && other.watchId != 0L) {
                    other.close();
                    other.lastUsedTick = tick;
                }
            }
            if (state == null) {
                // 闲置订阅清理不等于计划失效；从服务端保留的结果恢复并重新对账，不调用求解器。
                PlanResponse plan = retainedPlan.get();
                if (plan != null && plan.graph() != null) state = offer(playerId, plan, packet.requestId(), tick);
            }
            if (state == null) {
                sender.accept(new PlanInventoryUpdatePacket(packet.requestId(), packet.watchId(), 1L, true,
                        PreviewInventoryModel.Status.EXPIRED, 0, 1, List.of()));
                return;
            }
        }
        if (state != null) state.watch(packet, tick);
    }

    /** 沿用预览保留期和缓存容量；打开中的订阅不因其他后台结果被淘汰。 */
    static void prune(int tick, int pendingTicks, int maxEntries, long maxNodes) {
        SESSIONS.entrySet().removeIf(entry -> {
            Session state = entry.getValue();
            if (state.watchId != 0L || tick - state.lastUsedTick < pendingTicks) return false;
            state.close();
            return true;
        });
        long weight = SESSIONS.values().stream().mapToLong(Session::weight).sum();
        if (SESSIONS.size() <= maxEntries && weight <= maxNodes) return;
        for (var entry : SESSIONS.entrySet().stream()
                .filter(value -> value.getValue().watchId == 0L)
                .sorted(Comparator.comparingInt(value -> value.getValue().lastUsedTick)).toList()) {
            if (SESSIONS.size() <= maxEntries && weight <= maxNodes) break;
            Session removed = SESSIONS.remove(entry.getKey());
            if (removed != null) { weight -= removed.weight(); removed.close(); }
        }
    }

    static void removePlayer(UUID playerId) {
        SESSIONS.entrySet().removeIf(entry -> {
            if (!entry.getKey().playerId().equals(playerId)) return false;
            entry.getValue().close();
            return true;
        });
        LAST_WATCH_IDS.remove(playerId);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int tick = event.getServer().getTickCount();
        int poll = RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PREVIEW_INVENTORY_POLL_TICKS);
        int pending = RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PREVIEW_INVENTORY_PENDING_TICKS);
        prune(tick, pending,
                RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PLAN_CACHE_MAX_ENTRIES),
                RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PLAN_CACHE_MAX_NODES));
        for (var entry : List.copyOf(SESSIONS.entrySet())) {
            Session state = entry.getValue();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey().playerId());
            if (player == null || !enabled() || (state.watchId == 0 && tick - state.lastUsedTick >= pending)) {
                state.close();
                SESSIONS.remove(entry.getKey(), state);
                continue;
            }
            if (state.watchId == 0) continue;
            try {
                state.tick(player, tick, poll, packet -> NetworkHandler.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player), packet));
            } catch (RuntimeException | LinkageError failure) {
                state.unavailable();
                state.send(PreviewInventoryModel.Status.UNAVAILABLE, packet -> NetworkHandler.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player), packet));
                RSIntegrationMod.LOGGER.debug("[RSI-preview] Inventory update failed", failure);
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        removePlayer(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SESSIONS.values().forEach(Session::close);
        SESSIONS.clear();
        LAST_WATCH_IDS.clear();
    }

    static final class Session {
        final PlanResponse plan;
        final long requestId;
        final long revision = CraftPlanningRevision.current();
        final PreviewInventoryModel model;
        final boolean watchesContainers;
        final Map<IngredientKey, Long> stored = new HashMap<>();
        final Map<IngredientKey, Long> local = new HashMap<>();
        final Map<IngredientKey, Long> derived = new HashMap<>();
        final Map<IngredientKey, Long> pending = new LinkedHashMap<>();
        final Map<StorageFluidKey, IngredientKey> fluidKeys = new HashMap<>();
        final Set<IngredientKey> dirty = new LinkedHashSet<>();
        StorageItemSubscription subscription;
        CraftStorageEndpoint endpoint;
        StorageReference boundReference;
        long watchId;
        long lastWatchId;
        long sequence;
        long bindingGeneration;
        int lastUsedTick;
        int lastPollTick;
        boolean full;
        boolean invalidated;
        boolean available;
        boolean derivedDirty;
        PreviewInventoryModel.Status lastStatus;

        Session(PlanResponse plan, long requestId, int tick) {
            this.plan = plan;
            this.requestId = requestId;
            this.model = new PreviewInventoryModel(plan);
            this.watchesContainers = model.itemTypes().contains(Items.WATER_BUCKET)
                    || model.itemTypes().contains(Items.LAVA_BUCKET);
            this.lastUsedTick = tick;
        }

        long weight() { return Math.max(1L, (long) plan.graph().nodes().size() + model.keys().size()); }

        void watch(PlanInventoryWatchPacket packet, int tick) {
            if (packet.requestId() != requestId) return;
            if (!packet.open()) {
                if (packet.watchId() == watchId) {
                    close();
                    lastUsedTick = tick;
                }
                return;
            }
            if (packet.watchId() <= lastWatchId) return;
            close();
            watchId = packet.watchId();
            lastWatchId = packet.watchId();
            sequence = 0;
            full = true;
            invalidated = true;
            lastUsedTick = tick;
        }

        void tick(ServerPlayer player, int tick, int pollTicks, Consumer<PlanInventoryUpdatePacket> sender) {
            if (!CraftPlanningRevision.isCurrent(revision)) {
                unavailable();
                send(PreviewInventoryModel.Status.EXPIRED, sender);
                return;
            }
            boolean poll = tick - lastPollTick >= pollTicks;
            if (invalidated || poll) {
                lastPollTick = tick;
                CraftStorageEndpoint resolved = resolve(player);
                if (resolved == null && (plan.storageReference() != null || boundReference != null)) {
                    unavailable();
                } else if (resolved != null && (!resolved.isAvailable()
                        || !resolved.session().hasPermission(player, StoragePermission.VIEW))) {
                    unavailable();
                } else {
                    StorageReference reference = resolved == null ? null : resolved.session().reference();
                    if (invalidated || !available || !Objects.equals(boundReference, reference)
                            || (subscription != null && !subscription.isValid())) bind(player, resolved);
                    else if (subscription == null && resolved != null) snapshot(player, resolved);
                }
                invalidated = false;
            }
            if (!available) {
                send(PreviewInventoryModel.Status.UNAVAILABLE, sender);
                return;
            }
            if (poll || full || !pending.isEmpty() || derivedDirty) {
                // 背包没有统一原生通知；仅在对账间隔或相关网络变化时读取，不能重复扫描网络。
                Map<IngredientKey, Long> fresh = new HashMap<>();
                MaterialSources.countInventoryForTypes(player, model.itemTypes()).forEach((key, amount) -> {
                    IngredientKey material = IngredientKey.of(key.toStack());
                    if (model.tracks(material)) fresh.merge(material, amount, SaturatingLongMath::add);
                });
                replace(local, fresh);
            }
            for (var change : pending.entrySet()) {
                stored.put(change.getKey(), change.getValue());
                dirty.add(change.getKey());
            }
            pending.clear();
            if (poll || full || derivedDirty) updateDerived(player);
            for (IngredientKey key : dirty) {
                model.update(key, SaturatingLongMath.add(local.getOrDefault(key, 0L),
                        SaturatingLongMath.add(stored.getOrDefault(key, 0L), derived.getOrDefault(key, 0L))));
            }
            dirty.clear();
            PreviewInventoryModel.Status status = model.status();
            if (status == PreviewInventoryModel.Status.READY && derived.entrySet().stream().anyMatch(entry ->
                    entry.getValue() > 0 && SaturatingLongMath.add(local.getOrDefault(entry.getKey(), 0L),
                            stored.getOrDefault(entry.getKey(), 0L)) < model.requiredAmount(entry.getKey()))) {
                // 空桶和流体还可能被同一计划的其他槽位消耗，派生数量不是独立库存。
                status = PreviewInventoryModel.Status.REPLAN;
            }
            send(status, sender);
        }

        private CraftStorageEndpoint resolve(ServerPlayer player) {
            if (plan.storageReference() != null) return CraftStorageEndpoints.resolve(plan.storageReference(), player).orElse(null);
            ResourceLocation dimension = ResourceLocation.tryParse(Objects.requireNonNullElse(plan.executionDim(), ""));
            return CraftPacketUtils.resolveEndpointForCraft(player,
                    dimension == null ? player.level().dimension() : ResourceKey.create(Registries.DIMENSION, dimension),
                    new BlockPos(plan.executionPosX(), plan.executionPosY(), plan.executionPosZ())).orElse(null);
        }

        void bind(ServerPlayer player, CraftStorageEndpoint resolved) {
            detach();
            invalidated = false;
            endpoint = resolved;
            boundReference = resolved == null ? null : resolved.session().reference();
            stored.clear();
            pending.clear();
            fluidKeys.clear();
            dirty.addAll(model.keys());
            if (resolved != null) {
                for (IngredientKey key : model.keys()) {
                    FluidStack fluid = InkFluidSupport.fluid(key.stack(1));
                    if (!fluid.isEmpty()) fluidKeys.put(StorageFluidKey.fromStack(boundReference.backendId(), fluid), key);
                }
                long generation = bindingGeneration;
                StorageItemChangeListener listener = new StorageItemChangeListener() {
                    @Override public void onChanged(ItemStack stack, long amount) {
                        if (generation != bindingGeneration || watchId == 0 || stack.isEmpty()) return;
                        IngredientKey key = IngredientKey.of(stack);
                        if (model.tracks(key)) pending.put(key, Math.max(0L, amount));
                        if (watchesContainers && stack.is(Items.BUCKET)) derivedDirty = true;
                    }

                    @Override public void onFluidChanged(FluidStack stack, long amount) {
                        if (generation != bindingGeneration || watchId == 0 || stack.isEmpty()) return;
                        IngredientKey key = fluidKeys.get(StorageFluidKey.fromStack(boundReference.backendId(), stack));
                        if (key != null) pending.put(key, Math.max(0L, amount));
                        if (watchesContainers && (stack.getFluid() == Fluids.WATER || stack.getFluid() == Fluids.LAVA)) derivedDirty = true;
                    }

                    @Override public void onInvalidated() {
                        // RS 正在遍历监听器集合，必须等 tick 边界再解绑。
                        if (generation == bindingGeneration) invalidated = true;
                    }
                };
                try {
                    subscription = resolved.session().subscribeItemChanges(listener).orElse(null);
                } catch (RuntimeException | LinkageError failure) {
                    subscription = null;
                    RSIntegrationMod.LOGGER.debug("[RSI-preview] Falling back to inventory polling", failure);
                }
                snapshot(player, resolved);
            } else available = true;
            full = true;
            derivedDirty = true;
        }

        void snapshot(ServerPlayer player, CraftStorageEndpoint source) {
            var result = source.snapshot(player, model.itemTypes());
            if (!result.successful()) {
                unavailable();
                return;
            }
            Map<IngredientKey, Long> fresh = new HashMap<>();
            result.snapshot().orElseThrow().items().forEach(item -> {
                IngredientKey key = IngredientKey.of(item.stack());
                if (model.tracks(key)) fresh.merge(key, item.amount(), SaturatingLongMath::add);
            });
            replace(stored, fresh);
            pending.clear();
            available = true;
        }

        private void updateDerived(ServerPlayer player) {
            Map<IngredientKey, Long> fresh = new HashMap<>();
            if (endpoint != null) {
                for (var item : List.of(Items.WATER_BUCKET, Items.LAVA_BUCKET)) {
                    IngredientKey key = IngredientKey.of(new ItemStack(item));
                    if (model.tracks(key)) fresh.put(key, Math.max(0L,
                            endpoint.session().countDerivedContainer(player, key.stack(1))));
                }
            }
            replace(derived, fresh);
            derivedDirty = false;
        }

        private void replace(Map<IngredientKey, Long> previous, Map<IngredientKey, Long> fresh) {
            previous.forEach((key, amount) -> {
                if (!amount.equals(fresh.getOrDefault(key, 0L))) dirty.add(key);
            });
            fresh.forEach((key, amount) -> {
                if (!amount.equals(previous.getOrDefault(key, 0L))) dirty.add(key);
            });
            previous.clear();
            previous.putAll(fresh);
        }

        void send(PreviewInventoryModel.Status status, Consumer<PlanInventoryUpdatePacket> sender) {
            var entries = available ? model.drain(full) : List.<PlanInventoryUpdatePacket.Entry>of();
            if (!full && entries.isEmpty() && status == lastStatus) return;
            for (var packet : PlanInventoryUpdatePacket.split(requestId, watchId, ++sequence, full, status, entries)) sender.accept(packet);
            full = false;
            lastStatus = status;
        }

        void unavailable() {
            detach();
            available = false;
            pending.clear();
        }

        private void detach() {
            bindingGeneration++;
            StorageItemSubscription old = subscription;
            subscription = null;
            if (old != null) old.close();
        }

        void close() {
            watchId = 0;
            detach();
            available = false;
            pending.clear();
        }
    }
}
