package com.huanghuang.rsintegration.crafting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Additive, exactly-once terminal listener collection. */
final class TerminalListeners {

    private final List<Runnable> listeners = new ArrayList<>();
    private final Consumer<RuntimeException> errorHandler;
    private final Consumer<Runnable> dispatcher;
    private boolean fired;

    TerminalListeners(Consumer<RuntimeException> errorHandler) {
        this(errorHandler, Runnable::run);
    }

    TerminalListeners(Consumer<RuntimeException> errorHandler, Consumer<Runnable> dispatcher) {
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    void add(Runnable listener) {
        if (listener == null) return;
        if (fired) {
            dispatcher.accept(() -> run(listener));
            return;
        }
        listeners.add(listener);
    }

    void fireOnce() {
        if (fired) return;
        fired = true;
        List<Runnable> pending = List.copyOf(listeners);
        listeners.clear();
        for (Runnable listener : pending) dispatcher.accept(() -> run(listener));
    }

    private void run(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException e) {
            errorHandler.accept(e);
        }
    }
}
