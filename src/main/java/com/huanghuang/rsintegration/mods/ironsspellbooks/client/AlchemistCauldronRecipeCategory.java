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
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.ArrayList;

public final class AlchemistCauldronRecipeCategory implements IRecipeCategory<IronSpellBooksRecipe> {
    public static final RecipeType<IronSpellBooksRecipe> TYPE = RecipeType.create(
            "rs_integration", "alchemist_cauldron", IronSpellBooksRecipe.class);
    private final IDrawable icon;
    public AlchemistCauldronRecipeCategory(IGuiHelper helper) {
        icon = helper.createDrawableItemStack(new ItemStack(ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("irons_spellbooks", "alchemist_cauldron"))));
    }
    @Override public RecipeType<IronSpellBooksRecipe> getRecipeType() { return TYPE; }
    @Override public Component getTitle() { return Component.translatable("gui.rs_integration.jei.irons_spellbooks_alchemist_cauldron"); }
    @Override public int getWidth() { return 150; }
    @Override public int getHeight() { return 78; }
    @Override public IDrawable getIcon() { return icon; }
    @Override public ResourceLocation getRegistryName(IronSpellBooksRecipe recipe) { return recipe.getId(); }
    @Override public boolean isHandled(IronSpellBooksRecipe recipe) {
        return recipe.machine() == IronSpellBooksRecipe.Machine.ALCHEMIST_CAULDRON;
    }
    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, IronSpellBooksRecipe recipe, IFocusGroup focuses) {
        var inputs = recipe.inputs();
        for (int i = 0; i < inputs.size(); i++) {
            ItemStack input = inputs.get(i);
            var slot = builder.addInputSlot(8 + i * 28, 8).setStandardSlotBackground();
            if (InkFluidSupport.isToken(input)) {
                slot.addIngredient(ForgeTypes.FLUID_STACK, InkFluidSupport.fluid(input)).setFluidRenderer(input.getCount(), false, 16, 16);
                builder.addInvisibleIngredients(RecipeIngredientRole.INPUT).addItemStack(input.copyWithCount(1));
            } else slot.addIngredients(recipe.inputIngredients().get(i));
        }
        if (recipe.isScrollRecycling()) {
            builder.addInputSlot(36, 8).setStandardSlotBackground()
                    .addIngredient(ForgeTypes.FLUID_STACK, new FluidStack(Fluids.WATER, InkFluidSupport.BOTTLE_AMOUNT))
                    .setFluidRenderer(InkFluidSupport.BOTTLE_AMOUNT, false, 16, 16);
        }
        var outputs = new ArrayList<ItemStack>();
        outputs.add(recipe.getResultItem(RegistryAccess.EMPTY));
        outputs.addAll(recipe.secondaryOutputs());
        for (int i = 0; i < outputs.size(); i++) {
            ItemStack output = outputs.get(i);
            var slot = builder.addOutputSlot(i == 0 ? 118 : 90 + ((i - 1) % 2) * 28,
                    i == 0 ? 8 : 30 + ((i - 1) / 2) * 22).setOutputSlotBackground();
            if (InkFluidSupport.isToken(output)) {
                slot.addIngredient(ForgeTypes.FLUID_STACK, InkFluidSupport.fluid(output))
                        .setFluidRenderer(output.getCount(), false, 16, 16);
            } else slot.addItemStack(output);
        }
    }
    @Override
    public void draw(IronSpellBooksRecipe recipe, IRecipeSlotsView slots, GuiGraphics graphics, double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        graphics.drawString(font, "→", 83, 12, 0x404040, false);
        graphics.drawString(font, Component.translatable(recipe.isScrollRecycling()
                ? "rsi.alchemist.recycle_hint" : recipe.isBrewing()
                ? "rsi.alchemist.brew_hint" : "rsi.alchemist.bottle_hint"), 4, 63, 0x606060, false);
    }
}
