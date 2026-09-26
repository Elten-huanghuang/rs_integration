package com.huanghuang.rsintegration.network.binding;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.List;

/**
 * RS-specific resolution for bindings.  Keeping this class out of the event
 * subscriber class is important: Forge reflects over subscriber method
 * signatures even when Refined Storage is absent.
 */
public final class RSAltarBindingResolver {
    private RSAltarBindingResolver() {}

    @Nullable
    public static INetwork resolveNetworkForAltar(ServerPlayer player,
                                                   ResourceKey<Level> dim,
                                                   BlockPos altarPos) {
        List<AltarBinding> bindings =
                AltarBindingRegistry.bindingsFor(player, dim, altarPos);
        if (bindings.isEmpty()
                && AltarBindingRegistry.rebuildBindingFromNBT(player, dim, altarPos)) {
            bindings = AltarBindingRegistry.bindingsFor(player, dim, altarPos);
        }
        for (AltarBinding binding : bindings) {
            INetwork network = resolveNetworkForBinding(player, binding);
            if (network != null) return network;
        }
        return null;
    }

    @Nullable
    public static INetwork resolveNetworkForBinding(ServerPlayer player,
                                                    AltarBinding binding) {
        if (!AltarBinding.RS_NETWORK.equals(binding.type())) return null;
        try {
            CompoundTag data = binding.data();
            ResourceLocation dimId =
                    ResourceLocation.tryParse(data.getString("dim"));
            if (dimId == null) return null;
            ResourceKey<Level> netDim = ResourceKey.create(
                    Registries.DIMENSION, dimId);
            BlockPos netPos = new BlockPos(data.getInt("x"), data.getInt("y"), data.getInt("z"));
            return RSIntegrationNetwork.resolveNetwork(player.server, netDim, netPos);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    public static INetwork resolveNetworkFromAnyBinding(ServerPlayer player) {
        final INetwork[] result = {null};
        AltarBindingRegistry.forEachInventoryGroup(player, stacks -> {
            if (result[0] != null) return;
            for (ItemStack stack : stacks) {
                if (stack.isEmpty()) continue;
                for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                    ResourceKey<Level> altarDim = ResourceKey.create(
                            Registries.DIMENSION, entry.dim());
                    List<AltarBinding> bindings = AltarBindingRegistry.bindingsFor(
                            player, altarDim, entry.pos());
                    if (bindings.isEmpty()
                            && AltarBindingRegistry.rebuildBindingFromNBT(
                                    player, altarDim, entry.pos())) {
                        bindings = AltarBindingRegistry.bindingsFor(
                                player, altarDim, entry.pos());
                    }
                    for (AltarBinding binding : bindings) {
                        INetwork network = resolveNetworkForBinding(player, binding);
                        if (network != null) {
                            result[0] = network;
                            return;
                        }
                    }
                }
            }
        });
        return result[0];
    }
}
