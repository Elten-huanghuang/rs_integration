package com.huanghuang.rsintegration.storage;

import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoints;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

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
            for (ItemStack stack : com.huanghuang.rsintegration.util.CuriosAccess.stacks(player)) {
                heldNetwork = beyondDimensionsNetworkId(stack);
                if (heldNetwork.isPresent()) break;
            }
        }
        if (heldNetwork.isPresent()) {
            Optional<CraftStorageEndpoint> held = CraftStorageEndpoints.resolve(
                    new StorageReference(BEYOND_DIMENSIONS,
                            Integer.toString(heldNetwork.getAsInt())), player);
            if (held.isPresent()) return held;
        }
        if (isBeyondDimensionsTerminalMenu(player)) {
            for (StorageNetworkDescriptor descriptor :
                    com.huanghuang.rsintegration.RSIntegrationMod.STORAGE_BACKENDS.registry()
                            .discoverNetworksForPlayer(player)) {
                if (!BEYOND_DIMENSIONS.equals(descriptor.reference().backendId())) continue;
                Optional<CraftStorageEndpoint> endpoint = CraftStorageEndpoints.resolve(
                        descriptor.reference(), player);
                if (endpoint.isPresent()) return endpoint;
            }
        }
        return CraftStorageEndpoints.resolveDefault(player);
    }

    private static boolean isBeyondDimensionsTerminalMenu(ServerPlayer player) {
        if (player.containerMenu == null) return false;
        String name = player.containerMenu.getClass().getName();
        return name.equals("com.wintercogs.beyonddimensions.common.menu.DimensionsCraftMenuTerminal")
                || name.contains("beyonddimensions.common.menu.DimensionsCraftMenuTerminal");
    }

    private static OptionalInt beyondDimensionsNetworkId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return OptionalInt.empty();
        net.minecraft.resources.ResourceLocation id =
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
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
        if (endpoint == null || template == null || template.isEmpty() || amount <= 0) {
            return ItemStack.EMPTY;
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
        return merged;
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

    private static void refund(CraftStorageEndpoint endpoint, ServerPlayer player,
                               List<ItemStack> stacks, String reason) {
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            StorageOperationResult result = endpoint.insert(player, stack, false);
            Optional<ItemStack> knownRemainder = result.remainder();
            if (knownRemainder.isEmpty()) {
                com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.error(
                        "[RSI-Restock] {} refund became indeterminate for {} x{}",
                        reason, com.huanghuang.rsintegration.util.ItemStackUtils.registryId(stack), stack.getCount());
                continue;
            }
            ItemStack remainder = knownRemainder.orElse(ItemStack.EMPTY);
            if (!remainder.isEmpty()) {
                com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.error(
                        "[RSI-Restock] {} refund left {} x{}; dropping remainder",
                        reason, com.huanghuang.rsintegration.util.ItemStackUtils.registryId(remainder), remainder.getCount());
                player.drop(remainder, false);
            }
        }
    }
}
