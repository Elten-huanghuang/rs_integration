package com.huanghuang.rsintegration.feature.experience;

import com.huanghuang.rsintegration.feature.resonance.api.ResonanceStorageMenu;
import com.huanghuang.rsintegration.feature.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.feature.resonance.bridge.ClientDiskData;
import com.huanghuang.rsintegration.feature.resonance.bridge.ResonanceInventoryBridge;
import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 统一随身与共振盘内的经验杖来源；只有服务端能实际运行或修改磁盘物品。 */
public final class ExperienceWandAccess {
    private ExperienceWandAccess() {}

    public static boolean isWand(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ResonantExperienceWandItem;
    }

    /** 免税只要求有杖，不要求开启等级控制或修补。 */
    public static boolean hasWand(@Nullable Player player) {
        if (player == null) return false;
        if (!carried(player, ExperienceWandAccess::isWand).isEmpty()) return true;
        return player instanceof ServerPlayer serverPlayer
                ? ResonanceInventoryBridge.hasItem(serverPlayer, ExperienceWandAccess::isWand)
                : ClientDiskData.hasExperienceWand();
    }

    @Nullable
    static Selection selected(ServerPlayer player) {
        ItemStack carried = carried(player, ExperienceWandData::active);
        if (!carried.isEmpty()) return new Selection(carried, null, -1);
        // 共振背包菜单持有槽位快照，打开期间暂停盘内杖的修改，避免覆盖旧 NBT。
        if (player.containerMenu instanceof ResonanceStorageMenu) return null;
        for (ResonanceStorageView disk : ResonanceInventoryBridge.getViews(player)) {
            for (ResonanceStorageView.StoredStack stored : disk.storedStacks()) {
                if (ExperienceWandData.active(stored.stack())) {
                    return new Selection(stored.stack(), disk, stored.slot());
                }
            }
        }
        return null;
    }

    private static ItemStack carried(Player player, Predicate<ItemStack> predicate) {
        ItemStack mainHand = player.getMainHandItem();
        if (predicate.test(mainHand)) return mainHand;
        ItemStack offHand = player.getOffhandItem();
        if (predicate.test(offHand)) return offHand;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (predicate.test(stack)) return stack;
        }
        return ItemStack.EMPTY;
    }

    record Selection(ItemStack stack, @Nullable ResonanceStorageView disk, int slot) {
        void use(ServerPlayer player, Consumer<ItemStack> action) {
            if (disk == null) {
                action.accept(stack);
                return;
            }
            // 先取得真实物品再结算经验；不能修改快照后再尝试覆盖，失败会重复使用余液。
            ItemStack claimed = disk.extractExactView(slot, stack, 1, false);
            if (claimed.isEmpty()) return;
            try {
                if (claimed.getCount() == 1 && ItemStack.isSameItemSameTags(stack, claimed)) {
                    action.accept(claimed);
                }
            } finally {
                ItemStack remainder = disk.insertView(slot, claimed, claimed.getCount(), false);
                disk.markDirty(player);
                if (!remainder.isEmpty()) {
                    PlayerUtils.safeGiveToStorage(player, remainder, null);
                }
            }
        }
    }
}
