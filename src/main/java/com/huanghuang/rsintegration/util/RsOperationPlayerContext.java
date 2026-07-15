package com.huanghuang.rsintegration.util;

import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;

/** Server-thread player ownership propagated through nested backpack/RS operations. */
public final class RsOperationPlayerContext {

    private static final ThreadLocal<Deque<ServerPlayer>> PLAYERS = new ThreadLocal<>();

    private RsOperationPlayerContext() {}

    public static Scope push(ServerPlayer player) {
        Deque<ServerPlayer> players = PLAYERS.get();
        if (players == null) {
            players = new ArrayDeque<>();
            PLAYERS.set(players);
        }
        players.push(player);
        return new Scope(player);
    }

    @Nullable
    public static ServerPlayer current() {
        Deque<ServerPlayer> players = PLAYERS.get();
        return players == null ? null : players.peek();
    }

    public static final class Scope implements AutoCloseable {
        private final ServerPlayer player;
        private boolean closed;

        private Scope(ServerPlayer player) {
            this.player = player;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            Deque<ServerPlayer> players = PLAYERS.get();
            if (players == null) return;
            if (players.peek() == player) {
                players.pop();
            } else {
                players.clear();
            }
            if (players.isEmpty()) PLAYERS.remove();
        }
    }
}
