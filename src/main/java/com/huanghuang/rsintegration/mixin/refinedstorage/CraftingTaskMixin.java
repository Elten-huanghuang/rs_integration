package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.compat.ftbquests.ExternalItemProgressBridge;
import com.huanghuang.rsintegration.compat.ftbquests.RsAutocraftProgressTracker;
import com.refinedmods.refinedstorage.api.autocrafting.task.ICraftingRequestInfo;
import com.refinedmods.refinedstorage.api.autocrafting.task.ICraftingTask;
import com.refinedmods.refinedstorage.apiimpl.autocrafting.task.v6.CraftingTask;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(value = CraftingTask.class, remap = false)
public abstract class CraftingTaskMixin {
    @Inject(method = "update", at = @At("RETURN"), remap = false)
    private void rsi$reportCompletedOutput(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        CraftingTask task = (CraftingTask) (Object) this;
        UUID ownerId = RsAutocraftProgressTracker.owner(task.getId());
        if (ownerId == null) {
            RsAutocraftProgressTracker.forget(task.getId());
            return;
        }
        ICraftingRequestInfo requested = task.getRequested();
        if (requested == null || requested.getItem() == null || requested.getItem().isEmpty()) {
            RsAutocraftProgressTracker.forget(task.getId());
            return;
        }
        INetwork network = ((CraftingTaskAccessor) task).rsi$getNetwork();
        if (network == null) {
            RsAutocraftProgressTracker.forget(task.getId());
            return;
        }
        // The task has already inserted into RS. Report the requested final
        // output once, using the exact requested stack and requested quantity.
        MinecraftServer server = network.getLevel() == null ? null : network.getLevel().getServer();
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(ownerId);
        if (player != null) {
            ExternalItemProgressBridge.enqueueCrafted(player,
                    requested.getItem().copyWithCount(task.getQuantity()));
        }
        RsAutocraftProgressTracker.forget(task.getId());
    }
}
