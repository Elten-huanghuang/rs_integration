package com.huanghuang.rsintegration.compat.ftbquests.client;

import com.huanghuang.rsintegration.compat.ftbquests.QuestItemRequirement;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionSnapshot;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class FtbQuestSubmissionCategory implements IRecipeCategory<QuestSubmissionSnapshot> {

    private static final int WIDTH = 150;
    private static final int HEIGHT = 72;
    private final IDrawable icon;

    public FtbQuestSubmissionCategory(IGuiHelper guiHelper) {
        this.icon = guiHelper.createDrawableItemLike(
                BuiltInRegistries.ITEM.get(new ResourceLocation("ftbquests", "book")));
    }

    @Override
    public RecipeType<QuestSubmissionSnapshot> getRecipeType() {
        return FtbQuestSubmissionRecipe.TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("rsi.jei.ftb_quest_submission");
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, QuestSubmissionSnapshot snapshot,
                          IFocusGroup focuses) {
        int count = Math.min(snapshot.requirements().size(), 6);
        int startX = Math.max(2, (WIDTH - count * 22) / 2);
        for (int i = 0; i < count; i++) {
            QuestItemRequirement requirement = snapshot.requirements().get(i);
            List<ItemStack> stacks = requirement.validDisplayItems().isEmpty()
                    ? List.of(requirement.displayStack().copyWithCount(safeDisplayCount(requirement.remaining())))
                    : requirement.validDisplayItems().stream()
                    .map(stack -> stack.copyWithCount(safeDisplayCount(requirement.remaining())))
                    .toList();
            builder.addInputSlot(startX + i * 22, 23)
                    .setStandardSlotBackground()
                    .addItemStacks(stacks);
        }
        java.util.List<ItemStack> focusStacks = new java.util.ArrayList<>();
        for (QuestItemRequirement requirement : snapshot.requirements()) {
            if (requirement.validDisplayItems().isEmpty()) {
                focusStacks.add(requirement.displayStack().copyWithCount(1));
            } else {
                requirement.validDisplayItems().stream()
                        .map(stack -> stack.copyWithCount(1))
                        .forEach(focusStacks::add);
            }
        }
        // FTB Quests opens JEI with showRecipes(item), i.e. an OUTPUT focus.
        // The visible slots remain inputs because the items are consumed by the
        // quest, while this invisible output link makes the virtual submission
        // category discoverable directly from the quest task's item button.
        builder.addInvisibleIngredients(mezz.jei.api.recipe.RecipeIngredientRole.OUTPUT)
                .addItemStacks(focusStacks);

        int rewardSlots = Math.min(snapshot.itemRewards().size(), 4);
        int rewardStartX = Math.max(2, (WIDTH - rewardSlots * 22) / 2);
        for (int i = 0; i < rewardSlots; i++) {
            builder.addOutputSlot(rewardStartX + i * 22, 51)
                    .setOutputSlotBackground()
                    .addItemStack(snapshot.itemRewards().get(i).stack());
        }
    }

    @Override
    public void draw(QuestSubmissionSnapshot snapshot, IRecipeSlotsView slots,
                     GuiGraphics graphics, double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        String title = font.plainSubstrByWidth(snapshot.title(), WIDTH - 4);
        graphics.drawString(font, title, 2, 2, 0x404040, false);
        String mode = snapshot.repeatable()
                ? Component.translatable("rsi.jei.ftb_quest_repeatable").getString()
                : Component.translatable("rsi.jei.ftb_quest_once").getString();
        graphics.drawString(font, mode, 2, 12, 0x707070, false);
    }

    @Override
    public boolean handleInput(QuestSubmissionSnapshot snapshot, double mouseX, double mouseY,
                               com.mojang.blaze3d.platform.InputConstants.Key input) {
        if (mouseY < 0 || mouseY > HEIGHT || input.getValue() != 0) return false;
        var runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) return false;
        NetworkHandler.CHANNEL.sendToServer(
                new com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionRequestPacket(
                        snapshot.questId()));
        return true;
    }

    @Override
    public List<Component> getTooltipStrings(QuestSubmissionSnapshot snapshot,
                                              IRecipeSlotsView slots,
                                              double mouseX, double mouseY) {
        if (mouseY < 20) {
            return List.of(Component.translatable("rsi.jei.ftb_quest_tooltip",
                    snapshot.requirements().size(), snapshot.rewardCount()));
        }
        return List.of();
    }

    private static int safeDisplayCount(long count) {
        return (int) Math.max(1L, Math.min(64L, count));
    }
}
