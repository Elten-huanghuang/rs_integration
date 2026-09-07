package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.reflection.probes.GoetyReflection;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Goety-specific material source retained by the common availability service. */
public final class GoetyMaterialAvailability {
    private GoetyMaterialAvailability() {}

    @Nullable
    public static List<ItemStack> pedestalItems(ServerLevel level, BlockPos pos, Recipe<?> recipe) {
        try {
            Object ritual = Reflect.invoke(recipe, GoetyReflection.M_GET_RITUAL).orElse(null);
            if (ritual == null) return null;
            Object found = ritual.getClass().getMethod("getPedestals", Level.class, BlockPos.class)
                    .invoke(ritual, level, pos);
            if (!(found instanceof List<?> pedestals)) return null;
            List<ItemStack> items = new ArrayList<>();
            Set<BlockPos> visited = new HashSet<>();
            for (Object pedestal : pedestals) {
                if (!(pedestal instanceof BlockEntity be) || !visited.add(be.getBlockPos())) continue;
                if (!level.hasChunkAt(be.getBlockPos())) return null;
                Object value = Reflect.getField(be, "itemStackHandler").orElse(null);
                if (!(value instanceof LazyOptional<?> optional)) return null;
                Object handler = optional.resolve().orElse(null);
                if (!(handler instanceof IItemHandler inventory)) return null;
                for (int slot = 0; slot < inventory.getSlots(); slot++) {
                    ItemStack stack = inventory.getStackInSlot(slot);
                    if (!stack.isEmpty()) items.add(stack.copy());
                }
            }
            return items;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return null;
        }
    }
}
