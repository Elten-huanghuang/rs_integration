package com.huanghuang.rsintegration.magic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 重载后从已支持机器中回收本任务注入的 Source/Mana。 */
public final class MagicMachineResourceRecovery {
    public enum Status {
        UNAVAILABLE,
        NO_TARGET,
        EXTRACTED
    }

    public record Result(Status status, int amount) {
        static Result extracted(int amount) {
            return new Result(Status.EXTRACTED, Math.max(0, amount));
        }
    }

    private MagicMachineResourceRecovery() {}

    public static Result recover(MinecraftServer server, MagicResourceKind kind,
                                 ResourceLocation dimension, BlockPos position, int maximum) {
        if (server == null || kind == null || dimension == null || maximum <= 0) {
            return new Result(Status.NO_TARGET, 0);
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (level == null) return new Result(Status.NO_TARGET, 0);
        if (!level.isLoaded(position)) return new Result(Status.UNAVAILABLE, 0);
        BlockEntity machine = level.getBlockEntity(position);
        if (machine == null) return new Result(Status.NO_TARGET, 0);

        int before = current(machine, kind);
        if (before <= 0) return Result.extracted(0);
        int requested = Math.min(before, maximum);
        if (!remove(machine, kind, requested)) return new Result(Status.NO_TARGET, 0);
        int after = current(machine, kind);
        int extracted = Math.max(0, before - after);
        return Result.extracted(extracted);
    }

    private static int current(BlockEntity machine, MagicResourceKind kind) {
        String[] methods = kind == MagicResourceKind.SOURCE
                ? new String[]{"getSource"}
                : new String[]{"getCurrentMana", "getMana", "getSource"};
        for (String methodName : methods) {
            Method method = findMethod(machine.getClass(), methodName, new Class<?>[0]);
            if (method == null) continue;
            try {
                Object value = method.invoke(machine);
                if (value instanceof Number number) return Math.max(0, number.intValue());
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next supported interface.
            }
        }
        return readIntField(machine, "mana", "source");
    }

    private static boolean remove(BlockEntity machine, MagicResourceKind kind, int amount) {
        String[] methods = kind == MagicResourceKind.SOURCE
                ? new String[]{"removeSource", "addSource"}
                : new String[]{"receiveMana", "addMana", "removeMana"};
        for (String methodName : methods) {
            Method method = findMethod(machine.getClass(), methodName, new Class<?>[]{int.class});
            if (method == null) continue;
            try {
                method.invoke(machine, methodName.equals("removeSource") ? amount : -amount);
                return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next native operation.
            }
        }
        return writeIntField(machine, kind == MagicResourceKind.SOURCE ? "source" : "mana",
                Math.max(0, current(machine, kind) - amount));
    }

    private static int readIntField(Object target, String... names) {
        for (String name : names) {
            for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return Math.max(0, field.getInt(target));
                } catch (NoSuchFieldException ignored) {
                    // Continue through the hierarchy.
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static boolean writeIntField(Object target, String name, int value) {
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                field.setInt(target, value);
                return true;
            } catch (NoSuchFieldException ignored) {
                // Continue through the hierarchy.
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }
        return false;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] parameters) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Continue through the hierarchy.
            } catch (RuntimeException | LinkageError ignored) {
                return null;
            }
        }
        return null;
    }
}
