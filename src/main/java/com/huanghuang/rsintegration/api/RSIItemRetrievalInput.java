package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Client-only interception of recognized native retrieval gestures. */
public final class RSIItemRetrievalInput {
    private record Registration(ResourceLocation id, int priority, Handler handler) {}

    private static final Map<ResourceLocation, Registration> HANDLERS = new HashMap<>();
    private static volatile List<Registration> orderedHandlers = List.of();

    private RSIItemRetrievalInput() {}

    /** Register during client setup. Higher priorities run first, with ID order breaking ties. */
    public static synchronized boolean register(ResourceLocation id, int priority, Handler handler) {
        var entry = new Registration(Objects.requireNonNull(id), priority, Objects.requireNonNull(handler));
        if (HANDLERS.putIfAbsent(id, entry) != null) return false;
        orderedHandlers = HANDLERS.values().stream()
                .sorted(Comparator.comparingInt(Registration::priority).reversed().thenComparing(Registration::id))
                .toList();
        return true;
    }

    public enum Decision { PASS, HANDLED }

    @FunctionalInterface
    public interface Handler {
        /** PASS leaves the gesture untouched. HANDLED consumes the gesture and owns request dispatch. */
        Decision onInput(Context context);
    }

    public sealed interface Origin permits Jei, WorldPick {}
    public record Jei(Screen screen) implements Origin {}
    public record WorldPick(BlockHitResult hit) implements Origin {}
    public record Modifiers(boolean shift, boolean control, boolean alt) {
        public static Modifiers current() {
            return new Modifiers(Screen.hasShiftDown(), Screen.hasControlDown(), Screen.hasAltDown());
        }
    }

    public record Context(Origin origin, ItemStack target, InputConstants.Key input, Modifiers modifiers) {
        public Context {
            Objects.requireNonNull(origin);
            target = Objects.requireNonNull(target).copyWithCount(1);
            Objects.requireNonNull(input);
            Objects.requireNonNull(modifiers);
            if (target.isEmpty()) throw new IllegalArgumentException("empty retrieval target");
        }

        @Override
        public ItemStack target() { return target.copy(); }
    }

    /** Invoked by RSI on the client thread before its native request. */
    public static boolean dispatch(Context context) {
        for (Registration entry : orderedHandlers) {
            try {
                if (Objects.requireNonNull(entry.handler().onInput(context)) == Decision.HANDLED) return true;
            } catch (RuntimeException | LinkageError failure) {
                // A handler may have sent its request before failing.
                RSIntegrationMod.LOGGER.warn("[RSI-Retrieval] Client handler {} failed", entry.id(), failure);
                return true;
            }
        }
        return false;
    }
}
