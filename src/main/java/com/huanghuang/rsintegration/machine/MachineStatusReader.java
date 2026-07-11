package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reads live status from a bound Quick-type machine's BlockEntity.
 * Only supports {@link AbstractFurnaceBlockEntity} (furnace/blast/smoker/campfire).
 */
public final class MachineStatusReader {

    private MachineStatusReader() {}

    // Cached reflective accessors for AbstractFurnaceBlockEntity private fields
    private static final Field COOKING_PROGRESS = resolveField("cookingProgress", "f_18769_");
    private static final Field COOKING_TOTAL_TIME = resolveField("cookingTotalTime", "f_18768_");
    // MCP dev name + SRG name fallback for vanilla isLit()
    private static final Method IS_LIT = resolveIsLit();

    private static Method resolveIsLit() {
        String[] names = {"isLit", "m_6050_"};
        for (String name : names) {
            var m = Reflect.findMethod(AbstractFurnaceBlockEntity.class, name, new Class<?>[0]);
            if (m != null) return m;
        }
        RSIntegrationMod.LOGGER.warn("[RSI-MachineStatus] isLit method not found (no SRG match)");
        return null;
    }

    private static Field resolveField(String mcp, String srg) {
        var f = Reflect.findField(AbstractFurnaceBlockEntity.class, mcp);
        if (f.isPresent()) return f.get();
        f = Reflect.findField(AbstractFurnaceBlockEntity.class, srg);
        return f.orElse(null);
    }

    /**
     * Read the current status of a machine at the given position.
     * Returns {@link MachineStatus#UNKNOWN} for unsupported or missing BEs.
     */
    public static MachineStatus read(Level level, BlockPos pos) {
        if (level == null || pos == null) return MachineStatus.UNKNOWN;
        if (!level.isLoaded(pos)) return MachineStatus.UNKNOWN;

        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return MachineStatus.UNKNOWN;

        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            return readFurnace(furnace);
        }

        return MachineStatus.UNKNOWN;
    }

    private static MachineStatus readFurnace(AbstractFurnaceBlockEntity furnace) {
        ItemStack input  = furnace.getItem(0).copy();
        ItemStack fuel   = furnace.getItem(1).copy();
        ItemStack output = furnace.getItem(2).copy();

        int cookProgress = readIntField(furnace, COOKING_PROGRESS);
        int cookTotal    = readIntField(furnace, COOKING_TOTAL_TIME);

        boolean lit = false;
        if (IS_LIT != null) {
            try {
                lit = (boolean) IS_LIT.invoke(furnace);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-MachineStatus] isLit invocation failed", e);
            }
        }

        MachineState state;
        if (!output.isEmpty()) {
            state = MachineState.HAS_OUTPUT;
        } else if (lit || (!input.isEmpty() && cookProgress > 0)) {
            state = MachineState.WORKING;
        } else {
            state = MachineState.IDLE;
        }

        int displayMax = cookTotal > 0 ? cookTotal : 200;
        return new MachineStatus(state, cookProgress, displayMax, input, output, fuel);
    }

    private static int readIntField(Object target, Field field) {
        if (field == null || target == null) return 0;
        try {
            return field.getInt(target);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-MachineStatus] readIntField failed for {}", field, e);
            return 0;
        }
    }
}
