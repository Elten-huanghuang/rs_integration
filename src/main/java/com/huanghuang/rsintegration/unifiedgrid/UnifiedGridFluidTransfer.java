package com.huanghuang.rsintegration.unifiedgrid;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftFluidRecovery;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerTransfer;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.refinedmods.refinedstorage.RS;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;

import java.util.function.Function;

/** RS 终端适配器；与 BD 终端共用容器转移结算。 */
public final class UnifiedGridFluidTransfer {
    private UnifiedGridFluidTransfer() { }

    public record Result(ItemStack cursor, ItemStack overflow, FluidStack recovery,
                         FluidStack resource, int transferred) { }

    public static Result fill(INetwork network, ItemStack cursor, FluidStack fluid) {
        return result(FluidContainerTransfer.fill(storage(network), cursor, fluid));
    }

    static Result fill(INetwork network, ItemStack cursor, FluidStack fluid,
                       Function<ItemStack, IFluidHandlerItem> containers) {
        return result(FluidContainerTransfer.fill(storage(network), cursor, fluid, containers));
    }

    public static Result empty(INetwork network, ItemStack cursor) {
        return result(FluidContainerTransfer.empty(storage(network), cursor));
    }

    static Result empty(INetwork network, ItemStack cursor, Function<ItemStack, IFluidHandlerItem> containers) {
        return result(FluidContainerTransfer.empty(storage(network), cursor, containers));
    }

    private static Result result(FluidContainerTransfer.Result result) {
        return new Result(result.cursor(), result.overflow(), result.recovery(), result.resource(), result.transferred());
    }

    private static FluidContainerTransfer.Storage storage(INetwork network) {
        return new FluidContainerTransfer.Storage() {
            @Override public ItemStack extractItem(ItemStack stack, int amount, boolean simulate) {
                return network.extractItem(stack, amount, simulate ? Action.SIMULATE : Action.PERFORM);
            }
            @Override public ItemStack insertItem(ItemStack stack, int amount, boolean simulate) {
                return network.insertItem(stack, amount, simulate ? Action.SIMULATE : Action.PERFORM);
            }
            @Override public FluidStack extractFluid(FluidStack fluid, int amount, boolean simulate) {
                return network.extractFluid(fluid, amount, simulate ? Action.SIMULATE : Action.PERFORM);
            }
            @Override public FluidStack insertFluid(FluidStack fluid, int amount, boolean simulate) {
                return network.insertFluid(fluid, amount, simulate ? Action.SIMULATE : Action.PERFORM);
            }
        };
    }

    /** 混合终端与原生流体终端共用结算，异常余量封装为退款物品。 */
    public static void apply(ServerPlayer player, INetwork network, Result result, boolean filling, boolean shift) {
        if (!result.recovery().isEmpty()) {
            CraftFluidRecovery.retain(player, InkFluidSupport.token(result.recovery()), network);
            RSIntegrationMod.LOGGER.warn("终端容器转移留下 {} mB 流体，已封装为退款物品", result.recovery().getAmount());
        }
        player.containerMenu.setCarried(result.cursor());
        if (!result.overflow().isEmpty()) player.getInventory().placeItemBackInInventory(result.overflow());
        else if (filling && shift && result.transferred() > 0 && !result.cursor().isEmpty()) {
            ItemStack copy = result.cursor().copy();
            player.getInventory().add(copy);
            player.containerMenu.setCarried(copy);
        }
        if (result.transferred() <= 0) return;
        FluidStack tracked = result.resource().copy();
        tracked.setAmount(result.transferred());
        network.getFluidStorageTracker().changed(player, tracked);
        if (network.getNetworkItemManager() != null) {
            var config = RS.SERVER_CONFIG.getWirelessFluidGrid();
            network.getNetworkItemManager().drainEnergy(player, filling ? config.getExtractUsage() : config.getInsertUsage());
        }
    }

}
