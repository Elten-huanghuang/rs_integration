package com.huanghuang.rsintegration.compat.ftbquests.client;

import com.huanghuang.rsintegration.compat.ftbquests.CheckmarkConfirmPacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.ui.Panel;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftblibrary.util.TooltipList;
import dev.ftb.mods.ftbquests.client.gui.quests.TabButton;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** Native FTB Library sidebar button for bulk checkmark confirmation. */
public final class ConfirmCheckmarksButton extends TabButton {

    public ConfirmCheckmarksButton(Panel parent) {
        super(parent, Component.translatable("rsi.ftb_quest.checkmarks.button"), Icons.CHECK);
    }

    @Override
    public void addMouseOverText(TooltipList tooltip) {
        tooltip.add(Component.translatable("rsi.ftb_quest.checkmarks.button"));
        tooltip.add(Component.translatable("rsi.ftb_quest.reopen_required")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
    }

    @Override
    public void onClicked(MouseButton button) {
        if (button.isLeft()) {
            NetworkHandler.CHANNEL.sendToServer(new CheckmarkConfirmPacket());
        }
    }
}
