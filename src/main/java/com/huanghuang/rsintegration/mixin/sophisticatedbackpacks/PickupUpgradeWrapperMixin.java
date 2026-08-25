package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import com.huanghuang.rsintegration.mods.sophisticatedbackpacks.StorageBackpackUtils;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeWrapperBase;
import net.p3pp3rf1y.sophisticatedcore.upgrades.pickup.PickupUpgradeItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.pickup.PickupUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidUpgradeWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Mixin(value = PickupUpgradeWrapper.class)
public abstract class PickupUpgradeWrapperMixin
        extends UpgradeWrapperBase<PickupUpgradeWrapper, PickupUpgradeItem> {

    @Unique
    private StorageReference rsi$storageReference;
    @Unique
    private boolean rsi$voidUpgrade = false;

    protected PickupUpgradeWrapperMixin(IStorageWrapper storageWrapper, ItemStack upgrade,
                                         Consumer<ItemStack> upgradeSaveHandler) {
        super(storageWrapper, upgrade, upgradeSaveHandler);
    }

    @Shadow(remap = false)
    public abstract ContentsFilterLogic getFilterLogic();

    @Inject(method = "<init>", at = @At(value = "RETURN"), remap = false)
    private void onInit(IStorageWrapper storageWrapper, ItemStack upgrade,
                        Consumer<ItemStack> upgradeSaveHandler, CallbackInfo ci) {
        CompoundTag tag = upgrade.getTag();
        this.rsi$storageReference = StorageBackpackUtils.readReference(tag);
        if (this.rsi$storageReference != null) {
            if (!tag.contains("disabled")) {
                this.rsi$voidUpgrade = true;
            }
        }
    }

    @Inject(method = "pickup", at = @At(value = "HEAD"), remap = false, cancellable = true)
    private void pickupHook(Level world, ItemStack stack, boolean simulate,
                            CallbackInfoReturnable<ItemStack> cir) {
        if (this.rsi$storageReference == null) return;
        cir.setReturnValue(StorageBackpackUtils.pickupItem(this.getFilterLogic(), world, stack, simulate,
                this.rsi$storageReference,
                rsi$getUpgradesOfType(VoidUpgradeWrapper.class), this.rsi$voidUpgrade));
        cir.cancel();
    }

    @Unique
    protected <U extends IUpgradeWrapper> List<U> rsi$getUpgradesOfType(Class<U> upgradeClass) {
        return this.storageWrapper.getUpgradeHandler().getWrappersThatImplement(upgradeClass)
                .stream().filter(IUpgradeWrapper::isEnabled).collect(Collectors.toList());
    }
}
