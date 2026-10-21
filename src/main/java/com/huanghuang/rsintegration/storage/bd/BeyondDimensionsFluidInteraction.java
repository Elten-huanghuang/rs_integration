package com.huanghuang.rsintegration.storage.bd;

import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerBucketSupport;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerTransfer;
import com.huanghuang.rsintegration.mods.ironsspellbooks.AlchemistBottleSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.FluidRefundItem;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkBottleFluidHandler;
import com.huanghuang.rsintegration.feature.unifiedgrid.UnifiedGridFluidRecovery;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.FluidStackKey;
import com.wintercogs.beyonddimensions.common.menu.DimensionsNetMenu;
import com.wintercogs.beyonddimensions.common.menu.widget.slot.DisorderedStackTypedSlot;
import com.wintercogs.beyonddimensions.common.menu.widget.slot.ItemCapInteractionBlackList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fluids.FluidStack;

/** 只补充 BD 终端缺少的容器路径，其他资源和原生能力交互继续由 BD 处理。 */
public final class BeyondDimensionsFluidInteraction {
    private BeyondDimensionsFluidInteraction() { }

    public static boolean handle(AbstractContainerMenu container, int slotIndex, Object clicked,
                                 int button, boolean shift) {
        if (!(container instanceof DimensionsNetMenu menu) || !(menu.player instanceof ServerPlayer player)
                || player.containerMenu != menu || !menu.stillValid(player)
                || slotIndex < 0 || slotIndex >= menu.slots.size()
                || !(menu.slots.get(slotIndex) instanceof DisorderedStackTypedSlot slot)
                || !slot.isActive() || slot.getStorage() != menu.storage
                || !(clicked instanceof KeyAmount selected) || (button != 0 && button != 1)) return false;
        ItemStack cursor = menu.getCarried();
        if (!cursor.isEmpty() && ItemCapInteractionBlackList.isInBlackList(cursor.getItem())) return false;
        if (!cursor.isEmpty() && FluidContainerTransfer.handler(cursor.copyWithCount(1)) == null) return false;

        // Shift 装普通桶已有原生实现，装瓶借用网络空瓶并送入玩家背包。
        FluidStack fluid = selected.key() instanceof FluidStackKey key ? key.copyStack() : FluidStack.EMPTY;
        boolean bottle = !cursor.isEmpty() && InkBottleFluidHandler.create(cursor.copyWithCount(1)) != null;
        boolean specialBucket = !cursor.isEmpty() && FluidContainerBucketSupport.needsFallback(cursor);
        boolean customBucket = !cursor.isEmpty() && cursor.hasCraftingRemainingItem()
                && !cursor.getCraftingRemainingItem().isEmpty() && !cursor.getCraftingRemainingItem().is(Items.BUCKET);
        boolean fill = !fluid.isEmpty() && (cursor.isEmpty() || button == 1);
        boolean taggedFluid = !fluid.isEmpty() && fluid.hasTag() && !fluid.getTag().isEmpty();
        if (shift && (!cursor.isEmpty() || !AlchemistBottleSupport.canBottle(fluid) && !taggedFluid)) return false;
        if (!cursor.isEmpty() && !bottle && !specialBucket && !customBucket && !taggedFluid
                && cursor.getCount() == 1) return false;
        if (!fill && (button != 1 || cursor.isEmpty())) return false;
        if (!slot.mayPickup(player) || !cursor.isEmpty() && !slot.mayPlace(cursor)) return true;

        BeyondDimensionsFluidStorage storage = new BeyondDimensionsFluidStorage(menu.storage);
        FluidContainerTransfer.Result result = fill
                ? FluidContainerTransfer.fill(storage, cursor, fluid)
                : FluidContainerTransfer.empty(storage, cursor);
        if (fill && result.transferred() == 0 && !cursor.isEmpty()
                && result.recovery().isEmpty()) result = FluidContainerTransfer.empty(storage, cursor);
        if (!fill && result.transferred() == 0 && result.recovery().isEmpty()) return bottle || specialBucket || customBucket;
        apply(player, result, shift);
        return true;
    }

    static void apply(ServerPlayer player, FluidContainerTransfer.Result result, boolean shift) {
        if (!result.recovery().isEmpty()) {
            // 异常余量以单个退款物品保存，背包满时复用世界存档中的暂存队列。
            CompoundTag raw = result.recovery().writeToNBT(new CompoundTag());
            ItemStack voucher = FluidRefundItem.create(raw, null);
            if (!player.getInventory().add(voucher)) {
                UnifiedGridFluidRecovery.get(player.server).retainRaw(player.getUUID(), raw, null);
            }
        }
        player.containerMenu.setCarried(result.cursor());
        if (!result.overflow().isEmpty()) player.getInventory().placeItemBackInInventory(result.overflow());
        else if (shift && result.transferred() > 0) {
            ItemStack remaining = result.cursor().copy();
            player.getInventory().add(remaining);
            player.containerMenu.setCarried(remaining);
        }
        if (result.transferred() > 0) {
            player.getInventory().setChanged();
            MaterialSources.invalidateFor(player);
        }
    }
}
