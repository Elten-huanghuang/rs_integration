package com.huanghuang.rsintegration.storage;

import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.SessionCraftStorageEndpoint;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.CuriosAccess;
import com.huanghuang.rsintegration.util.ItemStackUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BiPredicate;

/** Shared backend-neutral extraction helper for GUI restock actions. */
public final class StorageRestockSupport {
    private static final StorageBackendId BEYOND_DIMENSIONS =
            new StorageBackendId("beyonddimensions");

    private StorageRestockSupport() {}

    public static Optional<CraftStorageEndpoint> resolve(ServerPlayer player) {
        return resolveForRetrieval(player).session().map(SessionCraftStorageEndpoint::new);
    }

    /** 保留原生网络选择顺序；无法选定存储端点时保留失败原因。 */
    static StorageResolutionResult resolveForRetrieval(ServerPlayer player) {
        var registry = RSIntegrationMod.STORAGE_BACKENDS.registry();
        StorageResolutionStatus failure = StorageResolutionStatus.NOT_FOUND;
        // A held BD terminal is an explicit user choice for these one-shot
        // GUI actions. This prevents the RS-first backend order from stealing
        // a refill when both networks are installed.
        OptionalInt heldNetwork = beyondDimensionsNetworkId(player.getMainHandItem());
        if (heldNetwork.isEmpty()) {
            heldNetwork = beyondDimensionsNetworkId(player.getOffhandItem());
        }
        // BD terminals can be equipped in a Curios slot. Treat an equipped
        // terminal as an explicit credential too, otherwise resolveDefault()
        // may select an unrelated RS network when both backends are present.
        if (heldNetwork.isEmpty()) {
            for (ItemStack stack : CuriosAccess.stacks(player)) {
                heldNetwork = beyondDimensionsNetworkId(stack);
                if (heldNetwork.isPresent()) break;
            }
        }
        if (heldNetwork.isPresent()) {
            StorageResolutionResult held = registry.resolve(
                    new StorageReference(BEYOND_DIMENSIONS,
                            Integer.toString(heldNetwork.getAsInt())), player);
            if (held.resolved()) return held;
            failure = preferFailure(failure, held.status());
        }
        if (isBeyondDimensionsTerminalMenu(player)) {
            for (StorageNetworkDescriptor descriptor : registry.discoverNetworksForPlayer(player)) {
                if (!BEYOND_DIMENSIONS.equals(descriptor.reference().backendId())) continue;
                StorageResolutionResult result = registry.resolve(descriptor.reference(), player);
                if (result.resolved()) return result;
                failure = preferFailure(failure, result.status());
            }
        }
        for (var candidate : registry.resolveDefaultResultsForPlayer(player)) {
            StorageResolutionResult result = candidate.result();
            if (result.resolved()) return result;
            failure = preferFailure(failure, result.status());
        }
        for (var candidate : registry.discoverAllNetworksForPlayer(player)) {
            StorageDiscoveryResult discovery = candidate.result();
            if (discovery.status() == StorageDiscoveryStatus.FAILED) failure = StorageResolutionStatus.FAILED;
            if (discovery.status() == StorageDiscoveryStatus.DENIED) {
                failure = preferFailure(failure, StorageResolutionStatus.DENIED);
            }
            for (StorageNetworkDescriptor descriptor : discovery.networks()) {
                // 发现 RS 网络坐标不等于玩家具备该网络的访问凭据。
                if ("refinedstorage".equals(descriptor.reference().backendId().value())
                        && !com.huanghuang.rsintegration.network.RSIntegrationNetwork
                                .hasCurrentNetworkAccess(player, descriptor.reference())) continue;
                StorageResolutionResult result = registry.resolve(descriptor.reference(), player);
                if (result.resolved()) return result;
                failure = preferFailure(failure, result.status());
            }
        }
        return StorageResolutionResult.failure(failure);
    }

    private static StorageResolutionStatus preferFailure(StorageResolutionStatus current,
                                                          StorageResolutionStatus candidate) {
        if (candidate == StorageResolutionStatus.FAILED || current == StorageResolutionStatus.FAILED) {
            return StorageResolutionStatus.FAILED;
        }
        if (candidate == StorageResolutionStatus.DENIED) return candidate;
        return current;
    }

    private static boolean isBeyondDimensionsTerminalMenu(ServerPlayer player) {
        if (player.containerMenu == null) return false;
        String name = player.containerMenu.getClass().getName();
        return name.equals("com.wintercogs.beyonddimensions.common.menu.DimensionsCraftMenuTerminal")
                || name.contains("beyonddimensions.common.menu.DimensionsCraftMenuTerminal");
    }

    private static OptionalInt beyondDimensionsNetworkId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return OptionalInt.empty();
        ResourceLocation id =
                ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null || !"beyonddimensions".equals(id.getNamespace())
                || !"net_terminal_item".equals(id.getPath())) {
            return OptionalInt.empty();
        }
        try {
            Class<?> netedItem = Class.forName(
                    "com.wintercogs.beyonddimensions.common.item.NetedItem", false,
                    StorageRestockSupport.class.getClassLoader());
            if (!netedItem.isInstance(stack.getItem())) return OptionalInt.empty();
            Method getNetId = netedItem.getMethod("getNetId", ItemStack.class);
            int networkId = ((Number) getNetId.invoke(null, stack)).intValue();
            return networkId >= 0 ? OptionalInt.of(networkId) : OptionalInt.empty();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return OptionalInt.empty();
        }
    }

    public static boolean canExtract(CraftStorageEndpoint endpoint, ServerPlayer player) {
        return endpoint != null && endpoint.session().hasPermission(player, StoragePermission.EXTRACT);
    }

    public static ItemStack extract(CraftStorageEndpoint endpoint, ServerPlayer player,
                                    ItemStack template, int amount) {
        return extractOutcome(endpoint, player, template, amount).stack();
    }

    public record Extraction(ItemStack stack, boolean confirmedMissing, boolean failed) {
        public Extraction(ItemStack stack, boolean confirmedMissing) {
            this(stack, confirmedMissing, false);
        }
    }

    public static Extraction extractOutcome(CraftStorageEndpoint endpoint, ServerPlayer player,
                                            ItemStack template, int amount) {
        if (endpoint == null || template == null || template.isEmpty() || amount <= 0) {
            return new Extraction(ItemStack.EMPTY, false, true);
        }
        StorageOperationResult result = endpoint.extractExact(player, template, amount, false);
        refund(endpoint, player, result.recoveryStacks(), "recovery");
        List<ItemStack> extracted = result.extractedStacks();
        ItemStack merged = mergeExactStacks(template, extracted,
                (left, right) -> endpoint.session().itemKey(left)
                        .equals(endpoint.session().itemKey(right)));
        if (merged.isEmpty() && !extracted.isEmpty()) {
            refund(endpoint, player, extracted, "invalid exact extraction");
        }
        boolean missing = confirmedMissing(result);
        boolean failed = !missing && (merged.isEmpty() || !(result.complete()
                || result.status() == StorageOperationStatus.PARTIAL));
        return new Extraction(merged, missing, failed);
    }

    public static boolean confirmedMissing(StorageOperationResult result) {
        return result.kind() == StorageOperationResult.Kind.EXTRACT
                && result.status() == StorageOperationStatus.NOT_FOUND
                && result.transferredAmount().orElse(-1L) == 0L
                && result.recoveryStacks().isEmpty();
    }

    static ItemStack mergeExactStacks(ItemStack template, List<ItemStack> stacks,
                                      BiPredicate<ItemStack, ItemStack> sameIdentity) {
        if (template == null || template.isEmpty() || stacks == null || sameIdentity == null) {
            return ItemStack.EMPTY;
        }
        ItemStack merged = ItemStack.EMPTY;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            if (!sameIdentity.test(template, stack)) return ItemStack.EMPTY;
            if (merged.isEmpty()) merged = stack.copy();
            else merged.grow(stack.getCount());
        }
        return merged;
    }

    static boolean refund(CraftStorageEndpoint endpoint, ServerPlayer player,
                          List<ItemStack> stacks, String reason) {
        boolean known = true;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            StorageOperationResult result;
            try {
                result = endpoint.insert(player, stack, false);
            } catch (RuntimeException | LinkageError failure) {
                RSIntegrationMod.LOGGER.error("[RSI-Restock] {} refund failed for {}", reason, stack, failure);
                known = false;
                continue;
            }
            Optional<ItemStack> knownRemainder = result.remainder();
            if (knownRemainder.isEmpty()) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-Restock] {} refund became indeterminate for {} x{}",
                        reason, ItemStackUtils.registryId(stack), stack.getCount());
                known = false;
                continue;
            }
            ItemStack remainder = knownRemainder.orElse(ItemStack.EMPTY);
            if (!remainder.isEmpty()) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-Restock] {} refund left {} x{}; dropping remainder",
                        reason, ItemStackUtils.registryId(remainder), remainder.getCount());
                player.drop(remainder, false);
            }
        }
        return known;
    }
}
