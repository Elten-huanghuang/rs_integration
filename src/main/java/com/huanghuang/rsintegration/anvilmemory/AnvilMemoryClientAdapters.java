package com.huanghuang.rsintegration.anvilmemory;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AnvilMemoryClientAdapters {
    private static final List<AnvilMemoryClientAdapter> ADAPTERS = new CopyOnWriteArrayList<>();

    static {
        register(new AnvilMemoryClientAdapter() {
            public String id() { return "minecraft_anvil"; }
            public boolean supports(AbstractContainerScreen<?> screen) { return screen instanceof AnvilScreen; }
            public Bounds swapButton(AbstractContainerScreen<?> screen) {
                int left = (screen.width - 176) / 2;
                int top = (screen.height - 166) / 2;
                return new Bounds(left + 52, top + 46, 16, 16);
            }
            public Bounds memoryPanel(AbstractContainerScreen<?> screen) {
                int left = (screen.width - 176) / 2;
                int top = (screen.height - 166) / 2;
                return new Bounds(left + 180, top + 18, 22, AnvilMemoryData.LIMIT * 20 + 4);
            }
            public Bounds resultSlot(AbstractContainerScreen<?> screen) {
                int left = (screen.width - 176) / 2;
                int top = (screen.height - 166) / 2;
                return new Bounds(left + 134, top + 47, 16, 16);
            }
        });
    }

    private AnvilMemoryClientAdapters() {}

    public static void register(AnvilMemoryClientAdapter adapter) {
        if (adapter == null || adapter.id() == null || adapter.id().isBlank()) {
            throw new IllegalArgumentException("Anvil memory client adapter must have an id");
        }
        if (ADAPTERS.stream().anyMatch(existing -> existing.id().equals(adapter.id()))) {
            throw new IllegalArgumentException("Duplicate anvil memory client adapter: " + adapter.id());
        }
        ADAPTERS.add(adapter);
    }

    public static AnvilMemoryClientAdapter find(AbstractContainerScreen<?> screen) {
        return ADAPTERS.stream().filter(adapter -> adapter.supports(screen)).findFirst().orElse(null);
    }
}
