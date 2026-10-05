package com.huanghuang.rsintegration.network;

import com.huanghuang.rsintegration.craftingstation.CraftingStationAccess;
import com.huanghuang.rsintegration.craftingstation.CraftingStationJeiTransferPacket;
import com.huanghuang.rsintegration.craftingstation.CraftingStationMode;
import com.huanghuang.rsintegration.craftingstation.CraftingStationModePacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.integration.jei.GridRecipeTransferHandler;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import mezz.jei.api.recipe.RecipeType;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 将原版/模组锻造配方填入 RSI 内嵌锻造台。 */
public final class SmithingJeiTransferHandler implements IRecipeTransferHandler<GridContainerMenu, Object> {
    @Nullable
    private static SmithingJeiTransferHandler activeHandler;
    private final IRecipeTransferHandlerHelper helper;

    public SmithingJeiTransferHandler(IRecipeTransferHandlerHelper helper) {
        this.helper = helper;
        activeHandler = this;
    }

    @Override
    public Class<GridContainerMenu> getContainerClass() {
        return GridContainerMenu.class;
    }

    @Override
    public Optional<MenuType<GridContainerMenu>> getMenuType() {
        return Optional.empty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public RecipeType<Object> getRecipeType() {
        return null;
    }

    public static IRecipeTransferError intercept(GridContainerMenu container, Object recipe,
                                                  IRecipeSlotsView recipeSlots, Player player,
                                                  boolean maxTransfer, boolean doTransfer) {
        SmithingJeiTransferHandler handler = activeHandler;
        return handler == null ? null : handler.transferRecipe(container, recipe, recipeSlots,
                player, maxTransfer, doTransfer);
    }

    @Override
    public IRecipeTransferError transferRecipe(GridContainerMenu container, Object recipe,
                                                IRecipeSlotsView recipeSlots, Player player,
                                                boolean maxTransfer, boolean doTransfer) {
        CraftingStationMode targetMode;
        if (recipe instanceof SmithingRecipe) targetMode = CraftingStationMode.SMITHING;
        else if (recipe instanceof StonecutterRecipe) targetMode = CraftingStationMode.STONECUTTER;
        else {
            return GridRecipeTransferHandler.INSTANCE.transferRecipe(container, recipe, recipeSlots,
                    player, maxTransfer, doTransfer);
        }
        if (container.getGrid() == null
                || container.getGrid().getGridType() != GridType.CRAFTING) {
            return null;
        }
        if (!doTransfer) return null;
        List<List<ItemStack>> options = new ArrayList<>();
        for (IRecipeSlotView view : recipeSlots.getSlotViews(RecipeIngredientRole.INPUT)) {
            options.add(view.getItemStacks().map(ItemStack::copy).toList());
            int targetSlots = targetMode == CraftingStationMode.SMITHING ? 3 : 1;
            if (options.size() == targetSlots) break;
        }
        // 模式校验、切换和物品提取全部由服务端按顺序完成。
        NetworkHandler.CHANNEL.sendToServer(new CraftingStationJeiTransferPacket(targetMode, options));
        return null;
    }
}
