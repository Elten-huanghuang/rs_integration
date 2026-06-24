package com.huanghuang.rsintegration.network;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.item.NetworkItem;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

import java.util.Optional;

public final class RSBindingHook implements IBindingHook {

    public static final RSBindingHook INSTANCE = new RSBindingHook();

    private static final String KEY_DIM = "dim";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";

    private RSBindingHook() {}

    @Override
    public boolean matches(ItemStack held) {
        return NetworkItem.isValid(held);
    }

    @Override
    public Optional<AltarBinding> createBinding(ItemStack held) {
        ResourceKey<Level> dim = NetworkItem.getDimension(held);
        if (dim == null) return Optional.empty();
        BlockPos pos = new BlockPos(
                NetworkItem.getX(held),
                NetworkItem.getY(held),
                NetworkItem.getZ(held)
        );
        CompoundTag data = new CompoundTag();
        data.putString(KEY_DIM, dim.location().toString());
        data.putInt(KEY_X, pos.getX());
        data.putInt(KEY_Y, pos.getY());
        data.putInt(KEY_Z, pos.getZ());
        return Optional.of(new AltarBinding(
                AltarBinding.RS_NETWORK,
                Component.translatable("gui.rs_integration.altar.binding.rs",
                        pos.toShortString()),
                data
        ));
    }

    @Override
    public ItemStack extractItem(ServerPlayer player, AltarBinding binding, Ingredient ingredient, int count) {
        CompoundTag data = binding.data();
        ResourceLocation dimId = ResourceLocation.tryParse(data.getString(KEY_DIM));
        if (dimId == null) return ItemStack.EMPTY;
        ResourceKey<Level> dim = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, dimId);
        BlockPos pos = new BlockPos(data.getInt(KEY_X), data.getInt(KEY_Y), data.getInt(KEY_Z));
        INetwork network = RSIntegration.resolveNetwork(player.server, dim, pos);
        if (network == null) return ItemStack.EMPTY;
        return RSIntegration.extractFromNetwork(network, ingredient, count);
    }

}
