package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.common.gui.IFilterSlot;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeContainerBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(value = StorageContainerMenuBase.class, remap = false)
public abstract class StorageContainerMenuBaseMixin {

    @Shadow
    public abstract Optional<UpgradeContainerBase<?, ?>> getOpenContainer();

    // ── Step 1: force tab-first priority ──────────────────────────

    @Inject(method = "shouldShiftClickIntoOpenTabFirst", at = @At("RETURN"),
            cancellable = true, remap = false)
    private void onShouldShiftClickIntoOpenTabFirst(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) {
            cir.setReturnValue(true);
        }
    }

    // ── Step 2: reject filter-type tabs ───────────────────────────

    @Inject(method = "mergeStackToOpenUpgradeTab", at = @At("HEAD"),
            cancellable = true, remap = false)
    private void onMergeToOpenTab(Slot src, ItemStack stack,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (isOpenTabFilterOnly()) {
            cir.setReturnValue(false);
        }
    }

    private boolean isOpenTabFilterOnly() {
        return getOpenContainer()
                .map(c -> c.getSlots().stream()
                        .anyMatch(s -> s instanceof IFilterSlot))
                .orElse(false);
    }
}
