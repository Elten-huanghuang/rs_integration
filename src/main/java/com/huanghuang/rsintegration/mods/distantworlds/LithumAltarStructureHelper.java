package com.huanghuang.rsintegration.mods.distantworlds;

import com.huanghuang.rsintegration.reflection.probes.DistantWorldsReflection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.List;

public final class LithumAltarStructureHelper {
    public static final List<BlockPos> PEDESTAL_OFFSETS = List.of(
            new BlockPos(-2, 0, -1), new BlockPos(-1, 0, -2),
            new BlockPos(1, 0, -2), new BlockPos(2, 0, -1),
            new BlockPos(2, 0, 1), new BlockPos(1, 0, 2),
            new BlockPos(-1, 0, 2), new BlockPos(-2, 0, 1));

    private LithumAltarStructureHelper() {}

    public static List<BlockPos> pedestalPositions(BlockPos corePos) {
        return PEDESTAL_OFFSETS.stream().map(corePos::offset).toList();
    }

    public static boolean isCore(BlockEntity be) {
        return be != null && DistantWorldsReflection.lithumCoreBEClass != null
                && DistantWorldsReflection.lithumCoreBEClass.isInstance(be);
    }

    public static boolean isPedestal(BlockEntity be) {
        return be != null && DistantWorldsReflection.lithumPedestalBEClass != null
                && DistantWorldsReflection.lithumPedestalBEClass.isInstance(be);
    }

    public static boolean isFurnace(BlockEntity be) {
        return be != null && DistantWorldsReflection.lithumFurnaceBEClass != null
                && DistantWorldsReflection.lithumFurnaceBEClass.isInstance(be);
    }

    public static ItemStack getSlot(BlockEntity be, int slot) {
        if (be == null) return ItemStack.EMPTY;
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).resolve()
                .filter(handler -> slot >= 0 && slot < handler.getSlots())
                .map(handler -> handler.getStackInSlot(slot).copy())
                .orElse(ItemStack.EMPTY);
    }

    public static boolean setSlot(BlockEntity be, int slot, ItemStack stack) {
        if (be == null) return false;
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).resolve().map(handler -> {
            if (!(handler instanceof IItemHandlerModifiable modifiable)
                    || slot < 0 || slot >= handler.getSlots()) return false;
            modifiable.setStackInSlot(slot, stack.copy());
            be.setChanged();
            Level level = be.getLevel();
            if (level != null) {
                level.sendBlockUpdated(be.getBlockPos(), be.getBlockState(), be.getBlockState(), 3);
            }
            return true;
        }).orElse(false);
    }
}
