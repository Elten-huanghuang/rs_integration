package com.huanghuang.rsintegration.network;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.network.node.INetworkNode;
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

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.UUID;

public final class RSIntegrationNetwork {

    private static final PlayerNetworkResolutionCache<INetwork> RESOLUTION_CACHE =
            new PlayerNetworkResolutionCache<>();

    private RSIntegrationNetwork() {}

    @Nullable
    public static INetwork resolveNetworkFromPlayer(ServerPlayer player) {
        UUID playerId = player.getUUID();
        MinecraftServer server = player.server;
        Object dimension = player.level().dimension();
        AbstractContainerMenu menu = player.containerMenu;
        long tick = server.getTickCount();
        PlayerNetworkResolutionCache.Entry<INetwork> cached =
                RESOLUTION_CACHE.get(playerId, server, dimension, menu, tick);
        if (cached != null) return cached.value();

        INetwork network = resolveNetworkFromPlayerUncached(player);
        RESOLUTION_CACHE.put(playerId, server, dimension, menu, tick, network);
        return network;
    }

    @Nullable
    private static INetwork resolveNetworkFromPlayerUncached(ServerPlayer player) {
        INetwork net = getNetworkFromContainer(player.containerMenu);
        if (net != null) { logResolved("container", net); return net; }

        net = resolveFromPlayerInventory(player);
        if (net != null) { logResolved("inventory", net); return net; }

        net = resolveFromContainerTerminal(player);
        if (net != null) { logResolved("terminal", net); return net; }

        net = AltarBindingRegistry.resolveNetworkFromAnyBinding(player);
        if (net != null) { logResolved("binding", net); return net; }

        // Do not guess a network from spatial proximity.  A nearby node may
        // belong to another player's network; callers performing extraction
        // or insertion must use an explicit container, item, terminal, or binding.
        RSIntegrationMod.LOGGER.debug("[RSI] resolveNetworkFromPlayer: no explicit network source");
        return null;
    }

    public static void invalidateNetworkResolution(UUID playerId) {
        RESOLUTION_CACHE.invalidate(playerId);
    }

    public static void clearNetworkResolutionCache() {
        RESOLUTION_CACHE.clear();
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
        if (!net.minecraftforge.fml.ModList.get().isLoaded("curios")) return null;
        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (opt.isPresent()) {
                var handler = opt.get();
                for (var stacksHandler : handler.getCurios().values()) {
                    var stacks = stacksHandler.getStacks();
                    for (int s = 0; s < stacks.getSlots(); s++) {
                        ItemStack stack = stacks.getStackInSlot(s);
                        INetwork net = resolveFromNetworkItem(player, stack);
                        if (net != null) return net;
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] Curios scan error", e);
        }
        return null;
    }

    @Nullable
    public static INetwork resolveNetwork(MinecraftServer server,
                                          ResourceKey<Level> dimension,
                                          BlockPos controllerPos) {
        if (dimension == null || controllerPos == null) return null;
        try {
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
            // Fallback: ControllerBlockEntity (and some other RS blocks) do not implement
            // INetworkNode but still have a getNetwork() method via their own hierarchy.
            try {
                java.lang.reflect.Method getNetwork = be.getClass().getMethod("getNetwork");
                Object result = getNetwork.invoke(be);
                if (result instanceof INetwork net) {
                    RSIntegrationMod.LOGGER.debug("[RSI] resolveNetwork: got network via reflection from {}",
                            be.getClass().getName());
                    return net;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI] resolveNetwork: getNetwork() not available on {}", be.getClass().getName());
            }
            RSIntegrationMod.LOGGER.debug("[RSI] resolveNetwork: BE at {} is {} (no network accessible)",
                    controllerPos, be.getClass().getName());
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] resolveNetwork error", e);
        }
        return null;
    }

    private static INetwork resolveFromNetworkItem(ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        // Standard path: RS NetworkItem / WirelessGrid
        if (NetworkItem.isValid(stack)) {
            ResourceKey<Level> dim = NetworkItem.getDimension(stack);
            if (dim != null) {
                BlockPos pos = new BlockPos(
                        NetworkItem.getX(stack),
                        NetworkItem.getY(stack),
                        NetworkItem.getZ(stack));
                INetwork net = resolveNetwork(player.server, dim, pos);
                if (net != null) return net;
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
                        net.minecraft.core.registries.Registries.DIMENSION, dimId);
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
                if (val instanceof net.minecraft.nbt.StringTag st) {
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
                    net.minecraft.core.registries.Registries.DIMENSION, dimId);
            return resolveNetwork(server, dim, new BlockPos(x, y, z));
        }
        return null;
    }

    // Cooldown cache for nearby-node scans — prevents repeated 4096-block sweeps
    private static final java.util.Map<UUID, Long> lastNearbyScan = new java.util.concurrent.ConcurrentHashMap<>();
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
        if (player.containerMenu == null) return null;
        try {
            Method getGrid = player.containerMenu.getClass().getMethod("getGrid");
            Object grid = getGrid.invoke(player.containerMenu);
            if (grid == null) return null;
            Method getStack = grid.getClass().getMethod("getItemStack");
            ItemStack stack = (ItemStack) getStack.invoke(grid);
            return resolveFromNetworkItem(player, stack);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] Container terminal resolve failed", e);
            return null;
        }
    }

    public static ItemStack extractFromNetwork(INetwork network, Ingredient ingredient, int count,
                                               @Nullable ServerPlayer player) {
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
            var snapshot = new java.util.ArrayList<ItemStack>();
            for (var entry : list.getStacks()) {
                ItemStack stored = entry.getStack();
                if (!stored.isEmpty() && ingredient.test(stored)) {
                    snapshot.add(stored.copy());
                }
            }

            // Single-phase PERFORM extraction — avoids TOCTOU race between
            // SIMULATE and PERFORM. If we get less than requested, refund.
            int remaining = count;
            ItemStack result = ItemStack.EMPTY;

            for (ItemStack template : snapshot) {
                if (remaining <= 0) break;
                int take = Math.min(remaining, template.getCount());
                ItemStack extractTemplate = template.copy();
                extractTemplate.setCount(1);
                ItemStack extracted = network.extractItem(extractTemplate, take, Action.PERFORM);
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

            if (!result.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI] extractFromNetwork: partial extraction — "
                        + "requested {} but only got {}", count, result.getCount());
            }
            return result;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI] extractFromNetwork error — items may have been lost", e);
        }
        return ItemStack.EMPTY;
    }

    /** Extract one exact item/NBT identity from the network. */
    public static ItemStack extractExactFromNetwork(INetwork network, ItemStack template, int count,
                                                    @Nullable ServerPlayer player) {
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
            ItemStack extracted = network.extractItem(request, count, Action.PERFORM);
            if (extracted.isEmpty() || ItemStack.isSameItemSameTags(extracted, template)) {
                return extracted;
            }
            RSIntegrationMod.LOGGER.error("[RSI] Exact extraction returned the wrong identity; refunding {} x{}",
                    extracted.getDisplayName().getString(), extracted.getCount());
            ItemStack leftover = network.insertItem(extracted, extracted.getCount(), Action.PERFORM);
            if (!leftover.isEmpty()) {
                RSIntegrationMod.LOGGER.error("[RSI] Exact extraction identity refund left {} x{} unreturned",
                        leftover.getDisplayName().getString(), leftover.getCount());
            }
            // Return only the unrefunded fragment so the ledger can account for
            // and roll it back through its normal partial-extraction path.
            return leftover;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI] extractExactFromNetwork error", e);
            return ItemStack.EMPTY;
        }
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
                if (ingredient.test(stored)) {
                    return true;
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] hasItemInNetwork error", e);
        }
        return false;
    }

    public static ItemStack tryExtractFromPlayerRS(ServerPlayer player, Ingredient ingredient, int count) {
        INetwork network = resolveNetworkFromPlayer(player);
        if (network == null) return ItemStack.EMPTY;
        return extractFromNetwork(network, ingredient, count, player);
    }

    public static boolean hasItemInPlayerRS(ServerPlayer player, Ingredient ingredient) {
        INetwork network = resolveNetworkFromPlayer(player);
        if (network == null) return false;
        return hasItemInNetwork(network, ingredient);
    }
}
