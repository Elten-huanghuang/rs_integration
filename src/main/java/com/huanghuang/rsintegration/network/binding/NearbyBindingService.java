package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.ProtectionChecker;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.util.CuriosAccess;
import com.refinedmods.refinedstorage.item.NetworkItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-thread, tick-budgeted one-shot binding scan. */
@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NearbyBindingService {

    private static final Map<UUID, ScanJob> JOBS = new LinkedHashMap<>();
    private static final Map<UUID, Long> LAST_REQUEST_NANOS = new LinkedHashMap<>();

    private NearbyBindingService() {}

    public static void request(ServerPlayer player) {
        if (!RSIntegrationConfig.ENABLE_BINDING.get()) return;
        UUID id = player.getUUID();
        if (JOBS.containsKey(id)) {
            player.displayClientMessage(Component.translatable("rsi.binding.nearby.busy"), true);
            return;
        }
        long now = System.nanoTime();
        long cooldown = RSIntegrationConfig.NEARBY_BINDING_COOLDOWN_MS.get() * 1_000_000L;
        Long previous = LAST_REQUEST_NANOS.get(id);
        if (previous != null && now - previous < cooldown) {
            long remaining = Math.max(1L, (cooldown - (now - previous)) / 1_000_000L);
            player.displayClientMessage(Component.translatable(
                    "rsi.binding.nearby.cooldown", remaining), true);
            return;
        }

        ItemStack connector = selectConnector(player);
        if (connector == null) return;
        var networkBinding = RSBindingHook.INSTANCE.createBinding(connector);
        if (networkBinding.isEmpty()) {
            player.displayClientMessage(Component.translatable("rsi.binding.nearby.invalid_connector"), true);
            return;
        }

        ServerLevel level = player.serverLevel();
        int horizontalRadius = RSIntegrationConfig.NEARBY_BINDING_HORIZONTAL_RADIUS.get();
        int verticalRadius = RSIntegrationConfig.NEARBY_BINDING_VERTICAL_RADIUS.get();
        JOBS.put(id, new ScanJob(player, level, connector, networkBinding.get(),
                horizontalRadius, verticalRadius));
        LAST_REQUEST_NANOS.put(id, now);
        player.displayClientMessage(Component.translatable(
                "rsi.binding.nearby.started", horizontalRadius, verticalRadius), true);
    }

    private static ItemStack selectConnector(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (NetworkItem.isValid(main)) return main;
        ItemStack offhand = player.getOffhandItem();
        if (NetworkItem.isValid(offhand)) return offhand;

        List<ItemStack> candidates = new ArrayList<>();
        for (ItemStack stack : player.getInventory().items) {
            if (NetworkItem.isValid(stack)) candidates.add(stack);
        }
        for (ItemStack stack : player.getInventory().armor) {
            if (NetworkItem.isValid(stack)) candidates.add(stack);
        }
        for (ItemStack stack : CuriosAccess.stacks(player)) {
            if (NetworkItem.isValid(stack)) candidates.add(stack);
        }
        if (candidates.size() == 1) return candidates.get(0);
        player.displayClientMessage(Component.translatable(candidates.isEmpty()
                ? "rsi.binding.nearby.no_connector"
                : "rsi.binding.nearby.multiple_connectors"), true);
        return null;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || JOBS.isEmpty()) return;
        long budgetNanos = RSIntegrationConfig.NEARBY_BINDING_TICK_BUDGET_MICROS.get() * 1_000L;
        long deadline = System.nanoTime() + budgetNanos;
        Iterator<Map.Entry<UUID, ScanJob>> iterator = JOBS.entrySet().iterator();
        int jobsRemaining = JOBS.size();
        while (iterator.hasNext() && System.nanoTime() < deadline) {
            Map.Entry<UUID, ScanJob> entry = iterator.next();
            ScanJob job = entry.getValue();
            if (!isOnline(event.getServer(), entry.getKey(), job.player)
                    || !job.connectorStillCarried()) {
                job.cancel("rsi.binding.nearby.connector_removed");
                iterator.remove();
                continue;
            }
            long remainingBudget = Math.max(1L, deadline - System.nanoTime());
            long sliceDeadline = System.nanoTime()
                    + Math.max(50_000L, remainingBudget / Math.max(1, jobsRemaining));
            boolean done = false;
            do {
                done = job.step();
            } while (!done && System.nanoTime() < sliceDeadline && System.nanoTime() < deadline);
            if (done) {
                job.finish();
                iterator.remove();
            }
            jobsRemaining--;
        }
    }

    private static boolean isOnline(MinecraftServer server, UUID id, ServerPlayer expected) {
        return server.getPlayerList().getPlayer(id) == expected;
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            JOBS.remove(player.getUUID());
            LAST_REQUEST_NANOS.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        JOBS.clear();
        LAST_REQUEST_NANOS.clear();
    }

    private static final class ScanJob {
        private final ServerPlayer player;
        private final ServerLevel level;
        private final ItemStack connector;
        private final AltarBinding networkBinding;
        private final int maxMachines;
        private final int minX, maxX, minY, maxY, minZ, maxZ;
        private final Set<BlockPos> knownPlayerBindings;
        private final Set<BlockPos> seenRoots = new java.util.HashSet<>();
        private final Map<Block, Boolean> targetCache = new IdentityHashMap<>();
        private final Map<Long, PermissionDecision> permissionCache = new LinkedHashMap<>();
        private final long startedNanos = System.nanoTime();
        private int x, y, z;
        private int bound;
        private int candidates;
        private int alreadyBound;
        private int denied;
        private int invalid;
        private boolean completed;

        private ScanJob(ServerPlayer player, ServerLevel level, ItemStack connector,
                        AltarBinding networkBinding, int horizontalRadius, int verticalRadius) {
            this.player = player;
            this.level = level;
            this.connector = connector;
            this.networkBinding = networkBinding;
            this.maxMachines = RSIntegrationConfig.NEARBY_BINDING_MAX_MACHINES.get();
            BlockPos center = player.blockPosition();
            this.minX = center.getX() - horizontalRadius;
            this.maxX = center.getX() + horizontalRadius;
            this.minY = Math.max(level.getMinBuildHeight(), center.getY() - verticalRadius);
            this.maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + verticalRadius);
            this.minZ = center.getZ() - horizontalRadius;
            this.maxZ = center.getZ() + horizontalRadius;
            this.x = minX;
            this.y = minY;
            this.z = minZ;
            this.knownPlayerBindings = collectPlayerBindings(player, level);
        }

        private static Set<BlockPos> collectPlayerBindings(ServerPlayer player, ServerLevel level) {
            Set<BlockPos> result = new java.util.HashSet<>();
            for (List<ItemStack> group : List.of(
                    player.getInventory().items,
                    player.getInventory().offhand,
                    player.getInventory().armor)) {
                collect(group, level, result);
            }
            for (ItemStack curio : CuriosAccess.stacks(player)) {
                collect(Collections.singletonList(curio), level, result);
            }
            return result;
        }

        private static void collect(List<ItemStack> stacks, ServerLevel level, Set<BlockPos> result) {
            for (ItemStack stack : stacks) {
                for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                    if (entry.dim().equals(level.dimension().location())) result.add(entry.pos());
                }
            }
        }

        private boolean connectorStillCarried() {
            if (player.serverLevel() != level) return false;
            if (player.getMainHandItem() == connector || player.getOffhandItem() == connector) return true;
            for (ItemStack stack : player.getInventory().items) {
                if (stack == connector) return true;
            }
            for (ItemStack stack : player.getInventory().armor) {
                if (stack == connector) return true;
            }
            for (ItemStack curio : CuriosAccess.stacks(player)) {
                if (curio == connector) return true;
            }
            return false;
        }

        /** Process one position; the outer tick loop supplies the global budget. */
        private boolean step() {
            if (bound >= maxMachines || completed) return true;
            if (x > maxX) return true;
            BlockPos pos = new BlockPos(x, y, z);
            advance();
            boolean exhausted = x > maxX;
            if (!level.isLoaded(pos)) return exhausted;
            net.minecraft.world.level.block.Block block = level.getBlockState(pos).getBlock();
            Boolean potential = targetCache.get(block);
            if (potential == null) {
                potential = BindingEventHandler.isPotentialNearbyTarget(block);
                targetCache.put(block, potential);
            }
            if (!potential) return exhausted;
            BindingEventHandler.NearbyTarget target =
                    BindingEventHandler.prepareNearbyTarget(level, pos);
            if (target == null) {
                if (BindingEventHandler.isEnabledRegisteredTarget(block)) invalid++;
                return exhausted;
            }
            if (!seenRoots.add(target.rootPos())) return exhausted;
            candidates++;

            long chunkKey = ChunkPos.asLong(target.rootPos());
            long tick = level.getGameTime();
            PermissionDecision decision = permissionCache.get(chunkKey);
            if (decision == null || decision.tick != tick) {
                decision = new PermissionDecision(tick,
                        ProtectionChecker.check(player, level, target.rootPos()).permitted());
                permissionCache.put(chunkKey, decision);
            }
            if (!decision.allowed) {
                denied++;
                return exhausted;
            }

            BindingEventHandler.NearbyBindResult result =
                    BindingEventHandler.bindNearbyTarget(player, level, connector,
                            networkBinding, target, knownPlayerBindings);
            if (result == BindingEventHandler.NearbyBindResult.BOUND) bound++;
            else alreadyBound++;
            return bound >= maxMachines || exhausted;
        }

        private void advance() {
            if (++y > maxY) {
                y = minY;
                if (++z > maxZ) {
                    z = minZ;
                    x++;
                }
            }
        }

        private void finish() {
            if (completed) return;
            completed = true;
            if (bound > 0) {
                // The scan mutates the connector's server-side NBT directly,
                // outside a normal inventory click. Mark the inventory dirty
                // and push both the player inventory and any open menu so a
                // multiplayer client receives the new binding immediately.
                syncPlayerInventory(player);
                AltarBindingRegistry.invalidateScanCache();
                RSIntegrationNetwork.invalidateNetworkResolution(player.getUUID());
                BindingEventHandler.sendBindingRefresh(player);
            }
            player.displayClientMessage(Component.translatable(
                    "rsi.binding.nearby.finished", candidates, bound,
                    alreadyBound, denied, invalid), false);
            RSIntegrationMod.LOGGER.info(
                    "[RSI-Bind] Nearby scan player={} candidates={} bound={} alreadyBound={} denied={} invalid={} elapsedMs={}",
                    player.getGameProfile().getName(), candidates, bound, alreadyBound, denied, invalid,
                    (System.nanoTime() - startedNanos) / 1_000_000L);
        }

        private static void syncPlayerInventory(ServerPlayer player) {
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            if (player.containerMenu != player.inventoryMenu) {
                player.containerMenu.broadcastChanges();
            }
        }

        private void cancel(String key) {
            completed = true;
            player.displayClientMessage(Component.translatable(key), true);
        }

        private record PermissionDecision(long tick, boolean allowed) {}
    }
}
