package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.majruszsaccessories.MajAccessoryCompressor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.FilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeWrapperBase;
import net.p3pp3rf1y.sophisticatedcore.upgrades.compacting.CompactingUpgradeItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.compacting.CompactingUpgradeWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(value = CompactingUpgradeWrapper.class, remap = false)
public abstract class CompactingUpgradeWrapperMixin
        extends UpgradeWrapperBase<CompactingUpgradeWrapper, CompactingUpgradeItem> {

    protected CompactingUpgradeWrapperMixin(IStorageWrapper storageWrapper, ItemStack upgrade,
                                            Consumer<ItemStack> upgradeSaveHandler) {
        super(storageWrapper, upgrade, upgradeSaveHandler);
    }

    @Shadow(remap = false)
    public abstract FilterLogic getFilterLogic();

    private int rsi_compressCooldown;

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void onTick(Entity entity, Level level, BlockPos pos, CallbackInfo ci) {
        if (!RSIntegrationConfig.ENABLE_MAJ_ACCESSORY_COMPRESSION.get()) return;
        if (!isEnabled()) return;
        if (++rsi_compressCooldown < 10) return;
        rsi_compressCooldown = 0;

        var inventory = this.storageWrapper.getInventoryForUpgradeProcessing();
        MajAccessoryCompressor.compress(inventory, getFilterLogic());
    }
}
