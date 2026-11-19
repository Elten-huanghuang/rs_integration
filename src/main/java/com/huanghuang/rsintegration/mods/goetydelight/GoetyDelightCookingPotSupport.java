package com.huanghuang.rsintegration.mods.goetydelight;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.wrapper.RangedWrapper;

import java.lang.reflect.Method;

/** 诅咒金属锅和神灵金属锅共用的烹饪库存。 */
public final class GoetyDelightCookingPotSupport {
    private static final int SOUL_SOURCE_SLOT = 9;
    private static final String CURSED_POT_ENTITY =
            "net.v_black_cat.goetydelight.block.CursedIngotPotBlockEntity";
    private static final String APOCALYPTIUM_POT_ENTITY =
            "net.v_black_cat.goetydelight.compat.goetyrevelation.block.ApocalyptiumCookingPotBlockEntity";

    private GoetyDelightCookingPotSupport() {}

    public static boolean isCookingPot(BlockEntity blockEntity) {
        if (blockEntity == null) return false;
        String className = blockEntity.getClass().getName();
        return CURSED_POT_ENTITY.equals(className) || APOCALYPTIUM_POT_ENTITY.equals(className);
    }

    public static IItemHandler getInventory(BlockEntity blockEntity) {
        if (!isCookingPot(blockEntity)) return null;
        try {
            Method method = blockEntity.getClass().getMethod("getInventory");
            Object value = method.invoke(blockEntity);
            return value instanceof IItemHandlerModifiable handler ? cookingSlots(handler) : null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            RSIntegrationMod.LOGGER.debug("[RSI-GoetyDelight] 无法读取金属锅库存", exception);
            return null;
        }
    }

    static IItemHandler cookingSlots(IItemHandlerModifiable handler) {
        if (handler.getSlots() <= SOUL_SOURCE_SLOT) return null;
        // 配方发料、清理和回收只接触烹饪槽，灵魂能量由锅自行管理。
        return new RangedWrapper(handler, 0, SOUL_SOURCE_SLOT);
    }

    public static boolean matchesOutputIgnoringNbt(ItemStack actual, ItemStack expected) {
        return actual != null && expected != null
                && !actual.isEmpty() && !expected.isEmpty()
                && ItemStack.isSameItem(actual, expected)
                && actual.getCount() >= expected.getCount();
    }
}
