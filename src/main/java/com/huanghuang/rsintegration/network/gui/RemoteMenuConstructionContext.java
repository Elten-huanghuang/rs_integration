package com.huanghuang.rsintegration.network.gui;

import com.huanghuang.rsintegration.util.ThreadLocalStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Supplier;

/** Supplies the target level while a remote machine menu is being constructed. */
public final class RemoteMenuConstructionContext {
    private record Target(ServerPlayer player, ServerLevel level) {}

    private static final ThreadLocalStack<Target> TARGETS = new ThreadLocalStack<>();

    private RemoteMenuConstructionContext() {}

    public static <T> T withTarget(ServerPlayer player, ServerLevel level, Supplier<T> action) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(level);
        Objects.requireNonNull(action);
        TARGETS.push(new Target(player, level));
        try {
            return action.get();
        } finally {
            TARGETS.pop();
        }
    }

    /** Returns a target only for the player whose menu is currently being opened. */
    @Nullable
    public static ServerLevel targetLevel(Player player) {
        Target target = TARGETS.peek();
        return target != null && target.player() == player ? target.level() : null;
    }
}
