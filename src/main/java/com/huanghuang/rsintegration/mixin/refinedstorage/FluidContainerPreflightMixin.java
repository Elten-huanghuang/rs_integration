package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.config.RSStorageConfig;
import com.huanghuang.rsintegration.mods.ironsspellbooks.AlchemistBottleSupport;
import com.huanghuang.rsintegration.unifiedgrid.UnifiedGridFluidTransfer;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.apiimpl.network.grid.handler.FluidGridHandler;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(value = FluidGridHandler.class, remap = false)
public abstract class FluidContainerPreflightMixin {
    @Shadow @Final private INetwork network;

    @Inject(method = "onExtract", at = @At("HEAD"), cancellable = true)
    private void rsi$extractInkBottle(ServerPlayer player, UUID id, boolean shift, CallbackInfo ci) {
        FluidStack selected = network.getFluidStorageCache().getList().get(id);
        if (selected == null || !AlchemistBottleSupport.canBottle(selected)) return;
        // 原生路径要求至少 1000 mB 并且只取桶，瓶装流体需要单独校验权限与菜单。
        ci.cancel();
        if (!(player.containerMenu instanceof GridContainerMenu menu)
                || !(menu.getGrid() instanceof INetworkAwareGrid grid) || grid.getNetwork() != network
                || !menu.stillValid(player) || !menu.getGrid().isGridActive() || !network.canRun()
                || !network.getSecurityManager().hasPermission(Permission.EXTRACT, player)) return;
        UnifiedGridFluidTransfer.apply(player, network,
                UnifiedGridFluidTransfer.fill(network, menu.getCarried(), selected), true, shift);
    }

    @Inject(method = "onExtract", at = @At(value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/util/NetworkUtils;extractBucketFromPlayerInventoryOrNetwork(Lnet/minecraft/world/entity/player/Player;Lcom/refinedmods/refinedstorage/api/network/INetwork;Ljava/util/function/Consumer;)V"),
            cancellable = true)
    private void rsi$simulateContainerFill(ServerPlayer player, UUID id, boolean shift, CallbackInfo ci) {
        // 原版数量、权限和供电检查已经通过，尚未取走空桶或流体。
        if (!RSStorageConfig.enabled(RSStorageConfig.CHECK_FLUID_CONTAINER)) return;
        FluidStack selected = network.getFluidStorageCache().getList().get(id);
        if (selected == null) return;
        // 归墟盘的索引列表对 COMPARE_QUANTITY 使用精确数量匹配；容器取液需要的是
        // 至少一桶，因此用不带数量比较的请求，避免正好 1000 mB 时被误判为空。
        FluidStack available = network.extractFluid(selected, FluidType.BUCKET_VOLUME,
                IComparer.COMPARE_NBT, Action.SIMULATE);
        if (available.isEmpty() || available.getAmount() < FluidType.BUCKET_VOLUME) {
            ci.cancel();
            return;
        }
        int accepted = new ItemStack(Items.BUCKET).getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).resolve()
                .map(handler -> handler.fill(available, IFluidHandler.FluidAction.SIMULATE)).orElse(0);
        if (accepted < FluidType.BUCKET_VOLUME) ci.cancel();
    }
}
