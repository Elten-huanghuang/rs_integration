package com.huanghuang.rsintegration.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.Optional;

public interface IBindingHook {

    boolean matches(ItemStack held);

    Optional<AltarBinding> createBinding(ItemStack held);

    ItemStack extractItem(ServerPlayer player, AltarBinding binding, Ingredient ingredient, int count);
}
