package com.huanghuang.rsintegration.anvilmemory;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Server-side description of an anvil-like menu. Mods may register additional adapters. */
public interface AnvilMemoryAdapter {
    String id();
    boolean supports(AbstractContainerMenu menu);
    int primarySlot();
    int materialSlot();

    default int resultSlot() {
        return 2;
    }

    default ItemStack rememberedMaterial(AbstractContainerMenu menu) {
        return menu.getSlot(materialSlot()).getItem();
    }
}
