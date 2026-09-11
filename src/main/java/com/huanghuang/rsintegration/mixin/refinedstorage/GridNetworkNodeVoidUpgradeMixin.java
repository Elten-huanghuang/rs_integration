package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.voidupgrade.VoidUpgradeManager;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.apiimpl.network.node.GridNetworkNode;
import com.refinedmods.refinedstorage.inventory.item.FilterItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import com.refinedmods.refinedstorage.inventory.item.BaseItemHandler;
import com.refinedmods.refinedstorage.inventory.listener.InventoryListener;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

@Mixin(value = GridNetworkNode.class, remap = false)
public abstract class GridNetworkNodeVoidUpgradeMixin {
    @Shadow @Final private FilterItemHandler filter;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void rsi$acceptVoidUpgrade(Level level, BlockPos pos, GridType type,
                                       CallbackInfo ci) {
        GridNetworkNode self = (GridNetworkNode) (Object) this;
        filter.addValidator(new Predicate<>() {
            @Override
            public boolean test(ItemStack stack) {
                return ModItems.RS_VOID_UPGRADE != null
                        && stack.is(ModItems.RS_VOID_UPGRADE.get());
            }
        });
        filter.addListener(new InventoryListener<>() {
            @Override
            public void onChanged(BaseItemHandler handler, int slot, boolean reading) {
                if (!reading && !level.isClientSide) VoidUpgradeManager.onGridFilterChanged(self);
            }
        });
    }
}
