package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.reflection.probes.GoetyReflection;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;

/** Goety 玩家能力灵魂的统一反射边界。 */
final class GoetyPlayerSoulAccess {
    private GoetyPlayerSoulAccess() {}

    static int souls(Player player) {
        if (player == null || GoetyReflection.seHelperClass == null) return 0;
        if (!staticBoolean("getSEActive", player)) return 0;
        return Math.max(0, staticInt("getSESouls", player));
    }

    static int consume(Player player, int requested) {
        if (player == null || requested <= 0) return 0;
        if (!staticBoolean("decreaseSESouls", player, requested)) return 0;
        staticVoid("sendSEUpdatePacket", player);
        return requested;
    }

    private static int staticInt(String method, Object... args) {
        Method resolved = Reflect.findMethod(
                GoetyReflection.seHelperClass, method, parameterTypes(args));
        if (resolved == null) return -1;
        try {
            Object value = resolved.invoke(null, args);
            return value instanceof Number number ? number.intValue() : -1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return -1;
        }
    }

    private static boolean staticBoolean(String method, Object... args) {
        Method resolved = Reflect.findMethod(
                GoetyReflection.seHelperClass, method, parameterTypes(args));
        if (resolved == null) return false;
        try {
            return Boolean.TRUE.equals(resolved.invoke(null, args));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static void staticVoid(String method, Object... args) {
        Method resolved = Reflect.findMethod(
                GoetyReflection.seHelperClass, method, parameterTypes(args));
        if (resolved == null) return;
        try {
            resolved.invoke(null, args);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Optional API difference.
        }
    }

    private static Class<?>[] parameterTypes(Object... args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] instanceof Player ? Player.class
                    : args[i] instanceof Integer ? int.class
                    : args[i] == null ? Object.class : args[i].getClass();
        }
        return types;
    }
}
