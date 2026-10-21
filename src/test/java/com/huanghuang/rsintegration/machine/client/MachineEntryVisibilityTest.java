package com.huanghuang.rsintegration.machine.client;

import com.huanghuang.rsintegration.machine.client.data.BindingCache;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import com.huanghuang.rsintegration.network.binding.MachineFavoriteKey;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineEntryVisibilityTest {
    private static final ResourceLocation OVERWORLD =
            new ResourceLocation("minecraft", "overworld");

    @AfterEach
    void clearCaches() {
        MachinePreferenceClient.clear();
        BindingCache.getInstance().clear();
    }

    @Test
    void blockedGuiMachineLeavesEntryPointsButStaysInManagementList() {
        BindingInfo info = info("||block.example.gui", null);
        BindingCache.getInstance().updateBindings(List.of(info));
        assertEquals(1, MachineTabHandler.getAllMachines().size());

        MachinePreferenceClient.update(List.of(), List.of(MachineFavoriteKey.from(info)));

        assertTrue(MachineTabHandler.getAllMachines().isEmpty());
        assertEquals(List.of(info),
                MachineTabHandler.getAllBoundMachinesForManagement());
    }

    @Test
    void nonGuiMachineIsManagementOnly() {
        BindingInfo info = info("example||block.example.no_gui", null);
        BindingCache.getInstance().updateBindings(List.of(info));

        assertTrue(MachineTabHandler.getAllMachines().isEmpty());
        assertEquals(List.of(info),
                MachineTabHandler.getAllBoundMachinesForManagement());
    }

    private static BindingInfo info(String blockKey, String blockRegKey) {
        return new BindingInfo(
                "example:terminal", OVERWORLD, new BlockPos(1, 64, 1),
                blockKey, "Machine", blockRegKey, null);
    }
}
