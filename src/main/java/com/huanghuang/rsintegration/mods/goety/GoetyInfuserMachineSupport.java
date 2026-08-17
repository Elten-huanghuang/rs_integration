package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Reflection-only compatibility for Goety's three cursed-infusion machines. */
public final class GoetyInfuserMachineSupport {
    public static final String CURSED_BE =
            "com.Polarice3.Goety.common.blocks.entities.CursedInfuserBlockEntity";
    public static final String GRIM_BE =
            "com.Polarice3.Goety.common.blocks.entities.GrimInfuserBlockEntity";
    public static final String DARK_MENDER_BE =
            "com.k1sak1.goetyawaken.common.blocks.entity.DarkMenderBlockEntity";

    private static final ResourceLocation CURSED_CAGE_ID =
            new ResourceLocation("goety", "cursed_cage");
    private static final ResourceLocation CURSED_INFUSER_ID =
            new ResourceLocation("goety", "cursed_infuser");
    private static final ResourceLocation GRIM_INFUSER_ID =
            new ResourceLocation("goety", "grim_infuser");
    private static final ResourceLocation DARK_MENDER_ID =
            new ResourceLocation("goetyawaken", "dark_mender");

    private GoetyInfuserMachineSupport() {}

    public enum Kind {
        CURSED(CURSED_BE, 1, false),
        GRIM(GRIM_BE, 64, true),
        DARK_MENDER(GoetyInfuserMachineSupport.DARK_MENDER_BE, 64, true);

        private final String blockEntityClass;
        private final int recipeCapacity;
        private final boolean acceptsGrimRecipes;

        Kind(String blockEntityClass, int recipeCapacity, boolean acceptsGrimRecipes) {
            this.blockEntityClass = blockEntityClass;
            this.recipeCapacity = recipeCapacity;
            this.acceptsGrimRecipes = acceptsGrimRecipes;
        }

        public String blockEntityClass() {
            return blockEntityClass;
        }

        public int recipeCapacity() {
            return recipeCapacity;
        }

        public boolean acceptsGrimRecipes() {
            return acceptsGrimRecipes;
        }
    }

    public enum Prerequisite {
        READY,
        SPAWNER_REQUIRED,
        CURSED_CAGE_REQUIRED
    }

    @Nullable
    public static Kind kindOf(@Nullable BlockEntity blockEntity) {
        return blockEntity == null ? null : kindOfClassName(blockEntity.getClass().getName());
    }

    @Nullable
    public static Kind kindOf(Level level, BlockPos pos) {
        Kind blockEntityKind = kindOf(level.getBlockEntity(pos));
        if (blockEntityKind != null) return blockEntityKind;
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(
                level.getBlockState(pos).getBlock());
        if (CURSED_INFUSER_ID.equals(blockId)) return Kind.CURSED;
        if (GRIM_INFUSER_ID.equals(blockId)) return Kind.GRIM;
        if (DARK_MENDER_ID.equals(blockId)) return Kind.DARK_MENDER;
        return null;
    }

    @Nullable
    static Kind kindOfClassName(@Nullable String className) {
        if (CURSED_BE.equals(className)) return Kind.CURSED;
        if (GRIM_BE.equals(className)) return Kind.GRIM;
        if (DARK_MENDER_BE.equals(className)) return Kind.DARK_MENDER;
        return null;
    }

    public static Prerequisite prerequisite(Level level, BlockPos pos, Kind kind) {
        if (kind == Kind.DARK_MENDER) {
            BlockPos cagePos = pos.below();
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(
                    level.getBlockState(cagePos).getBlock());
            if (!CURSED_CAGE_ID.equals(blockId)) return Prerequisite.CURSED_CAGE_REQUIRED;
            BlockEntity cage = level.getBlockEntity(cagePos);
            ItemStack cageItem = invokeItemGetter(cage);
            return cageItem.isEmpty() ? Prerequisite.CURSED_CAGE_REQUIRED : Prerequisite.READY;
        }
        return level.getBlockState(pos.below()).is(Blocks.SPAWNER)
                ? Prerequisite.READY : Prerequisite.SPAWNER_REQUIRED;
    }

    @Nullable
    public static Component prerequisiteMessage(Prerequisite prerequisite) {
        return switch (prerequisite) {
            case READY -> null;
            case SPAWNER_REQUIRED -> Component.translatable(
                    "rsi.goety.infuser.spawner_required");
            case CURSED_CAGE_REQUIRED -> Component.translatable(
                    "rsi.goety.dark_mender.cage_required");
        };
    }

    @Nullable
    public static Component bindingProblem(Level level, BlockPos pos) {
        Kind kind = kindOf(level, pos);
        if (kind == null) return null;
        return prerequisiteMessage(prerequisite(level, pos, kind));
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static List<ItemStack> recipeItems(BlockEntity blockEntity) {
        try {
            if (kindOf(blockEntity) != Kind.DARK_MENDER) {
                Method method = blockEntity.getClass().getMethod("getItems");
                Object value = method.invoke(blockEntity);
                if (value instanceof List<?> list) return (List<ItemStack>) list;
            }
            Field field = findField(blockEntity.getClass(), "items");
            if (field == null) return null;
            field.setAccessible(true);
            Object value = field.get(blockEntity);
            return value instanceof List<?> list ? (List<ItemStack>) list : null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-CursedInfuser] Unable to access recipe slots on {}",
                    blockEntity.getClass().getName(), exception);
            return null;
        }
    }

    public static boolean placeRecipeItem(BlockEntity blockEntity, Kind kind,
                                          ItemStack input, int cookingTime) {
        String methodName = kind == Kind.DARK_MENDER ? "placeRecipeItem" : "placeItem";
        try {
            Method method = blockEntity.getClass().getMethod(
                    methodName, ItemStack.class, int.class);
            return Boolean.TRUE.equals(method.invoke(blockEntity, input, cookingTime));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-CursedInfuser] Unable to place recipe input in {}",
                    blockEntity.getClass().getName(), exception);
            return false;
        }
    }

    public static int cookingTime(Object recipe) {
        try {
            return Math.max(1, (int) recipe.getClass().getMethod("getCookingTime").invoke(recipe));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return -1;
        }
    }

    @Nullable
    public static Boolean isGrimRecipe(Object recipe) {
        try {
            return (boolean) recipe.getClass().getMethod("isGrim").invoke(recipe);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            try {
                Field field = findField(recipe.getClass(), "grim");
                if (field == null) return null;
                field.setAccessible(true);
                return field.getBoolean(recipe);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return null;
            }
        }
    }

    static int parallelBatchSize(int totalOperations, int workerCount, int capacity) {
        if (totalOperations <= 0 || workerCount <= 0 || capacity <= 0) return 1;
        int evenShare = (int) (((long) totalOperations + workerCount - 1L) / workerCount);
        return Math.max(1, Math.min(capacity, evenShare));
    }

    private static ItemStack invokeItemGetter(@Nullable BlockEntity blockEntity) {
        if (blockEntity == null) return ItemStack.EMPTY;
        try {
            Object value = blockEntity.getClass().getMethod("getItem").invoke(blockEntity);
            return value instanceof ItemStack stack ? stack : ItemStack.EMPTY;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return ItemStack.EMPTY;
        }
    }

    @Nullable
    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
