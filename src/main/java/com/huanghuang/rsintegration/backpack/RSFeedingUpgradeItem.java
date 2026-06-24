package com.huanghuang.rsintegration.backpack;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeCountLimitConfig;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.feeding.FeedingUpgradeItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.feeding.FeedingUpgradeWrapper;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

public class RSFeedingUpgradeItem extends FeedingUpgradeItem {

    public RSFeedingUpgradeItem(IntSupplier filterSlotCount,
                                IUpgradeCountLimitConfig countLimitConfig) {
        super(filterSlotCount, countLimitConfig);
    }

    @Override
    public int getFilterSlotCount() {
        return 16;
    }

    @Override
    public List<IUpgradeItem.UpgradeConflictDefinition> getUpgradeConflicts() {
        return List.of();
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains("RSBlockPos") && tag.contains("RSBlockDimension");
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        RSMagnetUpgradeItem.appendRSInfo(stack, tooltip);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return RSMagnetUpgradeItem.bindToRS(context);
    }
}
