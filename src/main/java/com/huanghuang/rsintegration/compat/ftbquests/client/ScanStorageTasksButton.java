package com.huanghuang.rsintegration.compat.ftbquests.client;

import com.huanghuang.rsintegration.compat.ftbquests.StorageQuestScanPacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.ui.Panel;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftblibrary.util.TooltipList;
import dev.ftb.mods.ftbquests.client.gui.quests.TabButton;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** Runs one explicit storage, inventory, equipment and Curios task scan. */
public final class ScanStorageTasksButton extends TabButton {

    public ScanStorageTasksButton(Panel parent) {
        super(parent, Component.translatable("rsi.ftb_quest.storage_scan.button"), Icons.REFRESH);
    }

    @Override
    public void addMouseOverText(TooltipList tooltip) {
        tooltip.add(Component.translatable("rsi.ftb_quest.storage_scan.button"));
        tooltip.add(Component.translatable("rsi.ftb_quest.storage_scan.warning")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        tooltip.add(Component.translatable("rsi.ftb_quest.storage_scan.scope")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("rsi.ftb_quest.storage_scan.inventory_scope")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("rsi.ftb_quest.scan.safety")
                .withStyle(ChatFormatting.DARK_GREEN));
        tooltip.add(Component.translatable("rsi.ftb_quest.reopen_required")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
    }

    @Override
    public void onClicked(MouseButton button) {
        if (button.isLeft()) {
            NetworkHandler.CHANNEL.sendToServer(new StorageQuestScanPacket());
        }
    }
}
