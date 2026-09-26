package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.network.binding.BindingStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import com.huanghuang.rsintegration.util.CuriosAccess;
import net.minecraft.client.Minecraft;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/** Client-side binding backend detection without linking RS packet classes. */
public final class BindingBackendResolver {
    private BindingBackendResolver() {}

    public static boolean isBeyondDimensionsBinding(ResourceLocation dim, BlockPos pos) {
        if (!ModList.get().isLoaded("beyonddimensions")) return false;
        var player = Minecraft.getInstance().player;
        if (player == null || dim == null || pos == null) return false;
        List<ItemStack> stacks = new ArrayList<>();
        stacks.addAll(player.getInventory().items);
        stacks.addAll(player.getInventory().offhand);
        stacks.addAll(player.getInventory().armor);
        try {
            stacks.addAll(CuriosAccess.stacks(player));
        } catch (RuntimeException ignored) {
            // Curios is optional; inventory and offhand bindings remain valid.
        }
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            ResourceLocation itemId = ForgeRegistries.ITEMS
                    .getKey(stack.getItem());
            if (itemId == null || !"beyonddimensions".equals(itemId.getNamespace())
                    || !"net_terminal_item".equals(itemId.getPath())) continue;
            if (BindingStorage.hasBinding(stack, dim, pos)) return true;
        }
        return false;
    }
}
