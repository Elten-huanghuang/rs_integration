package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.crafting.fluid.FluidContainerRecipe;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class FluidContainerRecipeCategory implements IRecipeCategory<FluidContainerRecipe> {
    public static final RecipeType<FluidContainerRecipe> TYPE = RecipeType.create(
            "rs_integration", "fluid_container", FluidContainerRecipe.class);
    private final IDrawable icon;

    public FluidContainerRecipeCategory(IGuiHelper helper) {
        icon = helper.createDrawableItemStack(new ItemStack(Items.BUCKET));
    }

    @Override public RecipeType<FluidContainerRecipe> getRecipeType() { return TYPE; }
    @Override public Component getTitle() { return Component.translatable("gui.rs_integration.jei.fluid_container"); }
    @Override public int getWidth() { return 150; }
    @Override public int getHeight() { return 40; }
    @Override public IDrawable getIcon() { return icon; }
    @Override public ResourceLocation getRegistryName(FluidContainerRecipe recipe) { return recipe.getId(); }

    @Override public void setRecipe(IRecipeLayoutBuilder builder, FluidContainerRecipe recipe, IFocusGroup focuses) {
        int amount = recipe.fluid().getAmount();
        if (recipe.filling()) {
            builder.addInputSlot(8, 10).setStandardSlotBackground().addItemStack(recipe.emptyContainer());
            builder.addInputSlot(36, 10).setStandardSlotBackground()
                    .addIngredient(ForgeTypes.FLUID_STACK, recipe.fluid()).setFluidRenderer(amount, false, 16, 16);
            builder.addOutputSlot(118, 10).setOutputSlotBackground().addItemStack(recipe.filledContainer());
        } else {
            builder.addInputSlot(8, 10).setStandardSlotBackground().addItemStack(recipe.filledContainer());
            builder.addOutputSlot(90, 10).setOutputSlotBackground()
                    .addIngredient(ForgeTypes.FLUID_STACK, recipe.fluid()).setFluidRenderer(amount, false, 16, 16);
            builder.addOutputSlot(118, 10).setOutputSlotBackground().addItemStack(recipe.emptyContainer());
        }
        // 内部匹配仍使用流体凭据，界面只显示 JEI 的真实流体。
        RecipeIngredientRole role = recipe.filling() ? RecipeIngredientRole.INPUT : RecipeIngredientRole.OUTPUT;
        ItemStack token = recipe.filling() ? recipe.specs().get(1).ingredient().getItems()[0] : recipe.output();
        builder.addInvisibleIngredients(role).addItemStack(token.copyWithCount(1));
    }

    @Override public void draw(FluidContainerRecipe recipe, IRecipeSlotsView slots, GuiGraphics graphics,
                               double mouseX, double mouseY) {
        graphics.drawString(Minecraft.getInstance().font, "->", 65, 14, 0x404040, false);
    }
}
