package com.huanghuang.rsintegration.mods.pmmo.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.drawable.IDrawableStatic;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Map;

public final class PmmoSalvageRecipeCategory implements IRecipeCategory<PmmoSalvageRecipe> {
    public static final RecipeType<PmmoSalvageRecipe> TYPE = RecipeType.create(
            RSIntegrationMod.MOD_ID, "pmmo_salvage", PmmoSalvageRecipe.class);
    private static final int WIDTH = 154;
    private static final int HEIGHT = 76;

    private final IDrawable icon;
    private final IDrawableStatic arrow;

    public PmmoSalvageRecipeCategory(IGuiHelper guiHelper, ItemStack salvageBlock) {
        ItemStack iconStack = salvageBlock.isEmpty()
                ? new ItemStack(Items.SMITHING_TABLE)
                : salvageBlock;
        this.icon = guiHelper.createDrawableItemStack(iconStack);
        this.arrow = guiHelper.getRecipeArrow();
    }

    @Override
    public RecipeType<PmmoSalvageRecipe> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("rsi.jei.pmmo_salvage");
    }

    @Override public int getWidth() { return WIDTH; }
    @Override public int getHeight() { return HEIGHT; }
    @Override public IDrawable getIcon() { return icon; }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, PmmoSalvageRecipe recipe,
                          IFocusGroup focuses) {
        builder.addInputSlot(14, 18)
                .setStandardSlotBackground()
                .addItemStack(recipe.input());
        builder.addOutputSlot(122, 18)
                .setOutputSlotBackground()
                .addItemStack(recipe.output())
                .addRichTooltipCallback((slot, tooltip) -> addDetails(recipe, tooltip));
    }

    @Override
    public void draw(PmmoSalvageRecipe recipe, IRecipeSlotsView slots,
                     GuiGraphics graphics, double mouseX, double mouseY) {
        arrow.draw(graphics, 64, 19);
        var font = Minecraft.getInstance().font;
        graphics.drawString(font,
                Component.translatable("rsi.jei.pmmo_chance",
                        percent(recipe.baseChance()), percent(recipe.maxChance())),
                5, 49, 0x505050, false);
        graphics.drawString(font,
                Component.translatable("rsi.jei.pmmo_max_output", recipe.salvageMax()),
                5, 62, 0x505050, false);
    }

    private static void addDetails(PmmoSalvageRecipe recipe, ITooltipBuilder tooltip) {
        for (Map.Entry<String, Integer> entry : recipe.levelRequirements().entrySet()) {
            tooltip.add(Component.translatable("rsi.jei.pmmo_level_requirement",
                    skillName(entry.getKey()), entry.getValue()).withStyle(ChatFormatting.GRAY));
        }
        for (Map.Entry<String, Double> entry : recipe.chancePerLevel().entrySet()) {
            tooltip.add(Component.translatable("rsi.jei.pmmo_chance_per_level",
                    skillName(entry.getKey()), percent(entry.getValue()))
                    .withStyle(ChatFormatting.GRAY));
        }
        for (Map.Entry<String, Long> entry : recipe.xpAwards().entrySet()) {
            tooltip.add(Component.translatable("rsi.jei.pmmo_xp_award",
                    skillName(entry.getKey()), entry.getValue()).withStyle(ChatFormatting.GRAY));
        }
    }

    private static Component skillName(String skill) {
        String key = "pmmo." + skill;
        return I18n.exists(key) ? Component.translatable(key) : Component.literal(skill);
    }

    private static String percent(double chance) {
        return String.format(Locale.ROOT, "%.1f%%", chance * 100.0D);
    }
}
