package com.huanghuang.rsintegration.anvilmemory;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraftforge.registries.ForgeRegistries;

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
        register(menuAdapter("goety_dark_anvil", "goety:dark_anvil"));
        register(menuAdapter("irons_spellbooks_arcane_anvil", "irons_spellbooks:arcane_anvil_menu"));
    }

    private AnvilMemoryClientAdapters() {}

    private static AnvilMemoryClientAdapter menuAdapter(String id, String menuId) {
        return new AnvilMemoryClientAdapter() {
            public String id() { return id; }
            public boolean supports(AbstractContainerScreen<?> screen) {
                return menuId.equals(menuTypeId(screen.getMenu()));
            }
            public Bounds swapButton(AbstractContainerScreen<?> screen) {
                return standardBounds(screen, 52, 46, 16, 16);
            }
            public Bounds memoryPanel(AbstractContainerScreen<?> screen) {
                return standardBounds(screen, 180, 18, 22, AnvilMemoryData.LIMIT * 20 + 4);
            }
            public Bounds resultSlot(AbstractContainerScreen<?> screen) {
                return standardBounds(screen, 134, 47, 16, 16);
            }
        };
    }

    private static String menuTypeId(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        try {
            var key = ForgeRegistries.MENU_TYPES.getKey(menu.getType());
            return key == null ? null : key.toString();
        } catch (UnsupportedOperationException ignored) {
            return null;
        }
    }

    private static AnvilMemoryClientAdapter.Bounds standardBounds(AbstractContainerScreen<?> screen,
                                                                   int x, int y, int width, int height) {
        int left = (screen.width - 176) / 2;
        int top = (screen.height - 166) / 2;
        return new AnvilMemoryClientAdapter.Bounds(left + x, top + y, width, height);
    }

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
