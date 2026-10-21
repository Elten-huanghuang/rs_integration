package com.huanghuang.rsintegration.machine;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.ForgeHooks;

import javax.annotation.Nullable;
import java.util.Locale;

/** 原版炉与 Iron Furnaces 的统一槽位、燃料和输出访问。 */
public final class FurnaceMachineAccess {
    public static final int INPUT_SLOT = 0;
    public static final int FUEL_SLOT = 1;
    public static final int OUTPUT_SLOT = 2;

    private FurnaceMachineAccess() {}

    public enum Error {
        EMPTY_HAND,
        WRONG_TYPE,
        UNSUPPORTED_MODE,
        NO_OUTPUT,
        NOT_FUEL,
        SLOT_MISMATCH,
        SLOT_FULL
    }

    public record Result(
            boolean success, @Nullable Error error, ItemStack stack) {
        static Result success(ItemStack stack) {
            return new Result(true, null, stack);
        }

        static Result failure(Error error) {
            return new Result(false, error, ItemStack.EMPTY);
        }
    }

    public static boolean isIronFurnace(@Nullable BlockEntity entity) {
        return entity != null && isIronFurnace(entity.getClass());
    }

    public static boolean isIronFurnace(@Nullable Class<?> type) {
        while (type != null) {
            if ("ironfurnaces.tileentity.furnaces.BlockIronFurnaceTileBase"
                    .equals(type.getName())) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    /** 客户端无 BlockEntity 时，仅把服务端快速操作确实支持的炉类判为 QUICK。 */
    public static boolean supportsQuickActions(@Nullable String blockKey) {
        if (blockKey == null) return false;
        String key = blockKey.toLowerCase(Locale.ROOT);
        if (key.contains("ironfurnaces_")
                || key.contains("ironfurnaces:")) {
            return true;
        }
        return key.contains("minecraft.furnace")
                || key.contains("minecraft:furnace")
                || key.contains("minecraft.blast_furnace")
                || key.contains("minecraft:blast_furnace")
                || key.contains("minecraft.smoker")
                || key.contains("minecraft:smoker");
    }

    public static Result extractOutput(@Nullable BlockEntity entity) {
        if (isIronFurnace(entity)) {
            if (!(entity instanceof Container container)
                    || !isOrdinaryIronFurnace(entity)) {
                return Result.failure(Error.UNSUPPORTED_MODE);
            }
            ItemStack output = container.getItem(OUTPUT_SLOT).copy();
            if (output.isEmpty()) return Result.failure(Error.NO_OUTPUT);
            container.setItem(OUTPUT_SLOT, ItemStack.EMPTY);
            entity.setChanged();
            return Result.success(output);
        }
        if (!(entity instanceof AbstractFurnaceBlockEntity furnace)) {
            return Result.failure(Error.WRONG_TYPE);
        }
        ItemStack output = furnace.getItem(OUTPUT_SLOT).copy();
        if (output.isEmpty()) return Result.failure(Error.NO_OUTPUT);
        furnace.setItem(OUTPUT_SLOT, ItemStack.EMPTY);
        furnace.setChanged();
        return Result.success(output);
    }

    public static Result insertCarried(
            ServerPlayer player, @Nullable BlockEntity entity,
            MachineSlotType slotType) {
        return insertCarried(player.containerMenu, entity, slotType);
    }

    public static Result insertCarried(
            AbstractContainerMenu menu, @Nullable BlockEntity entity,
            MachineSlotType slotType) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) return Result.failure(Error.EMPTY_HAND);
        int targetSlot = slotType == MachineSlotType.FUEL
                ? FUEL_SLOT : INPUT_SLOT;

        if (isIronFurnace(entity)) {
            if (!(entity instanceof Container container)
                    || !isOrdinaryIronFurnace(entity)) {
                return Result.failure(Error.UNSUPPORTED_MODE);
            }
            if (slotType == MachineSlotType.FUEL
                    && ForgeHooks.getBurnTime(
                    carried, ironRecipeType(entity)) <= 0) {
                return Result.failure(Error.NOT_FUEL);
            }
            return insertIntoContainer(
                    menu, entity, container, carried, targetSlot);
        }
        if (!(entity instanceof AbstractFurnaceBlockEntity furnace)) {
            return Result.failure(Error.WRONG_TYPE);
        }
        if (slotType == MachineSlotType.FUEL
                && ForgeHooks.getBurnTime(carried, null) <= 0) {
            return Result.failure(Error.NOT_FUEL);
        }
        return insertIntoContainer(
                menu, entity, furnace, carried, targetSlot);
    }

    @Nullable
    public static Component errorMessage(Error error) {
        return switch (error) {
            case EMPTY_HAND -> null;
            case WRONG_TYPE -> Component.translatable(
                    "rsi.machine.error.wrong_type");
            case UNSUPPORTED_MODE -> Component.translatable(
                    "rsi.ironfurnaces.error.machine_mode_unsupported");
            case NO_OUTPUT -> Component.translatable(
                    "rsi.machine.error.no_output");
            case NOT_FUEL -> Component.translatable(
                    "rsi.machine.error.not_fuel");
            case SLOT_MISMATCH -> Component.translatable(
                    "rsi.machine.error.slot_mismatch");
            case SLOT_FULL -> Component.translatable(
                    "rsi.machine.error.slot_full");
        };
    }

    private static Result insertIntoContainer(
            AbstractContainerMenu menu, BlockEntity entity, Container container,
            ItemStack carried, int targetSlot) {
        ItemStack existing = container.getItem(targetSlot);
        if (!existing.isEmpty()
                && !ItemStack.isSameItemSameTags(existing, carried)) {
            return Result.failure(Error.SLOT_MISMATCH);
        }
        int capacity = Math.min(
                container.getMaxStackSize(), carried.getMaxStackSize());
        int inserted = Math.min(
                carried.getCount(), capacity - existing.getCount());
        if (inserted <= 0) return Result.failure(Error.SLOT_FULL);

        ItemStack merged = existing.isEmpty()
                ? carried.copy() : existing.copy();
        merged.setCount(existing.getCount() + inserted);
        container.setItem(targetSlot, merged);
        ItemStack remaining = carried.copyWithCount(
                carried.getCount() - inserted);
        menu.setCarried(
                remaining.isEmpty() ? ItemStack.EMPTY : remaining);
        entity.setChanged();
        return Result.success(merged.copy());
    }

    static boolean isOrdinaryIronFurnace(BlockEntity entity) {
        try {
            return (boolean) entity.getClass().getMethod("isFurnace")
                    .invoke(entity);
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    static boolean isFactoryIronFurnace(BlockEntity entity) {
        try {
            return (boolean) entity.getClass().getMethod("isFactory")
                    .invoke(entity);
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    @Nullable
    static RecipeType<?> ironRecipeType(BlockEntity entity) {
        try {
            Class<?> base = Class.forName(
                    "ironfurnaces.tileentity.furnaces.BlockIronFurnaceTileBase");
            return (RecipeType<?>) base.getField("recipeType").get(entity);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }
}
