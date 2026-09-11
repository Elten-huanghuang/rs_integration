package com.huanghuang.rsintegration.voidupgrade.client;

import com.huanghuang.rsintegration.voidupgrade.VoidUpgradeConfig;
import com.huanghuang.rsintegration.voidupgrade.network.SaveVoidUpgradeConfigPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

public final class VoidUpgradeClient {
    private VoidUpgradeClient() {}

    public static void open(InteractionHand hand, ItemStack stack) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new VoidUpgradeScreen(
                hand == InteractionHand.MAIN_HAND
                        ? SaveVoidUpgradeConfigPacket.Target.MAIN_HAND
                        : SaveVoidUpgradeConfigPacket.Target.OFF_HAND,
                -1, VoidUpgradeConfig.fromStack(stack), minecraft.screen));
    }

    public static void openMenuSlot(int slot, ItemStack stack, Screen parent) {
        Minecraft.getInstance().setScreen(new VoidUpgradeScreen(
                SaveVoidUpgradeConfigPacket.Target.MENU_SLOT, slot,
                VoidUpgradeConfig.fromStack(stack), parent));
    }
}
