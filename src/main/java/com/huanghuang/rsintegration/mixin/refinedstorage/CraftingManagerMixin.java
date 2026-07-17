package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.compat.ftbquests.RsAutocraftProgressTracker;
import com.refinedmods.refinedstorage.api.autocrafting.task.ICraftingTask;
import com.refinedmods.refinedstorage.apiimpl.autocrafting.CraftingManager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingManager.class, remap = false)
public abstract class CraftingManagerMixin {
    @Inject(method = "request(Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;I)Lcom/refinedmods/refinedstorage/api/autocrafting/task/ICraftingTask;",
            at = @At("RETURN"), remap = false)
    private void rsi$rememberItemRequester(Object requester, ItemStack requested, int quantity,
                                             CallbackInfoReturnable<ICraftingTask> cir) {
        ICraftingTask task = cir.getReturnValue();
        if (task != null) RsAutocraftProgressTracker.remember(task.getId(), requester);
    }
}
