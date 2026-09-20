package com.huanghuang.rsintegration.compat.ftbquests.client;

import com.huanghuang.rsintegration.compat.ftbquests.InventoryQuestScanPacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.ui.Panel;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftblibrary.util.TooltipList;
import dev.ftb.mods.ftbquests.client.gui.quests.TabButton;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** Runs one explicit inventory/Curios task scan from FTB Quests' upper sidebar. */
public final class ScanInventoryTasksButton extends TabButton {

    public ScanInventoryTasksButton(Panel parent) {
        super(parent, Component.translatable("rsi.ftb_quest.inventory_scan.button"), Icons.INV_IO);
    }

    @Override
    public void addMouseOverText(TooltipList tooltip) {
        tooltip.add(Component.translatable("rsi.ftb_quest.inventory_scan.button"));
        tooltip.add(Component.translatable("rsi.ftb_quest.inventory_scan.scope")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("rsi.ftb_quest.reopen_required")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
    }

    @Override
    public void onClicked(MouseButton button) {
        if (button.isLeft()) {
            NetworkHandler.CHANNEL.sendToServer(new InventoryQuestScanPacket());
        }
    }
}
