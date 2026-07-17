package com.huanghuang.rsintegration.mods.ironfurnaces;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class IronFurnaceBindingUpdater {

    private IronFurnaceBindingUpdater() {}

    public static void onRecipeTypeChanged(BlockEntity furnace, RecipeType<?> previous,
                                           RecipeType<?> current) {
        if (previous == current || !(furnace.getLevel() instanceof ServerLevel level)) return;
        String replacementPrefix = prefixFor(current);
        if (replacementPrefix == null) return;

        ResourceLocation dimension = level.dimension().location();
        boolean anyChanged = false;
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            boolean changed = updatePlayerBindings(player, dimension, furnace.getBlockPos(), replacementPrefix);
            if (!changed) continue;
            anyChanged = true;
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
        }
        if (anyChanged) {
            AltarBindingRegistry.invalidateScanCache();
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                RSSidePanelNetworkHandler.sendBindingSync(player);
            }
            RSIntegrationMod.LOGGER.debug("[RSI-IronFurnaces] Migrated binding at {} {} to {}",
                    dimension, furnace.getBlockPos(), replacementPrefix);
        }
    }

    static String prefixFor(RecipeType<?> recipeType) {
        if (recipeType == RecipeType.BLASTING) return IronFurnacesRSModule.BLAST_TYPE_ID;
        if (recipeType == RecipeType.SMOKING) return IronFurnacesRSModule.SMOKER_TYPE_ID;
        if (recipeType == RecipeType.SMELTING) return IronFurnacesRSModule.TYPE_ID;
        return null;
    }

    private static boolean updatePlayerBindings(ServerPlayer player, ResourceLocation dimension,
                                                net.minecraft.core.BlockPos pos,
                                                String replacementPrefix) {
        boolean changed = false;
        for (ItemStack stack : player.getInventory().items) {
            changed |= updateStack(stack, dimension, pos, replacementPrefix);
        }
        for (ItemStack stack : player.getInventory().offhand) {
            changed |= updateStack(stack, dimension, pos, replacementPrefix);
        }
        for (ItemStack stack : player.getInventory().armor) {
            changed |= updateStack(stack, dimension, pos, replacementPrefix);
        }
        try {
            var curios = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (curios.isPresent()) {
                for (var handler : curios.get().getCurios().values()) {
                    var stacks = handler.getStacks();
                    for (int slot = 0; slot < stacks.getSlots(); slot++) {
                        changed |= updateStack(stacks.getStackInSlot(slot), dimension, pos, replacementPrefix);
                    }
                }
            }
        } catch (Exception exception) {
            RSIntegrationMod.LOGGER.debug("[RSI-IronFurnaces] Curios binding migration failed", exception);
        }
        return changed;
    }

    private static boolean updateStack(ItemStack stack, ResourceLocation dimension,
                                       net.minecraft.core.BlockPos pos, String replacementPrefix) {
        if (stack.isEmpty()) return false;
        boolean changed = false;
        for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
            if (!dimension.equals(entry.dim()) || !pos.equals(entry.pos())
                    || !isIronFurnaceBlockKey(entry.blockKey())) continue;
            String replacement = BindingStorage.replaceBlockKeyPrefix(entry.blockKey(), replacementPrefix);
            changed |= BindingStorage.replaceBindingBlockKey(
                    stack, dimension, pos, entry.blockKey(), replacement);
        }
        return changed;
    }

    static boolean isIronFurnaceBlockKey(String blockKey) {
        if (blockKey == null) return false;
        int separator = blockKey.indexOf("||");
        String prefix = separator < 0 ? blockKey : blockKey.substring(0, separator);
        return IronFurnacesRSModule.TYPE_ID.equals(prefix)
                || IronFurnacesRSModule.BLAST_TYPE_ID.equals(prefix)
                || IronFurnacesRSModule.SMOKER_TYPE_ID.equals(prefix);
    }
}
