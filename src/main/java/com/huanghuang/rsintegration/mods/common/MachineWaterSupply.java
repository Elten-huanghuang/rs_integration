package com.huanghuang.rsintegration.mods.common;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;

public final class MachineWaterSupply {
    private MachineWaterSupply() {}

    public static boolean isFree(String machine) {
        return isFree(machine, RSIntegrationConfig.SERVER_SPEC.isLoaded()
                ? RSIntegrationConfig.FREE_WATER_MACHINES.get()
                : RSIntegrationConfig.FREE_WATER_MACHINES.getDefault());
    }

    public static boolean isFree(String machine, List<? extends String> configured) {
        return configured.contains(machine);
    }

    public static int fill(String machine, IFluidHandler tank, int amount,
                           CraftStorageEndpoint endpoint, ServerPlayer player) {
        return fill(tank, amount, isFree(machine),
                requested -> extractWater(endpoint, player, requested),
                water -> refundWater(endpoint, player, water));
    }

    public static boolean canFill(String machine, IFluidHandler tank, int amount,
                                  CraftStorageEndpoint endpoint, ServerPlayer player) {
        if (amount <= 0) return true;
        if (tank.fill(new FluidStack(Fluids.WATER, amount), IFluidHandler.FluidAction.SIMULATE) != amount) return false;
        return isFree(machine) || extractWater(endpoint, player, amount, true).getAmount() == amount;
    }

    public static boolean fillProperty(String machine, int amount, CraftStorageEndpoint endpoint,
                                        ServerPlayer player, BooleanSupplier update) {
        return fillProperty(amount, isFree(machine), requested -> extractWater(endpoint, player, requested),
                water -> refundWater(endpoint, player, water), update);
    }

    static boolean fillProperty(int amount, boolean free, IntFunction<FluidStack> extract,
                                Consumer<FluidStack> refund, BooleanSupplier update) {
        if (amount <= 0) return false;
        if (free) return update.getAsBoolean();
        FluidStack held = extract.apply(amount);
        if (held.getAmount() != amount || held.getFluid() != Fluids.WATER) {
            if (!held.isEmpty()) refund.accept(held);
            return false;
        }
        boolean updated = false;
        try {
            updated = update.getAsBoolean();
            return updated;
        } finally {
            if (!updated) refund.accept(held);
        }
    }

    static int fill(IFluidHandler tank, int amount, boolean free,
                    IntFunction<FluidStack> extract, Consumer<FluidStack> refund) {
        if (amount <= 0) return 0;
        FluidStack requested = new FluidStack(Fluids.WATER, amount);
        if (tank.fill(requested.copy(), IFluidHandler.FluidAction.SIMULATE) != amount) return 0;
        FluidStack held = free ? requested : extract.apply(amount);
        if (held.isEmpty() || !held.isFluidEqual(requested) || held.getAmount() != amount) {
            if (!free && !held.isEmpty()) refund.accept(held);
            return 0;
        }
        int filled = tank.fill(held.copy(), IFluidHandler.FluidAction.EXECUTE);
        if (filled == amount) return filled;
        int recovered = filled > 0 ? tank.drain(new FluidStack(Fluids.WATER, filled),
                IFluidHandler.FluidAction.EXECUTE).getAmount() : 0;
        if (!free && amount - filled + recovered > 0) {
            refund.accept(new FluidStack(Fluids.WATER, amount - filled + recovered));
        }
        return filled - recovered;
    }

    private static FluidStack extractWater(CraftStorageEndpoint endpoint, ServerPlayer player, int amount) {
        if (extractWater(endpoint, player, amount, true).getAmount() != amount) return FluidStack.EMPTY;
        return extractWater(endpoint, player, amount, false);
    }

    private static FluidStack extractWater(CraftStorageEndpoint endpoint, ServerPlayer player, int amount, boolean simulate) {
        if (endpoint == null || player == null || amount <= 0
                || !endpoint.session().reference().backendId().value().equals("refinedstorage")) return FluidStack.EMPTY;
        ItemStack template = InkFluidSupport.token(new FluidStack(Fluids.WATER, 1));
        var result = endpoint.extractExact(player, template, amount, simulate);
        if (simulate && result.status() != StorageOperationStatus.SUCCESS) return FluidStack.EMPTY;
        List<ItemStack> extractedStacks = result.extractedStacks();
        if (!simulate) result.recoveryStacks().forEach(stack -> refundToken(endpoint, player, stack));
        FluidStack requested = new FluidStack(Fluids.WATER, amount);
        if (extractedStacks.stream().anyMatch(stack -> !InkFluidSupport.fluid(stack).isFluidEqual(requested))) {
            if (!simulate) extractedStacks.forEach(stack -> refundToken(endpoint, player, stack));
            return FluidStack.EMPTY;
        }
        int extracted = extractedStacks.stream().mapToInt(ItemStack::getCount).sum();
        return extracted == 0 ? FluidStack.EMPTY : new FluidStack(Fluids.WATER, extracted);
    }

    public static void refundWater(CraftStorageEndpoint endpoint, ServerPlayer player, FluidStack water) {
        if (water.isEmpty()) return;
        refundToken(endpoint, player, InkFluidSupport.token(water));
    }

    private static void refundToken(CraftStorageEndpoint endpoint, ServerPlayer player, ItemStack token) {
        ItemStack remainder = endpoint.insert(player, token, false).remainder().orElse(token);
        if (!remainder.isEmpty()) ItemHandlerHelper.giveItemToPlayer(player, remainder);
    }
}
