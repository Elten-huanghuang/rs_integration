package com.huanghuang.rsintegration.mods.goety.magic;

import com.huanghuang.rsintegration.network.binding.AltarBinding;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.reflection.probes.GoetyReflection;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/** 将 Arca 容量之外的灵魂能量转入已绑定的 RS/BD 存储网络。 */
public final class GoetySoulOverflow {
    private static final ThreadLocal<ServerPlayer> CURRENT_GAIN = new ThreadLocal<>();
    private static volatile ConfigGetter maxArcaSouls;

    private GoetySoulOverflow() {}

    public static void beginGain(Player player) {
        if (player instanceof ServerPlayer serverPlayer) CURRENT_GAIN.set(serverPlayer);
        else CURRENT_GAIN.remove();
    }

    public static void endGain() {
        CURRENT_GAIN.remove();
    }

    @Nullable
    public static ServerPlayer currentGainPlayer() {
        return CURRENT_GAIN.get();
    }

    public static int maxArcaSouls() {
        ConfigGetter getter = maxArcaSouls;
        if (getter == null) {
            getter = resolveMaxArcaSouls();
            maxArcaSouls = getter;
        }
        if (getter == null) return 100_000;
        try {
            return Math.max(1, getter.get());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 100_000;
        }
    }

    public static int route(ServerPlayer player, int overflow) {
        if (player == null || overflow <= 0 || !isArcaActive(player)) return 0;
        BlockPos pos = arcaBlock(player);
        ResourceKey<Level> dim = arcaDimension(player);
        if (pos == null || dim == null) return 0;
        // 灵魂吸收器可能每秒触发多次；先查内存绑定，缓存为空时才做 NBT 重建。
        if (AltarBindingRegistry.getBinding(player, dim, pos, AltarBinding.RS_NETWORK).isEmpty()
                && AltarBindingRegistry.getBinding(player, dim, pos, AltarBinding.BD_NETWORK).isEmpty()
                && !AltarBindingRegistry.isBound(dim, pos, player)) return 0;

        int moved = insertRs(player, dim, pos, overflow);
        if (moved >= overflow || ModList.get() == null
                || !ModList.get().isLoaded("beyonddimensions")) return moved;
        return moved + insertBd(player, dim, pos, overflow - moved);
    }

    private static int insertRs(ServerPlayer player, ResourceKey<Level> dim,
                                BlockPos pos, int amount) {
        if (ModList.get() == null || !ModList.get().isLoaded("refinedstorage")) return 0;
        return invokeBackend("com.huanghuang.rsintegration.mods.goety.magic.GoetySoulOverflowRs",
                player, dim, pos, amount);
    }

    private static int insertBd(ServerPlayer player, ResourceKey<Level> dim,
                                BlockPos pos, int amount) {
        Optional<AltarBinding> binding =
                AltarBindingRegistry.getBinding(player, dim, pos, AltarBinding.BD_NETWORK);
        if (binding.isEmpty()) return 0;
        int networkId = binding.get().data().getInt("networkId");
        try {
            Class<?> type = Class.forName(
                    "com.huanghuang.rsintegration.storage.bd.BeyondDimensionsSoulStorage");
            Method insert = type.getMethod("insert", ServerPlayer.class, int.class, int.class);
            return (int) insert.invoke(null, player, networkId, amount);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    private static int invokeBackend(String className, ServerPlayer player,
                                     ResourceKey<Level> dim, BlockPos pos, int amount) {
        try {
            Class<?> type = Class.forName(className);
            Method insert = type.getMethod("insert", ServerPlayer.class,
                    ResourceKey.class, BlockPos.class, int.class);
            return (int) insert.invoke(null, player, dim, pos, amount);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    private static boolean isArcaActive(ServerPlayer player) {
        if (GoetyReflection.seHelperClass == null) return false;
        Method method = Reflect.findMethod(GoetyReflection.seHelperClass, "getSEActive",
                new Class<?>[]{Player.class});
        if (method == null) return false;
        try {
            return Boolean.TRUE.equals(method.invoke(null, player));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Nullable
    private static BlockPos arcaBlock(ServerPlayer player) {
        if (GoetyReflection.seHelperClass == null) return null;
        Method method = Reflect.findMethod(GoetyReflection.seHelperClass, "getArcaBlock",
                new Class<?>[]{Player.class});
        if (method == null) return null;
        try {
            return (BlockPos) method.invoke(null, player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static ResourceKey<Level> arcaDimension(ServerPlayer player) {
        if (GoetyReflection.seHelperClass == null) return null;
        Method method = Reflect.findMethod(GoetyReflection.seHelperClass, "getArcaDimension",
                new Class<?>[]{Player.class});
        if (method == null) return null;
        try {
            @SuppressWarnings("unchecked")
            ResourceKey<Level> dimension = (ResourceKey<Level>) method.invoke(null, player);
            return dimension;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static ConfigGetter resolveMaxArcaSouls() {
        if (ModList.get() == null || !ModList.get().isLoaded("goety")) return null;
        try {
            Class<?> config = Class.forName("com.Polarice3.Goety.config.MainConfig");
            Field field = config.getField("MaxArcaSouls");
            Object value = field.get(null);
            Method get = value.getClass().getMethod("get");
            return new ConfigGetter(value, get);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private record ConfigGetter(Object value, Method getter) {
        int get() throws ReflectiveOperationException {
            return ((Number) getter.invoke(value)).intValue();
        }
    }
}
