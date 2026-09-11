package com.huanghuang.rsintegration.autoeat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoEatPreferencesTest {
    private static final ResourceLocation APPLE = new ResourceLocation("minecraft", "apple");

    @Test
    void roundTripsOrderedSelectedFoodsThroughPersistentNbt() {
        CompoundTag tag = new CompoundTag();
        ResourceLocation bread = new ResourceLocation("minecraft", "bread");
        AutoEatPreferences.write(tag, AutoEatMode.STACK, List.of(APPLE, bread, APPLE));

        AutoEatPreferences restored = AutoEatPreferences.read(tag);

        assertEquals(AutoEatMode.STACK, restored.mode());
        assertEquals(List.of(APPLE, bread), restored.selectedItems());
    }

    @Test
    void malformedValuesFallBackWithoutKeepingInvalidSelection() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Mode", "UNKNOWN");
        tag.putString("SelectedItem", "not a resource location");

        AutoEatPreferences restored = AutoEatPreferences.read(tag);

        assertEquals(AutoEatMode.DIVERSITY, restored.mode());
        assertEquals(List.of(), restored.selectedItems());
    }

    @Test
    void readsLegacySingleSelection() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Mode", "STACK");
        tag.putString("SelectedItem", APPLE.toString());

        AutoEatPreferences restored = AutoEatPreferences.read(tag);

        assertEquals(AutoEatMode.STACK, restored.mode());
        assertEquals(List.of(APPLE), restored.selectedItems());
    }

    @Test
    void boundsAndDeduplicatesStoredSelections() {
        CompoundTag tag = new CompoundTag();
        ListTag selected = new ListTag();
        for (int i = 0; i < AutoEatPreferences.MAX_SELECTED_ITEMS + 5; i++) {
            selected.add(StringTag.valueOf("test:food_" + i));
        }
        tag.put("SelectedItems", selected);

        AutoEatPreferences restored = AutoEatPreferences.read(tag);

        assertEquals(AutoEatPreferences.MAX_SELECTED_ITEMS, restored.selectedItems().size());
    }
}
