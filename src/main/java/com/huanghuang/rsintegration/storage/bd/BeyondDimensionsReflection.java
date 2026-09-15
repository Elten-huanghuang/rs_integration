package com.huanghuang.rsintegration.storage.bd;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageDiscoveryResult;
import com.huanghuang.rsintegration.storage.StorageDiscoveryStatus;
import com.huanghuang.rsintegration.storage.StorageNetworkDescriptor;
import com.huanghuang.rsintegration.storage.StorageResolutionResult;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageResolutionStatus;
import com.huanghuang.rsintegration.storage.StorageSession;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small, fail-closed reflection boundary for the optional BD API. */
public final class BeyondDimensionsReflection {
    private static final String NET = "com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet";
    private static final String KEY = "com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey";
    private static final String FLUID_KEY = "com.wintercogs.beyonddimensions.api.storage.key.impl.FluidStackKey";
    public static StorageResolutionResult resolvePrimary(ServerPlayer player, StorageBackendId id) {
        StorageResolutionResult primary;
        try {
            Object net = invokeStatic(NET, "getPrimaryNetFromPlayer", new Class<?>[]{net.minecraft.world.entity.player.Player.class}, player);
            primary = session(net, player, id);
        } catch (Exception | LinkageError e) {
            // Older BD builds may not expose the implicit-primary helper.
            // Discovery below is the compatible path in that case.
            primary = StorageResolutionResult.failure(StorageResolutionStatus.FAILED);
        }
        if (primary.resolved()) return primary;

        // The BD primary-net API only describes the player's implicit network.
        // A bound net_terminal_item is an independent access path and may
        // point at another network (or at one absent from that index).
        try {
            StorageDiscoveryResult discovered = discover(player, id);
            if (discovered.successful()) {
                for (StorageNetworkDescriptor descriptor : discovered.networks()) {
                    try {
                        int networkId = Integer.parseInt(descriptor.reference().networkId());
                        StorageResolutionResult resolved = resolveById(networkId, player, id);
                        if (resolved.resolved()) {
                            RSIntegrationMod.debug("[RSI-Storage] BD default fallback selected network {} for {}",
                                    networkId, player.getGameProfile().getName());
                            return resolved;
                        }
                    } catch (NumberFormatException ignored) {
                        // A malformed descriptor must not prevent other
                        // discovered networks from being considered.
                    }
                }
            }
        } catch (Exception | LinkageError ignored) {
            // Preserve the primary failure status when discovery itself fails.
        }
        return primary;
    }

    static StorageDiscoveryResult discover(ServerPlayer player, StorageBackendId id) {
        try {
            Object value = invokeStatic(NET, "getAllNetFromPlayer", new Class<?>[]{net.minecraft.world.entity.player.Player.class}, player);
            List<StorageNetworkDescriptor> networks = new ArrayList<>();
            if (value instanceof Collection<?> nets) {
                for (Object net : nets) {
                    addNetwork(networks, net, player, id);
                }
            }
            // A bound BD terminal is an independent access path.  Its holder
            // may not be present in the network's player index, but the
            // terminal itself carries the authorized network id.
            for (int networkId : boundNetworkIds(player)) {
                Object net = invokeStatic(NET, "getNetFromId", new Class<?>[]{int.class}, networkId);
                addNetwork(networks, net, player, id);
            }
            RSIntegrationMod.debug("[RSI-Storage] BD discovery player={} networks={} refs={}",
                    player.getGameProfile().getName(), networks.size(),
                    networks.stream().map(network -> network.reference().networkId()).toList());
            return StorageDiscoveryResult.success(networks);
        } catch (Exception | LinkageError e) {
            return StorageDiscoveryResult.failure(StorageDiscoveryStatus.FAILED);
        }
    }

    static StorageResolutionResult resolveById(int id, ServerPlayer player, StorageBackendId backendId) {
        try {
            Object net = invokeStatic(NET, "getNetFromId", new Class<?>[]{int.class}, id);
            return session(net, player, backendId);
        } catch (Exception | LinkageError e) {
            return StorageResolutionResult.failure(StorageResolutionStatus.FAILED);
        }
    }

    static boolean isCurrentNetwork(Object expected, String networkId) {
        try {
            int id = Integer.parseInt(networkId);
            Object current = invokeStatic(NET, "getNetFromId", new Class<?>[]{int.class}, id);
            return current != null && current == expected;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    private static StorageResolutionResult session(Object net, ServerPlayer player, StorageBackendId id) {
        if (net == null) return StorageResolutionResult.failure(StorageResolutionStatus.NOT_FOUND);
        try {
            int networkId = ((Number) net.getClass().getMethod("getId").invoke(net)).intValue();
            // BD keeps owners/managers in dedicated sets; they are not required
            // to also appear in getPlayers().  The previous check therefore
            // rejected the player who created the network, making discovery
            // return an empty list whenever only the owner was online.
            boolean allowed = hasPlayerAccess(net, player)
                    || hasBoundNetworkItem(player, networkId);
            if (!allowed) return StorageResolutionResult.failure(StorageResolutionStatus.DENIED);
            return StorageResolutionResult.resolved(new BeyondDimensionsSession(net,
                    new StorageReference(id, Integer.toString(networkId))));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return StorageResolutionResult.failure(StorageResolutionStatus.FAILED);
        }
    }

    static boolean hasPlayerAccess(Object net, ServerPlayer player) throws ReflectiveOperationException {
        Method owner = net.getClass().getMethod("isOwner",
                net.minecraft.world.entity.player.Player.class);
        Method manager = net.getClass().getMethod("isManager",
                net.minecraft.world.entity.player.Player.class);
        if (Boolean.TRUE.equals(owner.invoke(net, player))
                || Boolean.TRUE.equals(manager.invoke(net, player))) {
            return true;
        }
        Object members = net.getClass().getMethod("getPlayers").invoke(net);
        return members instanceof Set<?> set && set.contains(player.getUUID());
    }

    private static void addNetwork(List<StorageNetworkDescriptor> networks, Object net,
                                   ServerPlayer player, StorageBackendId id) {
        StorageResolutionResult result = session(net, player, id);
        if (!result.resolved()) return;
        StorageSession session = result.session().orElseThrow();
        StorageReference reference = session.reference();
        if (networks.stream().anyMatch(existing -> existing.reference().equals(reference))) return;
        networks.add(new StorageNetworkDescriptor(reference, reference.networkId(),
                networks.isEmpty(), session.capabilities()));
    }

    private static Set<Integer> boundNetworkIds(ServerPlayer player) {
        Set<Integer> ids = new HashSet<>();
        addBoundNetworkIds(ids, player.getInventory().items);
        addBoundNetworkIds(ids, player.getInventory().armor);
        addBoundNetworkIds(ids, player.getInventory().offhand);
        // BD's own terminal supports Curios, so it must be treated as an
        // authenticated network item everywhere we resolve or authorize a
        // storage session. CuriosAccess is reflective and returns an empty
        // list when Curios is not installed.
        addBoundNetworkIds(ids,
                com.huanghuang.rsintegration.util.CuriosAccess.stacks(player));
        return ids;
    }

    private static void addBoundNetworkIds(Set<Integer> ids, Iterable<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            try {
                Class<?> netedItem = Class.forName(
                        "com.wintercogs.beyonddimensions.common.item.NetedItem", false,
                        BeyondDimensionsReflection.class.getClassLoader());
                if (!netedItem.isInstance(stack.getItem())) continue;
                Method getNetId = netedItem.getMethod("getNetId", ItemStack.class);
                int networkId = ((Number) getNetId.invoke(null, stack)).intValue();
                if (networkId >= 0) ids.add(networkId);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Ignore malformed or unrelated stacks and continue scanning
                // the remaining inventory entries.
            }
        }
    }

    static boolean hasBoundNetworkItem(ServerPlayer player, int networkId) {
        return boundNetworkIds(player).contains(networkId);
    }

    public static boolean isAuthorizedNetwork(ServerPlayer player, int networkId) {
        try {
            Object net = invokeStatic(NET, "getNetFromId", new Class<?>[]{int.class}, networkId);
            return net != null && (hasPlayerAccess(net, player) || hasBoundNetworkItem(player, networkId));
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    /** Returns whether BD still has a live network object for this id. */
    public static boolean networkExists(int networkId) {
        if (networkId < 0) return false;
        try {
            return invokeStatic(NET, "getNetFromId", new Class<?>[]{int.class}, networkId) != null;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    private static Object invokeStatic(String className, String name, Class<?>[] types, Object... args) throws Exception {
        Class<?> type = Class.forName(className, false, BeyondDimensionsReflection.class.getClassLoader());
        return type.getMethod(name, types).invoke(null, args);
    }

    static Object itemKey(ItemStack stack) throws Exception {
        Class<?> type = Class.forName(KEY, false, BeyondDimensionsReflection.class.getClassLoader());
        Constructor<?> constructor = type.getConstructor(ItemStack.class);
        return constructor.newInstance(stack.copyWithCount(1));
    }

    static Object fluidKey(Fluid fluid, long amount) throws Exception {
        Class<?> type = Class.forName(FLUID_KEY, false, BeyondDimensionsReflection.class.getClassLoader());
        Constructor<?> constructor = type.getConstructor(FluidStack.class);
        return constructor.newInstance(new FluidStack(fluid, Math.toIntExact(amount)));
    }

    static FluidStack fluidStack(Object key) throws Exception {
        return ((FluidStack) key.getClass().getMethod("getReadOnlyStack").invoke(key)).copy();
    }

    static ItemStack keyStack(Object key) throws Exception {
        return ((ItemStack) key.getClass().getMethod("getReadOnlyStack").invoke(key)).copy();
    }

    static ItemStack keyStack(Object key, Set<net.minecraft.world.item.Item> itemTypes) throws Exception {
        ItemStack stack = (ItemStack) key.getClass().getMethod("getReadOnlyStack").invoke(key);
        // Inspect only the item type on BD's read-only stack; copy matching
        // stacks before handing them to any identity or ingredient code.
        return itemTypes == null || itemTypes.contains(stack.getItem()) ? stack.copy() : ItemStack.EMPTY;
    }

    /** UnifiedStorage can contain fluids, mana and other typed keys. */
    static boolean isItemKey(Object key) {
        if (key == null) return false;
        try {
            Object stackClass = key.getClass().getMethod("getStackClass").invoke(key);
            return stackClass == ItemStack.class;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    static boolean isFluidKey(Object key) {
        if (key == null) return false;
        try {
            Object stackClass = key.getClass().getMethod("getStackClass").invoke(key);
            return stackClass == FluidStack.class;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    static long amount(Object keyAmount) throws Exception {
        return ((Number) keyAmount.getClass().getMethod("amount").invoke(keyAmount)).longValue();
    }

    static Object key(Object keyAmount) throws Exception {
        return keyAmount.getClass().getMethod("key").invoke(keyAmount);
    }
}
