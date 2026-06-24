package com.huanghuang.rsintegration.integration;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.refinedmods.refinedstorage.api.network.INetwork;
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

public final class RSIntegration {

    private RSIntegration() {}

    @Nullable
    public static INetwork resolveNetworkFromPlayer(ServerPlayer player) {
        INetwork net = getNetworkFromContainer(player.containerMenu);
        if (net != null) return net;

        net = resolveFromPlayerInventory(player);
        if (net != null) return net;

        net = resolveFromContainerTerminal(player);
        if (net != null) return net;

        net = com.huanghuang.rsintegration.integration.AltarBindingRegistry.resolveNetworkFromAnyBinding(player);
        if (net != null) return net;

        net = resolveFromNearbyNode(player);
        if (net != null) return net;

        RSIntegrationMod.LOGGER.info("[RSI] resolveNetworkFromPlayer: all paths failed");
        return null;
    }

    @Nullable
    private static INetwork getNetworkFromContainer(AbstractContainerMenu container) {
        if (container == null) return null;
        try {
            Class<?> clazz = container.getClass();
            while (clazz != null && clazz != Object.class) {
                if (clazz.getName().equals("com.refinedmods.refinedstorage.container.GridContainerMenu")) {
                    Method getGrid = clazz.getMethod("getGrid");
                    Object grid = getGrid.invoke(container);
                    if (grid instanceof INetworkAwareGrid awareGrid) {
                        return awareGrid.getNetwork();
                    }
                    if (grid instanceof INetworkNode node) {
                        return node.getNetwork();
                    }
                    break;
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.info("[RSI] getNetworkFromContainer error: {}", e.toString());
        }
        return null;
    }

    @Nullable
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
        } catch (Throwable e) {
            RSIntegrationMod.LOGGER.info("[RSI] Curios scan error: {}", e.toString());
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
                RSIntegrationMod.LOGGER.info("[RSI] resolveNetwork: null level for dim {}", dimension.location());
                return null;
            }
            if (!level.isLoaded(controllerPos)) {
                RSIntegrationMod.LOGGER.info("[RSI] resolveNetwork: chunk not loaded at {}, forcing load", controllerPos);
                level.getChunk(controllerPos);
            }
            BlockEntity be = level.getBlockEntity(controllerPos);
            if (be == null) {
                RSIntegrationMod.LOGGER.info("[RSI] resolveNetwork: no BlockEntity at pos={} dim={}",
                        controllerPos, dimension.location());
                return null;
            }
            if (be instanceof INetworkNode node) {
                INetwork net = node.getNetwork();
                if (net != null) return net;
                RSIntegrationMod.LOGGER.info("[RSI] resolveNetwork: INetworkNode at {} has null network", controllerPos);
            }
            // Fallback: ControllerBlockEntity (and some other RS blocks) do not implement
            // INetworkNode but still have a getNetwork() method via their own hierarchy.
            try {
                java.lang.reflect.Method getNetwork = be.getClass().getMethod("getNetwork");
                Object result = getNetwork.invoke(be);
                if (result instanceof INetwork net) {
                    RSIntegrationMod.LOGGER.info("[RSI] resolveNetwork: got network via reflection from {}",
                            be.getClass().getName());
                    return net;
                }
            } catch (Exception ignored) {
                // Not every block entity has getNetwork()
            }
            RSIntegrationMod.LOGGER.info("[RSI] resolveNetwork: BE at {} is {} (no network accessible)",
                    controllerPos, be.getClass().getName());
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.info("[RSI] resolveNetwork error: {}", e.toString());
        }
        return null;
    }

    @Nullable
    private static INetwork resolveFromNetworkItem(ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        // Standard path: RS NetworkItem / WirelessGrid
        if (NetworkItem.isValid(stack)) {
            ResourceKey<Level> dim = NetworkItem.getDimension(stack);
            RSIntegrationMod.LOGGER.debug("[RSI] NetworkItem.isValid=true item={} dim={} x={} y={} z={}",
                    stack.getDescriptionId(),
                    dim != null ? dim.location() : "null",
                    NetworkItem.getX(stack),
                    NetworkItem.getY(stack),
                    NetworkItem.getZ(stack));
            if (dim != null) {
                BlockPos pos = new BlockPos(
                        NetworkItem.getX(stack),
                        NetworkItem.getY(stack),
                        NetworkItem.getZ(stack));
                INetwork net = resolveNetwork(player.server, dim, pos);
                if (net != null) return net;
                RSIntegrationMod.LOGGER.info("[RSI] NetworkItem valid but resolveNetwork "
                        + "returned null for pos={} dim={}", pos, dim.location());
            }
        } else {
            RSIntegrationMod.LOGGER.debug("[RSI] NetworkItem.isValid=false item={}", stack.getDescriptionId());
        }

        // Fallback: read NBT directly (some RS item variants store network
        // info under different keys or class hierarchy prevents isValid())
        if (stack.hasTag()) {
            CompoundTag tag = stack.getTag();
            RSIntegrationMod.LOGGER.debug("[RSI] NBT fallback for item={} keys={}",
                    stack.getDescriptionId(), tag.getAllKeys());
            INetwork net = resolveFromNbt(player.server, tag);
            if (net != null) return net;
        }

        RSIntegrationMod.LOGGER.debug("[RSI] resolveFromNetworkItem ALL PATHS FAILED for item={}",
                stack.getDescriptionId());
        return null;
    }

    @Nullable
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

    @Nullable
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

    @Nullable
    private static INetwork resolveHeuristic(MinecraftServer server, CompoundTag tag) {
        // Scan for any string value that parses as a ResourceLocation (potential dim)
        // and any three int values whose keys loosely match x/y/z.
        ResourceLocation dimId = null;
        Integer x = null, y = null, z = null;

        for (String key : tag.getAllKeys()) {
            String lk = key.toLowerCase();
            // Dimension detection: any key containing "dim" or "world" or "level"
            // with a string value that parses as ResourceLocation
            if (dimId == null && (lk.contains("dim") || lk.contains("world") || lk.contains("level"))) {
                var val = tag.get(key);
                if (val instanceof net.minecraft.nbt.StringTag st) {
                    ResourceLocation parsed = ResourceLocation.tryParse(st.getAsString());
                    if (parsed != null && !parsed.getPath().isEmpty()) {
                        dimId = parsed;
                    }
                } else if (val instanceof net.minecraft.nbt.NumericTag) {
                    // Not a string, can't be a dimension
                }
            }
            // X detection
            if (x == null && (lk.equals("x") || lk.equals("nodex") || lk.endsWith("_x")
                    || lk.equals("posx") || lk.equals("coordx") || lk.equals("blockx"))) {
                x = tag.getInt(key);
            }
            // Y detection
            if (y == null && (lk.equals("y") || lk.equals("nodey") || lk.endsWith("_y")
                    || lk.equals("posy") || lk.equals("coordy") || lk.equals("blocky"))) {
                y = tag.getInt(key);
            }
            // Z detection
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

    @Nullable
    private static INetwork resolveFromNearbyNode(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos playerPos = player.blockPosition();
        int range = 32;
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

    @Nullable
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
            return null;
        }
    }

    public static ItemStack extractFromNetwork(INetwork network, Ingredient ingredient, int count) {
        try {
            var cache = network.getItemStorageCache();
            if (cache == null) return ItemStack.EMPTY;
            var list = cache.getList();
            if (list == null) return ItemStack.EMPTY;

            // Snapshot matching entries first, then extract.
            // Extracting inside a live for-each over list.getStacks() can
            // trigger ConcurrentModificationException when RS removes a
            // fully-depleted entry, causing commit to fail and abort the chain.
            var snapshot = new java.util.ArrayList<ItemStack>();
            for (var entry : list.getStacks()) {
                ItemStack stored = entry.getStack();
                if (!stored.isEmpty() && ingredient.test(stored)) {
                    snapshot.add(stored.copy());
                }
            }

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

            if (!result.isEmpty() && result.getCount() >= count) {
                return result;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI] extractFromNetwork error", e);
        }
        return ItemStack.EMPTY;
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
            RSIntegrationMod.LOGGER.info("[RSI] hasItemInNetwork error: {}", e.toString());
        }
        return false;
    }

    public static ItemStack tryExtractFromPlayerRS(ServerPlayer player, Ingredient ingredient, int count) {
        INetwork network = resolveNetworkFromPlayer(player);
        if (network == null) return ItemStack.EMPTY;
        return extractFromNetwork(network, ingredient, count);
    }

    public static boolean hasItemInPlayerRS(ServerPlayer player, Ingredient ingredient) {
        INetwork network = resolveNetworkFromPlayer(player);
        if (network == null) return false;
        return hasItemInNetwork(network, ingredient);
    }
}
