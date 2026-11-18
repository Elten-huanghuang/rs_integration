package com.huanghuang.rsintegration.mods.crockpot;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.reflection.probes.CrockPotReflection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

public final class CrockPotInventory {
    private CrockPotInventory() {}

    private static class Access {
        static final Field HANDLER = findHandler();

        private static Field findHandler() {
            try {
                var field = CrockPotReflection.crockPotBEClass.getDeclaredField("itemHandler");
                field.setAccessible(true);
                return field;
            } catch (Exception failure) {
                RSIntegrationMod.LOGGER.debug("无法读取烹饪锅物品栏字段", failure);
                return null;
            }
        }
    }

    static IItemHandler getItemHandler(BlockEntity be) {
        if (Access.HANDLER != null) {
            try {
                return (IItemHandler) Access.HANDLER.get(be);
            } catch (IllegalAccessException failure) {
                RSIntegrationMod.LOGGER.debug("无法访问烹饪锅物品栏", failure);
            }
        }
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
    }

    static int getBlockPotLevel(Level level, @Nullable BlockPos pos) {
        if (level == null || pos == null || CrockPotReflection.crockPotBEClass == null) return -1;
        var be = level.getBlockEntity(pos);
        if (!CrockPotReflection.crockPotBEClass.isInstance(be)) return -1;
        var handler = getItemHandler(be);
        return handler == null ? -1 : handler.getSlots() - 2;
    }

    public static int planningSlots(Level level, @Nullable BlockPos pos) {
        int slots = getBlockPotLevel(level, pos);
        return slots > 0 ? slots : CrockPotRecipeHandler.INPUT_SLOT_COUNT;
    }
}
