package com.huanghuang.rsintegration.mods.distantworlds.client;

import com.huanghuang.rsintegration.mods.distantworlds.LithumAltarRecipeResolver;
import com.huanghuang.rsintegration.mods.distantworlds.LithumAltarRecipeWrapper;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class LithumAltarFironRecipeCategory implements IRecipeCategory<LithumAltarRecipeWrapper> {
    public static final RecipeType<LithumAltarRecipeWrapper> TYPE = RecipeType.create(
            "rs_integration", "lithum_altar_firon", LithumAltarRecipeWrapper.class);
    private static final int WIDTH = 168;
    private static final int HEIGHT = 92;
    private final IDrawable icon;

    public LithumAltarFironRecipeCategory(IGuiHelper guiHelper) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("distant_worlds", "lithum_core");
        this.icon = guiHelper.createDrawableItemStack(
                new net.minecraft.world.item.ItemStack(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id)));
    }

    @Override public RecipeType<LithumAltarRecipeWrapper> getRecipeType() { return TYPE; }
    @Override public Component getTitle() { return Component.translatable("gui.rs_integration.jei.distant_worlds_lithum_altar"); }
    @Override public int getWidth() { return WIDTH; }
    @Override public int getHeight() { return HEIGHT; }
    @Override public IDrawable getIcon() { return icon; }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, LithumAltarRecipeWrapper wrapper, IFocusGroup focuses) {
        var definition = wrapper.definition();
        var all = definition.allMaterials();
        if (!all.isEmpty()) {
            builder.addInputSlot(8, 18).setStandardSlotBackground()
                    .addIngredients(all.get(0).ingredient());
        }
        int count = Math.min(8, Math.max(0, all.size() - 1));
        for (int i = 0; i < count; i++) {
            builder.addInputSlot(38 + (i % 4) * 22, 18 + (i / 4) * 22)
                    .setStandardSlotBackground().addIngredients(all.get(i + 1).ingredient());
        }
        builder.addOutputSlot(137, 30).setOutputSlotBackground().addItemStack(definition.output());
        builder.addInvisibleIngredients(mezz.jei.api.recipe.RecipeIngredientRole.OUTPUT)
                .addItemStack(definition.output());
    }

    @Override
    public void draw(LithumAltarRecipeWrapper wrapper, IRecipeSlotsView slots,
                     GuiGraphics graphics, double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        var definition = wrapper.definition();
        graphics.drawString(font, Component.translatable("gui.rs_integration.jei.lithum_altar_core"), 4, 2, 0x404040, false);
        graphics.drawString(font, Component.translatable("gui.rs_integration.jei.lithum_altar_energy",
                definition.baseMaxEnergy(), definition.baseRecovery()), 4, 70, 0x606060, false);
    }
}
