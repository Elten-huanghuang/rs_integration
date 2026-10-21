package com.huanghuang.rsintegration.machine.client;

import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import com.huanghuang.rsintegration.network.binding.MachineFavoriteKey;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachinePreferenceClientTest {
    private static final ResourceLocation OVERWORLD =
            new ResourceLocation("minecraft", "overworld");

    @AfterEach
    void clearPreferences() {
        MachinePreferenceClient.clear();
    }

    @Test
    void blockedMachineCanBeMatchedFromBindingEntryAndInfo() {
        BlockPos pos = new BlockPos(4, 64, -2);
        String blockKey = "example||block.example.machine";
        MachineFavoriteKey key = new MachineFavoriteKey(OVERWORLD, pos, blockKey);
        MachinePreferenceClient.update(List.of(), List.of(key));
        BindingInfo info = new BindingInfo(
                "example:terminal", OVERWORLD, pos, blockKey,
                "Machine", "example:machine", null);
        BindingStorage.BindingEntry entry = new BindingStorage.BindingEntry(
                OVERWORLD, pos, blockKey, "example:machine", null);

        assertTrue(MachinePreferenceClient.isBlocked(info));
        assertTrue(MachinePreferenceClient.isBlocked(entry));
        assertTrue(MachinePreferenceClient.isBlocked(OVERWORLD, pos, blockKey));
        assertFalse(MachinePreferenceClient.isBlocked(
                OVERWORLD, pos.offset(1, 0, 0), blockKey));
    }
}
