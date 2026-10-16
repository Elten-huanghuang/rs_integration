package com.huanghuang.rsintegration.magic;

import com.mojang.blaze3d.systems.RenderSystem;
import com.wintercogs.beyonddimensions.api.storage.key.IStackKey;
import com.wintercogs.beyonddimensions.api.storage.key.IStackRender;
import com.wintercogs.beyonddimensions.util.StringFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/** BD 终端中的液态灵魂能量图标；数量仍由 BD 的 KeyAmount 渲染。 */
public final class GoetySoulStackKeyRender implements IStackRender {
    public static final GoetySoulStackKeyRender INSTANCE = new GoetySoulStackKeyRender();

    private GoetySoulStackKeyRender() {}

    @Override public void render(GuiGraphics gui, IStackKey<?> key, int x, int y) {
        LiquidSoulEnergyTexture.render(gui, x, y);
    }

    @Override public void renderAmount(GuiGraphics gui, long amount, int x, int y) {
        String text = getCountText(amount);
        if (text.isEmpty()) return;
        gui.pose().pushPose();
        gui.pose().translate(0, 0, 200);
        gui.pose().scale(0.666f, 0.666f, 0.666f);
        RenderSystem.disableBlend();
        int width = Minecraft.getInstance().font.width(text);
        gui.drawString(Minecraft.getInstance().font, text,
                (int) ((x - 1 + 16.0f + 2.0f - width * 0.666f) / 0.666f),
                (int) ((y - 1 + 16.0f - 5.0f * 0.666f) / 0.666f), 0xFFFFFF);
        gui.pose().popPose();
    }

    @Override public String getCountText(long count) { return count < 0 ? "" : StringFormat.formatCount(count); }
    @Override public Component getDisplayName(IStackKey<?> key) { return GoetySoulStackKey.INSTANCE.getRenderStack().getName(); }

    @Override public List<Component> getTooltipLines(IStackKey<?> key, long amount, @Nullable Player player, TooltipFlag tooltipFlag) {
        return List.of(getDisplayName(key), Component.translatable("istack.beyonddimensions.storage_num.long_type", amount));
    }

    @Override public Optional<TooltipComponent> getTooltipImage(IStackKey<?> key) { return Optional.empty(); }

    @Override public void renderTooltip(GuiGraphics gui, Font font, IStackKey<?> key, long amount, int mouseX, int mouseY) {
        var minecraft = Minecraft.getInstance();
        gui.renderTooltip(minecraft.font, getTooltipLines(key, amount, minecraft.player,
                        minecraft.options.advancedItemTooltips ? TooltipFlag.Default.ADVANCED : TooltipFlag.Default.NORMAL),
                getTooltipImage(key), ItemStack.EMPTY, mouseX, mouseY);
    }
}
