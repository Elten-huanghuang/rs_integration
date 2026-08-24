package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoints;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.Optional;

/** Binding adapter for a BD portable terminal. Native BD classes stay optional. */
public final class BeyondDimensionsBindingHook implements IBindingHook {
    public static final BeyondDimensionsBindingHook INSTANCE = new BeyondDimensionsBindingHook();
    private static final ResourceLocation TERMINAL =
            ResourceLocation.fromNamespaceAndPath("beyonddimensions", "net_terminal_item");
    private static final StorageBackendId BACKEND = new StorageBackendId("beyonddimensions");
    private static final String KEY_NETWORK_ID = "networkId";

    private BeyondDimensionsBindingHook() {}

    @Override
    public boolean matches(ItemStack held) {
        ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(held.getItem());
        return TERMINAL.equals(id);
    }

    @Override
    public Optional<AltarBinding> createBinding(ItemStack held) {
        int networkId = readNetworkId(held);
        if (networkId < 0) return Optional.empty();
        var data = new net.minecraft.nbt.CompoundTag();
        data.putInt(KEY_NETWORK_ID, networkId);
        return Optional.of(new AltarBinding(AltarBinding.BD_NETWORK,
                Component.translatable("rsi.binding.bd_network", networkId), data));
    }

    @Override
    public ItemStack extractItem(ServerPlayer player, AltarBinding binding,
                                 Ingredient ingredient, int count) {
        int networkId = binding.data().getInt(KEY_NETWORK_ID);
        if (networkId < 0) return ItemStack.EMPTY;
        Optional<CraftStorageEndpoint> endpoint = CraftStorageEndpoints.resolve(
                new StorageReference(BACKEND, Integer.toString(networkId)), player);
        if (endpoint.isEmpty()) return ItemStack.EMPTY;
        StorageOperationResult result = endpoint.orElseThrow().extractMatching(
                player, ingredient, count, false);
        return result.extractedStacks().stream().reduce(ItemStack.EMPTY, (left, right) -> {
            if (left.isEmpty()) return right.copy();
            if (!ItemStack.isSameItemSameTags(left, right)) return left;
            ItemStack merged = left.copy();
            merged.grow(right.getCount());
            return merged;
        });
    }

    private static int readNetworkId(ItemStack stack) {
        if (!stack.hasTag() || stack.getTag() == null) return -1;
        return stack.getTag().contains("NetId", net.minecraft.nbt.Tag.TAG_INT)
                ? stack.getTag().getInt("NetId") : -1;
    }
}
