package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoints;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.storage.StoredItem;
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
        if (count <= 0 || ingredient.isEmpty()) return ItemStack.EMPTY;
        int networkId = binding.data().getInt(KEY_NETWORK_ID);
        if (networkId < 0) return ItemStack.EMPTY;
        Optional<CraftStorageEndpoint> endpoint = CraftStorageEndpoints.resolve(
                new StorageReference(BACKEND, Integer.toString(networkId)), player);
        if (endpoint.isEmpty()) return ItemStack.EMPTY;
        CraftStorageEndpoint selectedEndpoint = endpoint.orElseThrow();
        var snapshot = selectedEndpoint.snapshot(player).snapshot().orElse(null);
        if (snapshot == null) return ItemStack.EMPTY;
        var match = snapshot.match(ingredient);
        if (!match.successful() || match.items().isEmpty()) return ItemStack.EMPTY;

        StoredItem selected = match.items().stream()
                .filter(candidate -> candidate.amount() >= count)
                .findFirst().orElse(match.items().get(0));
        int requested = (int) Math.min((long) count, selected.amount());
        return StorageRestockSupport.extract(selectedEndpoint, player,
                selected.stack(), requested);
    }

    private static int readNetworkId(ItemStack stack) {
        if (!stack.hasTag() || stack.getTag() == null) return -1;
        return stack.getTag().contains("NetId", net.minecraft.nbt.Tag.TAG_INT)
                ? stack.getTag().getInt("NetId") : -1;
    }
}
