package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;
import java.util.Map;
import java.util.Optional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflective access to Curios' inventory.
 *
 * <p>Curios is an OPTIONAL dependency ({@code mandatory = false}, {@code compileOnly}).
 * Referencing {@code top.theillusivec4.curios.api.CuriosApi} directly compiles a hard
 * {@code invokestatic} into the class, and on a server without Curios that throws
 * {@link NoClassDefFoundError} — an {@link Error}, which a {@code catch (Exception)}
 * does <em>not</em> intercept. Every access therefore goes through this class, which
 * gates on {@link ModList} and reflects, so the containing classes never link
 * Curios types at all.</p>
 */
public final class CuriosAccess {

    private static boolean probed;
    private static Method getCuriosInventory;

    private CuriosAccess() {}

    /** True when Curios is installed. */
    public static boolean isPresent() {
        return ModList.get().isLoaded(ModIds.CURIOS);
    }

    /**
     * The player's curio slot handlers, or an empty list when Curios is absent
     * or its inventory cannot be resolved.
     */
    public static List<IItemHandler> handlers(LivingEntity player) {
        if (player == null || !isPresent()) return List.of();
        Method accessor = accessor();
        if (accessor == null) return List.of();
        try {
            Object lazy = accessor.invoke(null, player);
            if (lazy == null) return List.of();
            Object resolved = lazy.getClass().getMethod("resolve").invoke(lazy);
            if (!(resolved instanceof Optional<?> opt) || opt.isEmpty()) return List.of();

            Object inventory = opt.get();
            Object curios = inventory.getClass().getMethod("getCurios").invoke(inventory);
            if (!(curios instanceof Map<?, ?> map)) return List.of();

            List<IItemHandler> out = new ArrayList<>();
            for (Object stacksHandler : map.values()) {
                if (stacksHandler == null) continue;
                Object stacks = stacksHandler.getClass().getMethod("getStacks").invoke(stacksHandler);
                if (stacks instanceof IItemHandler handler) out.add(handler);
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Curios] inventory probe failed", e);
            return List.of();
        }
    }

    /** Flattened snapshot of every stack in the player's curio slots. */
    public static List<ItemStack> stacks(LivingEntity player) {
        List<IItemHandler> handlers = handlers(player);
        if (handlers.isEmpty()) return List.of();
        List<ItemStack> out = new ArrayList<>();
        for (IItemHandler handler : handlers) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                out.add(handler.getStackInSlot(slot));
            }
        }
        return out;
    }

    /**
     * Stacks in the player's <em>equipped</em> curio slots, as a single handler view.
     * Empty when Curios is absent.
     */
    public static List<ItemStack> equippedStacks(LivingEntity player) {
        if (player == null || !isPresent()) return List.of();
        Method accessor = accessor();
        if (accessor == null) return List.of();
        try {
            Object lazy = accessor.invoke(null, player);
            if (lazy == null) return List.of();
            Object resolved = lazy.getClass().getMethod("resolve").invoke(lazy);
            if (!(resolved instanceof Optional<?> opt) || opt.isEmpty()) return List.of();

            Object inventory = opt.get();
            Object equipped = inventory.getClass().getMethod("getEquippedCurios").invoke(inventory);
            if (!(equipped instanceof IItemHandler handler)) return List.of();

            List<ItemStack> out = new ArrayList<>(handler.getSlots());
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                out.add(handler.getStackInSlot(slot));
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Curios] equipped-curio probe failed", e);
            return List.of();
        }
    }

    private static Method accessor() {
        if (!probed) {
            probed = true;
            try {
                getCuriosInventory = Class.forName("top.theillusivec4.curios.api.CuriosApi")
                        .getMethod("getCuriosInventory",
                                LivingEntity.class);
            } catch (ReflectiveOperationException e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Curios] CuriosApi unavailable", e);
            }
        }
        return getCuriosInventory;
    }
}
