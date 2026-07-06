package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeCountLimitConfig;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.pickup.PickupUpgradeItem;

import java.util.List;
import java.util.function.IntSupplier;

public class RSPickupUpgradeItem extends PickupUpgradeItem {

    public RSPickupUpgradeItem(IntSupplier filterSlotCount,
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
        return RSMagnetUpgradeItem.isBoundToRS(stack);
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
