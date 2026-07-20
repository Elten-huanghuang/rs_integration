package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.apiimpl.network.node.GridNetworkNode;
import com.refinedmods.refinedstorage.blockentity.grid.GridBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.api.IItemHandlerInteractionUpgrade;
import net.p3pp3rf1y.sophisticatedbackpacks.upgrades.deposit.DepositUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.util.InventoryInteractionHelper;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = InventoryInteractionHelper.class)
public class InventoryInteractionHelperMixin {

    @Inject(method = "tryInventoryInteraction(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/core/Direction;Lnet/minecraft/world/entity/player/Player;)Z",
            at = @At(value = "HEAD"),
            remap = false,
            cancellable = true)
    private static void tryInventoryInteraction(BlockPos pos, Level world, ItemStack backpack,
                                                 Direction face, Player player,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if (!RSIntegrationConfig.DEPOSIT_UPGRADE_RS.get()) return;
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof GridBlockEntity)) return;

        GridBlockEntity grid = (GridBlockEntity) blockEntity;
        cir.cancel();
        GridNetworkNode node = grid.getNode();
        if (node == null) {
            cir.setReturnValue(false);
            return;
        }
        INetwork network = node.getNetwork();
        if (!world.isClientSide && (network == null || !network.canRun()
                || network.getSecurityManager() != null
                && !network.getSecurityManager().hasPermission(Permission.INSERT, player))) {
            cir.setReturnValue(false);
            return;
        }
        Boolean b = backpack.getCapability(CapabilityBackpackWrapper.getCapabilityInstance()).map(wrapper -> {
            List<IItemHandlerInteractionUpgrade> interactionUpgrades =
                    wrapper.getUpgradeHandler().getWrappersThatImplement(IItemHandlerInteractionUpgrade.class);
            List<DepositUpgradeWrapper> depositUpgrades = interactionUpgrades.stream()
                    .filter(DepositUpgradeWrapper.class::isInstance)
                    .map(DepositUpgradeWrapper.class::cast)
                    .filter(DepositUpgradeWrapper::isEnabled)
                    .toList();
            if (depositUpgrades.isEmpty()) {
                return false;
            }
            if (world instanceof ServerLevel) {
                for (DepositUpgradeWrapper depositUpgrade : depositUpgrades) {
                    handleAutoOutput(network, wrapper, depositUpgrade, player);
                }
            }
            return true;
        }).orElse(false);
        cir.setReturnValue(b);
    }

    @Unique
    private static boolean handleAutoOutput(INetwork network, IStorageWrapper wrapper,
                                             DepositUpgradeWrapper upgrade, Player player) {
        InventoryHandler backpackHandler = wrapper.getInventoryHandler();
        boolean transferred = false;
        int s1 = 0;
        int s2 = 0;
        for (int i = 0; i < backpackHandler.getSlots(); ++i) {
            ItemStack stack = backpackHandler.getStackInSlot(i);
            if (stack.isEmpty() || !upgrade.getFilterLogic().matchesFilter(stack)) continue;
            int originalCount = stack.getCount();
            ItemStack input = stack.copy();
            ItemStack simulatedRemainder = network.insertItem(
                    input.copy(), originalCount, Action.SIMULATE);
            int accepted = originalCount - simulatedRemainder.getCount();
            if (accepted <= 0) continue;

            // RS updates its own tracker before insertion, so cache listeners see
            // the new timestamp while processing the synchronous storage delta.
            var tracker = network.getItemStorageTracker();
            if (tracker != null) tracker.changed(player, input.copyWithCount(accepted));

            ItemStack remainder = network.insertItem(input, originalCount, Action.PERFORM);
            int inserted = originalCount - remainder.getCount();
            if (inserted <= 0) continue;
            s2 += inserted;
            backpackHandler.extractItem(i, inserted, false);
            ++s1;
            transferred = true;
        }
        String translKey = s1 > 0
                ? "gui.rs_integration.status.stacks_deposited"
                : "gui.rs_integration.status.nothing_to_deposit";
        player.displayClientMessage(Component.translatable(translKey, s1, s2), true);
        return transferred;
    }
}
