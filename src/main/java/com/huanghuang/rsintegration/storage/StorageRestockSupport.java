package com.huanghuang.rsintegration.storage;

import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoints;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/** Shared backend-neutral extraction helper for GUI restock actions. */
public final class StorageRestockSupport {
    private StorageRestockSupport() {}

    public static Optional<CraftStorageEndpoint> resolve(ServerPlayer player) {
        // A held BD terminal is an explicit user choice for these one-shot
        // GUI actions. This prevents the RS-first backend order from stealing
        // a refill when both networks are installed.
        if (isBeyondDimensionsTerminal(player.getMainHandItem())
                || isBeyondDimensionsTerminal(player.getOffhandItem())
                || isBeyondDimensionsTerminalMenu(player)) {
            for (StorageNetworkDescriptor descriptor :
                    com.huanghuang.rsintegration.RSIntegrationMod.STORAGE_BACKENDS.registry()
                            .discoverNetworksForPlayer(player)) {
                if (!"beyonddimensions".equals(descriptor.reference().backendId().value())) continue;
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

    private static boolean isBeyondDimensionsTerminal(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        net.minecraft.resources.ResourceLocation id =
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && "beyonddimensions".equals(id.getNamespace())
                && "net_terminal_item".equals(id.getPath());
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
        return merge(result.extractedStacks());
    }

    private static ItemStack merge(List<ItemStack> stacks) {
        ItemStack merged = ItemStack.EMPTY;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            if (merged.isEmpty()) merged = stack.copy();
            else if (ItemStack.isSameItemSameTags(merged, stack)) merged.grow(stack.getCount());
        }
        return merged;
    }
}
