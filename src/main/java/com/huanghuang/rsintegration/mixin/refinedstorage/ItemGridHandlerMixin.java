package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.compat.ftbquests.RsAutocraftProgressTracker;
import com.refinedmods.refinedstorage.api.autocrafting.ICraftingManager;
import com.refinedmods.refinedstorage.api.autocrafting.task.ICraftingTask;
import com.refinedmods.refinedstorage.apiimpl.network.grid.handler.ItemGridHandler;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.UUID;

@Mixin(value = ItemGridHandler.class, remap = false)
public abstract class ItemGridHandlerMixin {
    @Redirect(method = "onCraftingRequested",
            at = @At(value = "INVOKE",
                    target = "Lcom/refinedmods/refinedstorage/api/autocrafting/ICraftingManager;start(Lcom/refinedmods/refinedstorage/api/autocrafting/task/ICraftingTask;)V"),
            remap = false)
    private void rsi$rememberGridRequester(ICraftingManager manager, ICraftingTask task,
                                           ServerPlayer player, UUID stackId, int quantity) {
        RsAutocraftProgressTracker.remember(task.getId(), player);
        manager.start(task);
    }
}
