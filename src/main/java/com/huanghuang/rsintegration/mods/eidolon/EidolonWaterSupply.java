package com.huanghuang.rsintegration.mods.eidolon;

import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.mods.common.MachineWaterSupply;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Predicate;

public final class EidolonWaterSupply {
    private EidolonWaterSupply() {}

    @SuppressWarnings("unchecked")
    public static boolean isHeated(BlockEntity crucible) {
        Level level = crucible.getLevel();
        if (level == null || !level.hasChunkAt(crucible.getBlockPos().below())) return false;
        try {
            Field sourcesField = crucible.getClass().getField("HOT_BLOCKS");
            List<Predicate<BlockState>> sources = (List<Predicate<BlockState>>) sourcesField.get(null);
            BlockState below = level.getBlockState(crucible.getBlockPos().below());
            return sources.stream().anyMatch(source -> source.test(below));
        } catch (NoSuchFieldException missingSources) {
            try {
                Field boiling = crucible.getClass().getDeclaredField("boiling");
                boiling.setAccessible(true);
                return boiling.getBoolean(crucible);
            } catch (ReflectiveOperationException failure) {
                return false;
            }
        } catch (ReflectiveOperationException | ClassCastException failure) {
            return false;
        }
    }

    public static boolean ensureWater(BlockEntity crucible, int required,
                                      CraftStorageEndpoint endpoint, ServerPlayer player) {
        if (required <= 0) return true;
        IFluidHandler tank = crucible.getCapability(ForgeCapabilities.FLUID_HANDLER).resolve().orElse(null);
        return ensureWater(tank, required, endpoint, player, crucible::setChanged);
    }

    static boolean ensureWater(IFluidHandler tank, int required, CraftStorageEndpoint endpoint,
                               ServerPlayer player, Runnable changed) {
        if (required <= 0) return true;
        if (tank == null || tank.getTanks() == 0) return false;
        FluidStack existing = tank.getFluidInTank(0);
        if (!existing.isEmpty() && existing.getFluid() != Fluids.WATER) return false;
        int missing = Math.max(0, Math.max(1000, required) - existing.getAmount());
        if (missing == 0) return true;
        if (MachineWaterSupply.fill("eidolon_crucible", tank, missing, endpoint, player) != missing) return false;
        changed.run();
        return true;
    }
}
