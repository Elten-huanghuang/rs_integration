package com.huanghuang.rsintegration.mods.ironsspellbooks.client;

import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipe;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.forge.ForgeTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

public final class AlchemistCauldronRecipeCategory implements IRecipeCategory<IronSpellBooksRecipe> {
    public static final RecipeType<IronSpellBooksRecipe> TYPE = RecipeType.create(
            "rs_integration", "alchemist_cauldron", IronSpellBooksRecipe.class);
    private final IDrawable icon;
    public AlchemistCauldronRecipeCategory(IGuiHelper helper) {
        icon = helper.createDrawableItemStack(new ItemStack(ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("irons_spellbooks", "alchemist_cauldron"))));
    }
    @Override public RecipeType<IronSpellBooksRecipe> getRecipeType() { return TYPE; }
    @Override public Component getTitle() { return Component.translatable("gui.rs_integration.jei.irons_spellbooks_ink_bottling"); }
    @Override public int getWidth() { return 150; }
    @Override public int getHeight() { return 58; }
    @Override public IDrawable getIcon() { return icon; }
    @Override public ResourceLocation getRegistryName(IronSpellBooksRecipe recipe) { return recipe.getId(); }
    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, IronSpellBooksRecipe recipe, IFocusGroup focuses) {
        var inputs = recipe.inputs();
        for (int i = 0; i < inputs.size(); i++) {
            ItemStack input = inputs.get(i);
            var slot = builder.addInputSlot(8 + i * 28, 8).setStandardSlotBackground();
            if (InkFluidSupport.isToken(input)) {
                slot.addIngredient(ForgeTypes.FLUID_STACK, InkFluidSupport.fluid(input)).setFluidRenderer(InkFluidSupport.BOTTLE_AMOUNT, false, 16, 16);
                builder.addInvisibleIngredients(RecipeIngredientRole.INPUT).addItemStack(input.copyWithCount(1));
            } else slot.addIngredients(recipe.inputIngredients().get(i));
        }
        ItemStack output = recipe.getResultItem(RegistryAccess.EMPTY);
        var slot = builder.addOutputSlot(118, 8).setOutputSlotBackground();
        slot.addItemStack(output);
    }
    @Override
    public void draw(IronSpellBooksRecipe recipe, IRecipeSlotsView slots, GuiGraphics graphics, double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        graphics.drawString(font, "→", 83, 12, 0x404040, false);
        graphics.drawString(font, Component.translatable("rsi.alchemist.bottle_hint"), 4, 37, 0x606060, false);
    }
}
