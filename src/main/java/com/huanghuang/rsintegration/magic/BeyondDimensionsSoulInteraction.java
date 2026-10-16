package com.huanghuang.rsintegration.magic;

import com.wintercogs.beyonddimensions.api.storage.handler.impl.AbstractUnorderedStackHandler;
import com.wintercogs.beyonddimensions.common.menu.DimensionsNetMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** 仅在 Beyond Dimensions 已加载时加载的灵魂终端交互。 */
public final class BeyondDimensionsSoulInteraction {
    private BeyondDimensionsSoulInteraction() {}

    public static boolean handle(AbstractContainerMenu container, int button) {
        if (!(container instanceof DimensionsNetMenu menu)
                || !(menu.player instanceof ServerPlayer player)
                || player.containerMenu != menu
                || !menu.stillValid(player)
                || (button != 0 && button != 1)) return false;
        ItemStack cursor = menu.getCarried();
        if (!GoetySoulTotem.isTotem(cursor) || menu.storage == null) return false;

        AbstractUnorderedStackHandler storage = menu.storage;
        // 与 RS 混合终端保持一致：右键倒出到网络，左键从网络吸取。
        long moved = MagicResourceInteraction.depositsFromHeldItem(button)
                ? BeyondDimensionsSoulTransfer.deposit(
                        storage, cursor, GoetySoulTotem.souls(cursor), true)
                : BeyondDimensionsSoulTransfer.withdraw(
                        storage, cursor, GoetySoulTotem.space(cursor), true);
        if (moved > 0) {
            menu.setCarried(cursor);
            player.getInventory().setChanged();
        }
        return true;
    }
}
