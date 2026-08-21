package com.huanghuang.rsintegration.autoeat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AutoEatPreferencesTest {
    private static final ResourceLocation APPLE = new ResourceLocation("minecraft", "apple");

    @Test
    void roundTripsModeAndSelectedFoodThroughPersistentNbt() {
        CompoundTag tag = new CompoundTag();
        AutoEatPreferences.write(tag, AutoEatMode.STACK, APPLE);

        AutoEatPreferences restored = AutoEatPreferences.read(tag);

        assertEquals(AutoEatMode.STACK, restored.mode());
        assertEquals(APPLE, restored.selectedItem());
    }

    @Test
    void malformedValuesFallBackWithoutKeepingInvalidSelection() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Mode", "UNKNOWN");
        tag.putString("SelectedItem", "not a resource location");

        AutoEatPreferences restored = AutoEatPreferences.read(tag);

        assertEquals(AutoEatMode.DIVERSITY, restored.mode());
        assertNull(restored.selectedItem());
    }
}
