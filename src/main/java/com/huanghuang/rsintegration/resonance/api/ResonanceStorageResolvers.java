package com.huanghuang.rsintegration.resonance.api;

import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Registry for optional RS/BD resonance-storage adapters. */
public final class ResonanceStorageResolvers {

    private static final CopyOnWriteArrayList<Resolver> RESOLVERS = new CopyOnWriteArrayList<>();

    private ResonanceStorageResolvers() {}

    public static void register(Resolver resolver) {
        if (resolver != null && !RESOLVERS.contains(resolver)) RESOLVERS.addIfAbsent(resolver);
    }

    public static List<ResonanceStorageView> resolveAll(ServerPlayer player) {
        if (player == null || RESOLVERS.isEmpty()) return List.of();
        IdentityHashMap<ResonanceStorageView, Boolean> seen = new IdentityHashMap<>();
        List<ResonanceStorageView> result = new ArrayList<>();
        for (Resolver resolver : RESOLVERS) {
            try {
                ResonanceStorageView view = resolver.resolve(player);
                if (view != null && seen.put(view, Boolean.TRUE) == null) result.add(view);
            } catch (RuntimeException | LinkageError ignored) {
                // An optional backend must not prevent other resonance providers
                // from serving the player.
            }
        }
        return List.copyOf(result);
    }

    @FunctionalInterface
    public interface Resolver {
        @Nullable ResonanceStorageView resolve(ServerPlayer player);
    }
}
