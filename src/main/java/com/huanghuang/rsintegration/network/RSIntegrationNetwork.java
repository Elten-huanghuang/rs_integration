package com.huanghuang.rsintegration.network;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.command.PerformanceMonitor;
import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.RSAltarBindingResolver;
import com.huanghuang.rsintegration.resonance.backpack.ResonanceBackpackContainer;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.util.ModIds;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.network.node.INetworkNode;
import com.refinedmods.refinedstorage.api.network.node.INetworkNodeProxy;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.item.NetworkItem;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.ModList;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.util.CuriosAccess;
import java.util.ArrayList;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.StringTag;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class RSIntegrationNetwork {

    private static final PlayerNetworkResolutionCache<INetwork> RESOLUTION_CACHE =
            new PlayerNetworkResolutionCache<>(20);
    private static final ConcurrentHashMap<UUID, INetwork> LAST_RESOLVED_NETWORKS =
            new ConcurrentHashMap<>();

    private RSIntegrationNetwork() {}

    /**
     * Publishes a network that was already authenticated by an RS UI/container
     * to the common player-resolution cache.  The side panel is only a UI
     * consumer; it must not become a separate source of truth for crafting.
     */
    public static void rememberResolvedNetwork(ServerPlayer player, INetwork network) {
        if (player == null || network == null) return;
        UUID playerId = player.getUUID();
        LAST_RESOLVED_NETWORKS.put(playerId, network);
        RESOLUTION_CACHE.put(playerId, player.server, player.level().dimension(),
                player.containerMenu, player.server.getTickCount(), network);
    }

    @Nullable
    public static INetwork resolveNetworkFromPlayer(ServerPlayer player) {
        if (player == null || !ModList.get().isLoaded(ModIds.REFINED_STORAGE)) return null;
        UUID playerId = player.getUUID();
        MinecraftServer server = player.server;
        Object dimension = player.level().dimension();
        AbstractContainerMenu menu = player.containerMenu;
        long tick = server.getTickCount();
        PlayerNetworkResolutionCache.Entry<INetwork> cached =
                RESOLUTION_CACHE.get(playerId, server, dimension, menu, tick);
        if (cached != null && cached.value() != null) {
            PerformanceMonitor.recordNetworkResolve(true, cached.value() != null);
            LAST_RESOLVED_NETWORKS.put(playerId, cached.value());
            return cached.value();
        }

        // Do not treat a cached null as authoritative. A wireless terminal can
        // be moved into the player's inventory, curios, or an RS container
        // after the negative lookup was cached; execution must retry the
        // player's own terminal before considering secondary integrations.
        INetwork network = resolveNetworkFromPlayerUncached(player);
        if (network == null) {
            // The RS grid may close between preview and execution. Reuse the
            // last network resolved from this player's own terminal/container
            // while it remains a live network; do not use this as the primary
            // lookup, so newly inserted terminals are always rescanned first.
            network = LAST_RESOLVED_NETWORKS.get(playerId);
            if (network != null) {
                try {
                    if (!network.canRun() || network.getItemStorageCache() == null) {
                        LAST_RESOLVED_NETWORKS.remove(playerId, network);
                        network = null;
                    }
                } catch (RuntimeException | LinkageError invalid) {
                    LAST_RESOLVED_NETWORKS.remove(playerId, network);
                    network = null;
                }
            }
            if (network != null) logResolved("last player terminal session", network);
        } else {
            rememberResolvedNetwork(player, network);
        }
        RESOLUTION_CACHE.put(playerId, server, dimension, menu, tick, network);
        PerformanceMonitor.recordNetworkResolve(false, network != null);
        return network;
    }

    /**
     * Resolves only an access context that is present right now.  In
     * particular, this deliberately excludes LAST_RESOLVED_NETWORKS and the
     * side panel's last-known network.  Those values are useful for legacy
     * services, but are not credentials and must not select RS after a player
     * has discarded the terminal that authenticated it.
     */
    @Nullable
    public static INetwork resolveCurrentNetworkFromPlayer(ServerPlayer player) {
        if (player == null || !ModList.get().isLoaded(ModIds.REFINED_STORAGE)) return null;

        INetwork resonanceNetwork = resolveAuthenticatedResonanceMenuNetwork(player);
        if (resonanceNetwork != null) return resonanceNetwork;

        INetwork network = getNetworkFromContainer(player.containerMenu);
        if (network != null) return network;
        network = resolveFromPlayerInventory(player);
        if (network != null) return network;
        network = resolveFromContainerTerminal(player);
        if (network != null) return network;

        // A live side-panel listener is an active RS UI session.  Do not use
        // getListenerNetwork(), because it also returns the retained,
        // last-known network after the panel has been closed.
        network = RSSidePanelNetworkHandler
                .getActiveListenerNetwork(player.getUUID());
        if (network != null) return network;

        // A machine binding is an explicit, player-owned RS access path and
        // remains valid even when no terminal is held.
        return RSAltarBindingResolver.resolveNetworkFromAnyBinding(player);
    }

    /** Returns whether the supplied RS reference is backed by a current access context. */
    public static boolean hasCurrentNetworkAccess(ServerPlayer player,
                                                   StorageReference reference) {
        if (player == null || reference == null
                || !"refinedstorage".equals(reference.backendId().value())) return false;
        INetwork network = resolveCurrentNetworkFromPlayer(player);
        if (network == null) return false;
        try {
            if (network.getLevel() == null || network.getPosition() == null) return false;
            String id = "v1|" + network.getLevel().dimension().location() + "@"
                    + network.getPosition().getX() + "," + network.getPosition().getY()
                    + "," + network.getPosition().getZ();
            return id.equals(reference.networkId());
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Nullable
    private static INetwork resolveNetworkFromPlayerUncached(ServerPlayer player) {
        INetwork resonanceNetwork = resolveAuthenticatedResonanceMenuNetwork(player);
        if (resonanceNetwork != null) return resonanceNetwork;

        INetwork net = getNetworkFromContainer(player.containerMenu);
        if (net != null) return net;

        net = resolveFromPlayerInventory(player);
        if (net != null) return net;

        net = resolveFromContainerTerminal(player);
        if (net != null) return net;

        // Reuse the validated side-panel listener after the RS grid screen is
        // closed; the crafting plan is a separate request and may arrive with
        // no RS container or NetworkItem in the active player inventory.
        net = RSSidePanelNetworkHandler
                .getListenerNetwork(player.getUUID());
        if (net != null) {
            logResolved("RS side-panel listener", net);
            return net;
        }

        net = RSAltarBindingResolver.resolveNetworkFromAnyBinding(player);
        if (net != null) return net;

        // Do not guess a network from spatial proximity.  A nearby node may
        // belong to another player's network; callers performing extraction
        // or insertion must use an explicit container, item, terminal, or binding.
        return null;
    }

    public static void invalidateNetworkResolution(UUID playerId) {
        RESOLUTION_CACHE.invalidate(playerId);
        LAST_RESOLVED_NETWORKS.remove(playerId);
    }

    public static void clearNetworkResolutionCache() {
        RESOLUTION_CACHE.clear();
        LAST_RESOLVED_NETWORKS.clear();
        lastNearbyScan.clear();
    }

    private static void logResolved(String source, INetwork net) {
        if (RSIntegrationMod.LOGGER.isDebugEnabled()) {
            RSIntegrationMod.LOGGER.debug("[RSI] Resolved network via {}", source);
        }
    }

    private static INetwork getNetworkFromContainer(AbstractContainerMenu container) {
        if (container == null) return null;
        try {
            // Probe any RS grid container (GridContainerMenu, CraftingGridContainerMenu,
            // WirelessGridContainerMenu, etc.) by trying getGrid() reflectively across
            // the class hierarchy, rather than matching a single hardcoded class name.
            Class<?> clazz = container.getClass();
            while (clazz != null && clazz != Object.class) {
                if (clazz.getName().startsWith("com.refinedmods.refinedstorage.")) {
                    try {
                        Method getGrid = clazz.getMethod("getGrid");
                        Object grid = getGrid.invoke(container);
                        if (grid instanceof INetworkAwareGrid awareGrid) {
                            INetwork net = awareGrid.getNetwork();
                            if (net != null) return net;
                        }
                        if (grid instanceof INetworkNode node) {
                            INetwork net = node.getNetwork();
                            if (net != null) return net;
                        }
                    } catch (NoSuchMethodException e) { /* try superclass */ }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] getNetworkFromContainer error", e);
        }
        return null;
    }

    private static INetwork resolveFromPlayerInventory(ServerPlayer player) {
        var inv = player.getInventory();
        for (ItemStack stack : inv.items) {
            INetwork net = resolveFromNetworkItem(player, stack);
            if (net != null) return net;
        }
        for (ItemStack stack : inv.offhand) {
            INetwork net = resolveFromNetworkItem(player, stack);
            if (net != null) return net;
        }
        for (ItemStack stack : inv.armor) {
            INetwork net = resolveFromNetworkItem(player, stack);
            if (net != null) return net;
        }
        for (ItemStack stack : CuriosAccess.stacks(player)) {
            INetwork net = resolveFromNetworkItem(player, stack);
            if (net != null) return net;
        }
        return null;
    }

    @Nullable
    public static INetwork resolveNetwork(MinecraftServer server,
                                          ResourceKey<Level> dimension,
                                          BlockPos controllerPos) {
        if (!ModList.get().isLoaded(ModIds.REFINED_STORAGE)) return null;
        try {
            return resolveNetworkStrict(server, dimension, controllerPos);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] resolveNetwork error", e);
        }
        return null;
    }

    /**
     * A resonance menu is opened only after its backend resolver authenticates
     * the player. Preserve that exact RS session while the menu remains open,
     * without exposing RS types through the backend-neutral menu class.
     */
    @Nullable
    private static INetwork resolveAuthenticatedResonanceMenuNetwork(ServerPlayer player) {
        if (!(player.containerMenu instanceof ResonanceBackpackContainer backpack)
                || !backpack.usesBackend("refinedstorage")) return null;
        INetwork network = LAST_RESOLVED_NETWORKS.get(player.getUUID());
        if (network == null) return null;
        try {
            if (network.canRun() && network.getItemStorageCache() != null) return network;
        } catch (RuntimeException | LinkageError ignored) {
        }
        LAST_RESOLVED_NETWORKS.remove(player.getUUID(), network);
        return null;
    }

    /**
     * Crafting-only fallback for requests that have no terminal, network item,
     * or machine binding. It never becomes the general-purpose resolver used
     * by side-panel operations. The candidate must be running and the player
     * must have at least one RS storage permission, matching the VIEW policy
     * used by the storage backend adapter.
     */
    @Nullable
    public static INetwork resolveNearbyNetworkForCraft(ServerPlayer player) {
        if (player == null || !ModList.get().isLoaded(ModIds.REFINED_STORAGE)) return null;
        INetwork network = resolveFromNearbyNode(player);
        if (network == null) return null;
        try {
            if (!network.canRun()) return null;
            var security = network.getSecurityManager();
            if (security != null
                    && !security.hasPermission(Permission.INSERT, player)
                    && !security.hasPermission(Permission.EXTRACT, player)
                    && !security.hasPermission(Permission.AUTOCRAFTING, player)) {
                RSIntegrationMod.LOGGER.debug("[RSI] Nearby RS network rejected: no storage permission");
                return null;
            }
            RSIntegrationMod.LOGGER.debug("[RSI] Resolved crafting network via nearby RS node at {}",
                    network.getPosition());
            return network;
        } catch (RuntimeException | LinkageError failure) {
            RSIntegrationMod.LOGGER.debug("[RSI] Nearby crafting network probe failed", failure);
            return null;
        }
    }

    /** Resolves an explicit network without collapsing backend failures into a missing network. */
    @Nullable
    public static INetwork resolveNetworkStrict(MinecraftServer server,
                                                ResourceKey<Level> dimension,
                                                BlockPos controllerPos) {
        if (!ModList.get().isLoaded(ModIds.REFINED_STORAGE)) return null;
        if (server == null || dimension == null || controllerPos == null) return null;
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            RSIntegrationMod.LOGGER.debug("[RSI] resolveNetwork: null level for dim {}", dimension.location());
            return null;
        }
        if (!level.isLoaded(controllerPos)) {
            RSIntegrationMod.LOGGER.debug("[RSI] resolveNetwork: chunk not loaded at pos={} dim={}",
                    controllerPos, dimension.location());
            return null;
        }
        BlockEntity be = level.getBlockEntity(controllerPos);
        if (be == null) {
            RSIntegrationMod.LOGGER.debug("[RSI] resolveNetwork: no BlockEntity at pos={} dim={}",
                    controllerPos, dimension.location());
            return null;
        }
        if (be instanceof INetworkNode node) {
            INetwork net = node.getNetwork();
            if (net != null) return net;
            RSIntegrationMod.LOGGER.debug("[RSI] resolveNetwork: INetworkNode at {} has null network", controllerPos);
        }
        // 磁盘驱动器等 RS 方块实体通过代理提供节点，终端可以绑定到这些坐标。
        if (be instanceof INetworkNodeProxy<?> proxy) {
            INetworkNode node = proxy.getNode();
            INetwork net = node == null ? null : node.getNetwork();
            if (net != null) return net;
        }
        try {
            Method getNetwork = be.getClass().getMethod("getNetwork");
            Object result = getNetwork.invoke(be);
            if (result instanceof INetwork net) return net;
        } catch (NoSuchMethodException e) {
            RSIntegrationMod.LOGGER.debug("[RSI] resolveNetwork: getNetwork() not available on {}",
                    be.getClass().getName());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("RS block entity getNetwork() failed", e);
        }
        RSIntegrationMod.LOGGER.debug("[RSI] resolveNetwork: BE at {} is {} (no network accessible)",
                controllerPos, be.getClass().getName());
        return null;
    }

    private static INetwork resolveFromNetworkItem(ServerPlayer player, ItemStack stack) {
        if (player == null || !ModList.get().isLoaded(ModIds.REFINED_STORAGE)
                || stack == null || stack.isEmpty()) return null;

        // Let RS resolve the item through its own provider first.  This is
        // important for wireless grid/crafting-monitor variants and keeps us
        // independent of their private NBT layout across RS releases.
        if (stack.getItem() instanceof NetworkItem networkItem) {
            AtomicReference<INetwork> resolved = new AtomicReference<>();
            try {
                networkItem.applyNetwork(player.server, stack,
                        resolved::set,
                        ignored -> { });
                INetwork net = resolved.get();
                if (net != null) {
                    logResolved("RS NetworkItem provider", net);
                    return net;
                }
            } catch (RuntimeException | LinkageError failure) {
                RSIntegrationMod.LOGGER.debug("[RSI] NetworkItem provider resolution failed for {}",
                        stack.getItem(), failure);
            }
        }

        // Compatibility path for older/custom terminal items that still use
        // the standard NetworkItem NBT contract.
        if (NetworkItem.isValid(stack)) {
            ResourceKey<Level> dim = NetworkItem.getDimension(stack);
            if (dim != null) {
                BlockPos pos = new BlockPos(
                        NetworkItem.getX(stack),
                        NetworkItem.getY(stack),
                        NetworkItem.getZ(stack));
                INetwork net = resolveNetwork(player.server, dim, pos);
                if (net != null) {
                    logResolved("NetworkItem coordinates", net);
                    return net;
                }
            }
        }

        if (stack.hasTag()) {
            CompoundTag tag = stack.getTag();
            INetwork net = resolveFromNbt(player.server, tag);
            if (net != null) return net;
        }

        return null;
    }

    private static INetwork resolveFromNbt(MinecraftServer server, CompoundTag tag) {
        // Phase 1: Try known key patterns (fast path)
        INetwork net = resolveFromKnownKeys(server, tag);
        if (net != null) return net;

        // Phase 2: Recurse into child compound tags
        for (String key : tag.getAllKeys()) {
            if (tag.get(key) instanceof CompoundTag child) {
                net = resolveFromNbt(server, child);
                if (net != null) return net;
            }
        }

        // Phase 3: Heuristic scan — look for any dimension-like string +
        //           any x/y/z-like int triplet in the same tag
        return resolveHeuristic(server, tag);
    }

    private static INetwork resolveFromKnownKeys(MinecraftServer server, CompoundTag tag) {
        // Dimension key candidates (tried in order)
        String[] dimKeys = {"NetworkDimension", "Dimension", "dim", "dimension",
                "network_dimension", "world", "level"};
        ResourceLocation dimId = null;
        for (String key : dimKeys) {
            ResourceLocation parsed = ResourceLocation.tryParse(tag.getString(key));
            if (parsed != null && !parsed.getPath().isEmpty()) {
                dimId = parsed;
                break;
            }
        }
        if (dimId == null) return null;

        // Position key candidates (tried in order)
        // Each group: {x key, y key, z key}
        String[][] posGroups = {
                {"NodeX", "NodeY", "NodeZ"},       // RS 1.12+
                {"X", "Y", "Z"},                   // old RS / generic
                {"x", "y", "z"},                   // lowercase
                {"BlockX", "BlockY", "BlockZ"},    // some addons
                {"ControllerX", "ControllerY", "ControllerZ"},
                {"NetworkX", "NetworkY", "NetworkZ"},
                {"PosX", "PosY", "PosZ"},
        };
        for (String[] group : posGroups) {
            if (tag.contains(group[0]) && tag.contains(group[1]) && tag.contains(group[2])) {
                int x = tag.getInt(group[0]);
                int y = tag.getInt(group[1]);
                int z = tag.getInt(group[2]);
                ResourceKey<Level> dim = ResourceKey.create(
                        Registries.DIMENSION, dimId);
                return resolveNetwork(server, dim, new BlockPos(x, y, z));
            }
        }
        return null;
    }

    private static INetwork resolveHeuristic(MinecraftServer server, CompoundTag tag) {
        // Only heuristically scan tags that already contain at least one known RS
        // key pattern — this prevents false matches against arbitrary mod NBT.
        boolean hasRsSignal = false;
        for (String key : tag.getAllKeys()) {
            String lk = key.toLowerCase();
            if (lk.equals("nodex") || lk.equals("networkdimension")
                    || lk.equals("controllerx") || lk.equals("networkx")) {
                hasRsSignal = true;
                break;
            }
        }
        if (!hasRsSignal) return null;

        // Scan for any string value that parses as a ResourceLocation (potential dim)
        // and any three int values whose keys loosely match x/y/z.
        ResourceLocation dimId = null;
        Integer x = null, y = null, z = null;

        for (String key : tag.getAllKeys()) {
            String lk = key.toLowerCase();
            if (dimId == null && (lk.contains("dim") || lk.contains("world") || lk.contains("level"))) {
                var val = tag.get(key);
                if (val instanceof StringTag st) {
                    ResourceLocation parsed = ResourceLocation.tryParse(st.getAsString());
                    if (parsed != null && !parsed.getPath().isEmpty()) {
                        dimId = parsed;
                    }
                }
            }
            if (x == null && (lk.equals("x") || lk.equals("nodex") || lk.endsWith("_x")
                    || lk.equals("posx") || lk.equals("coordx") || lk.equals("blockx"))) {
                x = tag.getInt(key);
            }
            if (y == null && (lk.equals("y") || lk.equals("nodey") || lk.endsWith("_y")
                    || lk.equals("posy") || lk.equals("coordy") || lk.equals("blocky"))) {
                y = tag.getInt(key);
            }
            if (z == null && (lk.equals("z") || lk.equals("nodez") || lk.endsWith("_z")
                    || lk.equals("posz") || lk.equals("coordz") || lk.equals("blockz"))) {
                z = tag.getInt(key);
            }
        }

        if (dimId != null && x != null && y != null && z != null) {
            ResourceKey<Level> dim = ResourceKey.create(
                    Registries.DIMENSION, dimId);
            return resolveNetwork(server, dim, new BlockPos(x, y, z));
        }
        return null;
    }

    // Cooldown cache for nearby-node scans — prevents repeated 4096-block sweeps
    private static final Map<UUID, Long> lastNearbyScan = new ConcurrentHashMap<>();
    private static final long NEARBY_SCAN_COOLDOWN_MS = 10_000;
    private static final int NEARBY_SCAN_RANGE = 8;

    private static INetwork resolveFromNearbyNode(ServerPlayer player) {
        UUID pid = player.getUUID();
        long now = System.currentTimeMillis();
        Long last = lastNearbyScan.get(pid);
        if (last != null && now - last < NEARBY_SCAN_COOLDOWN_MS) return null;

        lastNearbyScan.put(pid, now);
        // Prune stale entries
        lastNearbyScan.values().removeIf(t -> now - t > NEARBY_SCAN_COOLDOWN_MS * 6);

        ServerLevel level = (ServerLevel) player.level();
        BlockPos playerPos = player.blockPosition();
        int range = NEARBY_SCAN_RANGE;
        for (BlockPos pos : BlockPos.betweenClosed(
                playerPos.offset(-range, -range / 2, -range),
                playerPos.offset(range, range / 2, range))) {
            if (!level.isLoaded(pos)) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof INetworkNode node) {
                INetwork net = node.getNetwork();
                if (net != null) return net;
            }
        }
        return null;
    }

    private static INetwork resolveFromContainerTerminal(ServerPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) return null;

        // Only attempt reflection on Refined Storage containers. Other mod containers
        // (vanilla menus, other mod UIs) never have getGrid() and this saves:
        // - ~6 reflection calls per tick per player with open containers
        // - Stack trace allocation and GC pressure from NoSuchMethodException
        if (!menu.getClass().getName().startsWith("com.refinedmods.refinedstorage.")) {
            return null;
        }

        try {
            Method getGrid = menu.getClass().getMethod("getGrid");
            Object grid = getGrid.invoke(menu);
            if (grid == null) return null;
            Method getStack = grid.getClass().getMethod("getItemStack");
            ItemStack stack = (ItemStack) getStack.invoke(grid);
            return resolveFromNetworkItem(player, stack);
        } catch (NoSuchMethodException e) {
            // RS container without getGrid() - not an error
            return null;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] Container terminal resolve failed", e);
            return null;
        }
    }

    public static ItemStack extractFromNetwork(INetwork network, Ingredient ingredient, int count,
                                               @Nullable ServerPlayer player) {
        return extractFromNetwork(network, ingredient, count, player, false);
    }

    /** Extract matching items, optionally without mutating the network. */
    public static ItemStack extractFromNetwork(INetwork network, Ingredient ingredient, int count,
                                               @Nullable ServerPlayer player, boolean simulate) {
        try {
            if (player != null) {
                var sec = network.getSecurityManager();
                if (sec != null && !sec.hasPermission(Permission.EXTRACT, player)) {
                    RSIntegrationMod.LOGGER.warn("[RSI] extractFromNetwork: player {} lacks EXTRACT permission",
                            player.getGameProfile().getName());
                    return ItemStack.EMPTY;
                }
            }
            var cache = network.getItemStorageCache();
            if (cache == null) return ItemStack.EMPTY;
            var list = cache.getList();
            if (list == null) return ItemStack.EMPTY;

            // Snapshot matching entries first, then extract.
            var snapshot = new ArrayList<ItemStack>();
            for (var entry : list.getStacks()) {
                ItemStack stored = entry.getStack();
                if (!stored.isEmpty() && MaterialMatcher.matchesIngredient(ingredient, stored)) {
                    snapshot.add(stored.copy());
                }
            }

            Action action = simulate ? Action.SIMULATE : Action.PERFORM;
            int remaining = count;
            ItemStack result = ItemStack.EMPTY;

            for (ItemStack template : snapshot) {
                if (remaining <= 0) break;
                int take = Math.min(remaining, template.getCount());
                ItemStack extractTemplate = template.copy();
                extractTemplate.setCount(1);
                ItemStack extracted = network.extractItem(extractTemplate, take, action);
                if (!extracted.isEmpty()) {
                    remaining -= extracted.getCount();
                    if (result.isEmpty()) {
                        result = extracted;
                    } else {
                        result.grow(extracted.getCount());
                    }
                }
            }

            if (result.getCount() >= count) {
                return result;
            }

            if (!result.isEmpty() && !simulate) {
                RSIntegrationMod.LOGGER.warn("[RSI] extractFromNetwork: partial extraction — "
                        + "requested {} but only got {}", count, result.getCount());
                // A ledger reservation is atomic from the caller's point of
                // view. Never leave a partial intermediate-material debit in
                // RS: the following craft step cannot use it and the ledger
                // would report a misleading commit failure. Put the partial
                // result back before returning failure.
                ItemStack refund = network.insertItem(result.copy(), result.getCount(), Action.PERFORM);
                if (!refund.isEmpty()) {
                    RSIntegrationMod.LOGGER.error("[RSI] partial extraction refund was rejected: {} x{}",
                            itemId(refund), refund.getCount());
                }
            }
            return ItemStack.EMPTY;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI] extractFromNetwork error — items may have been lost", e);
        }
        return ItemStack.EMPTY;
    }

    /** Extract one exact item/NBT identity from the network. */
    public static ItemStack extractExactFromNetwork(INetwork network, ItemStack template, int count,
                                                    @Nullable ServerPlayer player) {
        return extractExactFromNetwork(network, template, count, player, false);
    }

    /**
     * Extract one exact item/NBT identity from the network, optionally only
     * simulating the operation. The legacy bridge performs a SIMULATE pass
     * before the real extraction, so that validation must not consume storage.
     */
    public static ItemStack extractExactFromNetwork(INetwork network, ItemStack template, int count,
                                                    @Nullable ServerPlayer player, boolean simulate) {
        if (template.isEmpty() || count <= 0) return ItemStack.EMPTY;
        try {
            if (player != null) {
                var sec = network.getSecurityManager();
                if (sec != null && !sec.hasPermission(Permission.EXTRACT, player)) {
                    RSIntegrationMod.LOGGER.warn("[RSI] extractExactFromNetwork: player {} lacks EXTRACT permission",
                            player.getGameProfile().getName());
                    return ItemStack.EMPTY;
                }
            }
            ItemStack request = template.copyWithCount(1);
            Action action = simulate ? Action.SIMULATE : Action.PERFORM;
            ItemStack extracted = network.extractItem(request, count, action);
            if (extracted.isEmpty()) {
                return extracted;
            }
            if (MaterialMatcher.sameRuntimeFragment(template, extracted)
                    && extracted.getCount() >= count) {
                return extracted;
            }
            if (MaterialMatcher.sameRuntimeFragment(template, extracted)) {
                RSIntegrationMod.LOGGER.warn("[RSI] extractExactFromNetwork: partial extraction — requested {} but only got {}",
                        count, extracted.getCount());
                if (!simulate) {
                    ItemStack partialRefund = network.insertItem(extracted.copy(), extracted.getCount(), Action.PERFORM);
                    if (!partialRefund.isEmpty()) {
                        RSIntegrationMod.LOGGER.error("[RSI] partial exact extraction refund was rejected: {} x{}",
                                itemId(partialRefund), partialRefund.getCount());
                    }
                }
                return ItemStack.EMPTY;
            }
            if (simulate) {
                RSIntegrationMod.LOGGER.warn("[RSI] Simulated exact extraction returned the wrong identity: {} x{}",
                        itemId(extracted), extracted.getCount());
                return ItemStack.EMPTY;
            }
            RSIntegrationMod.LOGGER.error("[RSI] Exact extraction returned the wrong identity; refunding {} x{}",
                    itemId(extracted), extracted.getCount());
            ItemStack leftover = network.insertItem(extracted, extracted.getCount(), Action.PERFORM);
            if (!leftover.isEmpty()) {
                RSIntegrationMod.LOGGER.error("[RSI] Exact extraction identity refund left {} x{} unreturned",
                        itemId(leftover), leftover.getCount());
            }
            // Return only the unrefunded fragment so the ledger can account for
            // and roll it back through its normal partial-extraction path.
            return leftover;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI] extractExactFromNetwork error", e);
            return ItemStack.EMPTY;
        }
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null ? id.toString() : "unknown";
    }

    public static boolean hasItemInNetwork(INetwork network, Ingredient ingredient) {
        try {
            var cache = network.getItemStorageCache();
            if (cache == null) return false;
            var list = cache.getList();
            if (list == null) return false;
            for (var entry : list.getStacks()) {
                ItemStack stored = entry.getStack();
                if (stored.isEmpty()) continue;
                if (MaterialMatcher.matchesIngredient(ingredient, stored)) {
                    return true;
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] hasItemInNetwork error", e);
        }
        return false;
    }

    public static ItemStack tryExtractFromPlayerRS(ServerPlayer player, Ingredient ingredient, int count) {
        if (player == null || !ModList.get().isLoaded(ModIds.REFINED_STORAGE)) return ItemStack.EMPTY;
        INetwork network = resolveNetworkFromPlayer(player);
        if (network == null) return ItemStack.EMPTY;
        return extractFromNetwork(network, ingredient, count, player);
    }

    public static boolean hasItemInPlayerRS(ServerPlayer player, Ingredient ingredient) {
        if (player == null || !ModList.get().isLoaded(ModIds.REFINED_STORAGE)) return false;
        INetwork network = resolveNetworkFromPlayer(player);
        if (network == null) return false;
        return hasItemInNetwork(network, ingredient);
    }
}
