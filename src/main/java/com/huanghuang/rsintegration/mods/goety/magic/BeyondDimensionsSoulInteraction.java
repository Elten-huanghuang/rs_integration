package com.huanghuang.rsintegration.mods.goety.magic;

import com.huanghuang.rsintegration.magic.MagicGridItemTransfer;
import com.huanghuang.rsintegration.magic.MagicResourceInteraction;
import com.huanghuang.rsintegration.magic.MagicResourceKind;

import com.huanghuang.rsintegration.api.magic.BdMagicApi;
import com.wintercogs.beyonddimensions.api.storage.handler.impl.AbstractUnorderedStackHandler;
import com.wintercogs.beyonddimensions.api.storage.key.IStackKey;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
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
        MagicResourceKind creative = MagicGridItemTransfer.creativeKind(cursor);
        if (creative != null) {
            if (creative.available() && button == 1 && menu.storage != null) {
                long moved = depositCreative(menu.storage, creative);
                if (moved > 0) {
                    player.getInventory().setChanged();
                    menu.broadcastChanges();
                }
            }
            return true;
        }
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

    private static long depositCreative(AbstractUnorderedStackHandler storage,
                                        MagicResourceKind kind) {
        IStackKey<?> key = BdMagicApi.canonicalKey(BdMagicApi.resolveKey(
                kind == MagicResourceKind.MANA ? BdMagicApi.MANA_KEY : BdMagicApi.SOURCE_KEY));
        if (key == null) return 0;
        long requested = Math.min(MagicGridItemTransfer.creativeDepositAmount(), kind.capacity());
        KeyAmount remainder = storage.insert(key, requested, true);
        long accepted = requested - (remainder == null ? 0 : Math.max(0, remainder.amount()));
        if (accepted <= 0) return 0;
        KeyAmount actualRemainder = storage.insert(key, accepted, false);
        return Math.max(0, accepted - (actualRemainder == null
                ? 0 : Math.max(0, actualRemainder.amount())));
    }
}
