package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import com.huanghuang.rsintegration.util.RSUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeWrapperBase;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidUpgradeWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Mixin(value = MagnetUpgradeWrapper.class)
public abstract class MagnetUpgradeWrapperMixin
        extends UpgradeWrapperBase<MagnetUpgradeWrapper, MagnetUpgradeItem> {

    @Unique
    private static final String RS_BLOCK_POS_TAG = "RSBlockPos";
    @Unique
    private static final String RS_BLOCK_DIMENSION_TAG = "RSBlockDimension";

    @Unique
    private boolean rsi$isRs;
    @Unique
    private BlockPos rsi$rsBlockPos;
    @Unique
    private ResourceKey<Level> rsi$rsDimensionKey;
    @Unique
    private boolean rsi$voidUpgrade = false;

    protected MagnetUpgradeWrapperMixin(IStorageWrapper storageWrapper, ItemStack upgrade,
                                         Consumer<ItemStack> upgradeSaveHandler) {
        super(storageWrapper, upgrade, upgradeSaveHandler);
    }

    @Shadow(remap = false)
    public abstract ContentsFilterLogic getFilterLogic();

    @Inject(method = "<init>", at = @At(value = "RETURN"), remap = false)
    private void onInit(IStorageWrapper storageWrapper, ItemStack upgrade,
                        Consumer<ItemStack> upgradeSaveHandler, CallbackInfo ci) {
        CompoundTag tag = upgrade.getTag();
        if (tag != null && tag.contains(RS_BLOCK_POS_TAG) && tag.contains(RS_BLOCK_DIMENSION_TAG)) {
            this.rsi$isRs = true;
            this.rsi$rsBlockPos = BlockPos.of(tag.getLong(RS_BLOCK_POS_TAG));
            this.rsi$rsDimensionKey = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(tag.getString(RS_BLOCK_DIMENSION_TAG)));
            if (!tag.contains("disabled")) {
                this.rsi$voidUpgrade = true;
            }
        }
    }

    @Inject(method = "tryToInsertItem", at = @At(value = "HEAD"), remap = false, cancellable = true)
    private void tryToInsertItem(ItemEntity itemEntity, CallbackInfoReturnable<Boolean> cir) {
        if (!this.rsi$isRs) return;
        UUID backpackUuid = this.storageWrapper.getContentsUuid().orElse(null);
        cir.setReturnValue(RSUtils.handleRsInsertion(this.getFilterLogic(), itemEntity,
                backpackUuid,
                this.rsi$rsBlockPos, this.rsi$rsDimensionKey,
                rsi$getUpgradesOfType(VoidUpgradeWrapper.class), this.rsi$voidUpgrade));
        cir.cancel();
    }

    @Inject(method = "pickup", at = @At(value = "HEAD"), remap = false, cancellable = true)
    private void pickup(Level world, ItemStack stack, boolean simulate,
                        CallbackInfoReturnable<ItemStack> cir) {
        if (!this.rsi$isRs) return;
        UUID backpackUuid = this.storageWrapper.getContentsUuid().orElse(null);
        cir.setReturnValue(RSUtils.handleRSPickup(this.getFilterLogic(), world, stack, simulate,
                backpackUuid,
                this.rsi$rsBlockPos, this.rsi$rsDimensionKey,
                rsi$getUpgradesOfType(VoidUpgradeWrapper.class), this.rsi$voidUpgrade));
        cir.cancel();
    }

    @Unique
    protected <U extends IUpgradeWrapper> List<U> rsi$getUpgradesOfType(Class<U> upgradeClass) {
        return this.storageWrapper.getUpgradeHandler().getWrappersThatImplement(upgradeClass)
                .stream().filter(IUpgradeWrapper::isEnabled).collect(Collectors.toList());
    }
}
