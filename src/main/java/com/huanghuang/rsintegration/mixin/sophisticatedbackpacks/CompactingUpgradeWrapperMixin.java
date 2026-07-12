package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.majruszsaccessories.MajAccessoryCompressor;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
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

    // Resolved once — MAJ compression references majruszsaccessories classes
    // (AccessoryItem/AccessoryHolder). Touching MajAccessoryCompressor when the
    // mod is absent throws NoClassDefFoundError and crashes the backpack tick,
    // so this guard MUST short-circuit before that class is ever referenced.
    private static Boolean rsi_majLoaded;

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void onTick(Entity entity, Level level, BlockPos pos, CallbackInfo ci) {
        if (!RSIntegrationConfig.ENABLE_MAJ_ACCESSORY_COMPRESSION.get()) return;
        if (rsi_majLoaded == null) {
            rsi_majLoaded = ModList.get().isLoaded(ModIds.MAJRUSZS_ACCESSORIES);
        }
        if (!rsi_majLoaded) return;
        if (!isEnabled()) return;
        if (++rsi_compressCooldown < 10) return;
        rsi_compressCooldown = 0;

        var inventory = this.storageWrapper.getInventoryForUpgradeProcessing();
        MajAccessoryCompressor.compress(inventory, getFilterLogic());
    }
}
