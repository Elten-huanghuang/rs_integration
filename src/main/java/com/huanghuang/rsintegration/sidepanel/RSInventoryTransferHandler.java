package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.util.ModIds;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fml.ModList;

import java.util.Optional;

/**
 * JEI universal recipe transfer handler that activates the standard JEI "+"
 * button with any open container (no RS Grid GUI required).
 * <p>
 * RS's own {@code GridRecipeTransferHandler} only matches
 * {@code GridContainerMenu}, so the "+" button disappears when the player
 * closes the Grid.  This handler matches any {@code AbstractContainerMenu},
 * giving the same one-click JEI→RS→inventory transfer whenever the RS side
 * panel is enabled.
 */
public final class RSInventoryTransferHandler
        implements IUniversalRecipeTransferHandler<AbstractContainerMenu> {

    @Override
    public Class<? extends AbstractContainerMenu> getContainerClass() {
        return AbstractContainerMenu.class;
    }

    @Override
    public Optional<MenuType<AbstractContainerMenu>> getMenuType() {
        return Optional.empty();
    }

    @Override
    public IRecipeTransferError transferRecipe(AbstractContainerMenu container, Object recipe,
                                                IRecipeSlotsView recipeSlots,
                                                Player player, boolean maxTransfer,
                                                boolean doTransfer) {
        if (!doTransfer) return null;
        if (!RSIntegrationConfig.ENABLE_RS_SIDE_PANEL.get()) return null;
        if (!ModList.get().isLoaded(ModIds.REFINED_STORAGE)) return null;

        ResourceLocation recipeId = extractRecipeId(recipe);
        if (recipeId == null) return null;

        RSSidePanelNetworkHandler.CHANNEL.sendToServer(
                new RSInventoryTransferPacket(recipeId));
        return null;
    }

    private static ResourceLocation extractRecipeId(Object recipe) {
        if (recipe instanceof Recipe<?> r) {
            return r.getId();
        }
        try {
            return (ResourceLocation) recipe.getClass().getMethod("getId").invoke(recipe);
        } catch (Exception e) {
            return null;
        }
    }
}
