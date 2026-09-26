package com.huanghuang.rsintegration.anvilmemory;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AnvilMemoryAdapters {
    private static final List<AnvilMemoryAdapter> ADAPTERS = new CopyOnWriteArrayList<>();

    static {
        register(menuAdapter("goety_dark_anvil", "goety:dark_anvil"));
        register(menuAdapter("irons_spellbooks_arcane_anvil", "irons_spellbooks:arcane_anvil_menu"));
        register(new AnvilMemoryAdapter() {
            public String id() { return "minecraft_anvil"; }
            public boolean supports(AbstractContainerMenu menu) { return menu instanceof AnvilMenu; }
            public int primarySlot() { return 0; }
            public int materialSlot() { return 1; }
        });
    }

    private AnvilMemoryAdapters() {}

    private static AnvilMemoryAdapter menuAdapter(String id, String menuId) {
        return new AnvilMemoryAdapter() {
            public String id() { return id; }
            public boolean supports(AbstractContainerMenu menu) {
                return menuId.equals(menuTypeId(menu));
            }
            public int primarySlot() { return 0; }
            public int materialSlot() { return 1; }
        };
    }

    private static String menuTypeId(AbstractContainerMenu menu) {
        try {
            var key = ForgeRegistries.MENU_TYPES.getKey(menu.getType());
            return key == null ? null : key.toString();
        } catch (UnsupportedOperationException ignored) {
            return null;
        }
    }

    public static void register(AnvilMemoryAdapter adapter) {
        if (adapter == null || adapter.id() == null || adapter.id().isBlank()) {
            throw new IllegalArgumentException("Anvil memory adapter must have an id");
        }
        if (ADAPTERS.stream().anyMatch(existing -> existing.id().equals(adapter.id()))) {
            throw new IllegalArgumentException("Duplicate anvil memory adapter: " + adapter.id());
        }
        ADAPTERS.add(adapter);
    }

    public static AnvilMemoryAdapter find(AbstractContainerMenu menu) {
        return ADAPTERS.stream().filter(adapter -> adapter.supports(menu)).findFirst().orElse(null);
    }
}
