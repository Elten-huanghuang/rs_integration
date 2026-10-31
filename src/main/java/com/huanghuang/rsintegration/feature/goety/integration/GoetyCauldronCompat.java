package com.huanghuang.rsintegration.feature.goety.integration;

import com.Polarice3.Goety.common.blocks.entities.BrewCauldronBlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** 可选的新版本坩埚 API 统一通过此适配层访问。 */
public final class GoetyCauldronCompat {
    private static final Field IS_CRAFTING = optionalField("isCrafting");
    private static final Field RECIPE_ID = optionalField("recipeId");
    private static final Method BREW_FOR_PLAYER = optionalMethod("brew", Player.class);
    private static final Class<?> SOUL_CANDLE_API = optionalClass(
            "com.Polarice3.Goety.api.blocks.entities.ISoulCandle");
    private static final Class<?> SOUL_CANDLE_BLOCK_ENTITY = optionalClass(
            "com.Polarice3.Goety.common.blocks.entities.SoulCandlestickBlockEntity");
    private static final Method GET_SOULS = optionalMethod(SOUL_CANDLE_API, "getSouls");
    private static final Method GET_SOULS_FROM_BLOCK_ENTITY = optionalMethod(
            SOUL_CANDLE_BLOCK_ENTITY, "getSouls");

    private GoetyCauldronCompat() {}

    // 优先读取新字段，并用状态枚举兼容没有该字段的旧版本。
    public static boolean isCrafting(BrewCauldronBlockEntity cauldron) {
        return booleanField(IS_CRAFTING, cauldron)
                || hasMode(cauldron, "CRAFTING");
    }

    public static boolean isBusy(BrewCauldronBlockEntity cauldron) {
        return cauldron.isBrewing || isCrafting(cauldron) || hasMode(cauldron, "BREWING");
    }

    public static boolean isCrafted(BrewCauldronBlockEntity cauldron) {
        return hasMode(cauldron, "CRAFTED");
    }

    public static ResourceLocation recipeId(BrewCauldronBlockEntity cauldron) {
        if (RECIPE_ID == null) return null;
        try {
            Object value = RECIPE_ID.get(cauldron);
            return value instanceof ResourceLocation id ? id : null;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("无法读取 Goety 坩埚配方 ID", exception);
        }
    }

    public static void brew(BrewCauldronBlockEntity cauldron, Player player) {
        if (BREW_FOR_PLAYER == null) {
            cauldron.brew();
            return;
        }
        try {
            BREW_FOR_PLAYER.invoke(cauldron, player);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("无法调用 Goety 玩家版 brew 方法", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Goety brew 调用失败", cause);
        }
    }

    public static int soulCount(BlockEntity entity) {
        if (entity == null) return -1;
        boolean isSoulCandle = SOUL_CANDLE_API != null && SOUL_CANDLE_API.isInstance(entity);
        if (!isSoulCandle && (SOUL_CANDLE_BLOCK_ENTITY == null
                || !SOUL_CANDLE_BLOCK_ENTITY.isInstance(entity))) return -1;
        Method method = isSoulCandle ? GET_SOULS : GET_SOULS_FROM_BLOCK_ENTITY;
        if (method == null) return -1;
        try {
            Object value = method.invoke(entity);
            return value instanceof Number number ? number.intValue() : -1;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("无法读取 Goety 灵魂烛台状态", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            return -1;
        }
    }

    private static boolean hasMode(BrewCauldronBlockEntity cauldron, String name) {
        return cauldron.mode != null && name.equals(cauldron.mode.name());
    }

    private static boolean booleanField(Field field, BrewCauldronBlockEntity cauldron) {
        if (field == null) return false;
        try {
            return field.getBoolean(cauldron);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("无法读取 Goety 坩埚状态", exception);
        }
    }

    private static Field optionalField(String name) {
        try {
            return BrewCauldronBlockEntity.class.getField(name);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private static Method optionalMethod(String name, Class<?>... parameterTypes) {
        return optionalMethod(BrewCauldronBlockEntity.class, name, parameterTypes);
    }

    private static Method optionalMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        if (type == null) return null;
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Class<?> optionalClass(String name) {
        try {
            return Class.forName(name, false, GoetyCauldronCompat.class.getClassLoader());
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }
}
