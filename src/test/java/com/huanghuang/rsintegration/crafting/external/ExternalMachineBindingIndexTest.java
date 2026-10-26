package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.api.RSIIntegrationServices;
import com.huanghuang.rsintegration.api.RegistrationOptions;
import com.huanghuang.rsintegration.api.machine.MachineBinding;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalMachineBindingIndexTest {
    @AfterEach
    void clearIndex() {
        ExternalMachineBindingIndex.clear();
    }

    @Test
    void bindingMatchesOnlyItsDeclaredProcesses() {
        ResourceLocation cookingProcess =
                new ResourceLocation("test", "cooking");
        ResourceLocation smeltingProcess =
                new ResourceLocation("test", "smelting");
        MachineIntegration machine = mock(MachineIntegration.class);
        when(machine.bindings()).thenReturn(List.of(
                new MachineBinding(
                        List.of(new ResourceLocation(
                                "test", "cooking_pot")),
                        List.of(),
                        Set.of(cookingProcess),
                        true
                )
        ));
        ExternalMachineBindingIndex.install(List.of(
                new RSIIntegrationServices.MachineRegistration(
                        machine, RegistrationOptions.defaults())
        ));

        assertTrue(ExternalMachineBindingIndex.supportsProcess(
                "test:cooking_pot", cookingProcess));
        assertFalse(ExternalMachineBindingIndex.supportsProcess(
                "test:cooking_pot", smeltingProcess));
        assertFalse(ExternalMachineBindingIndex.supportsProcess(
                "test:furnace", cookingProcess));
        assertTrue(ExternalMachineBindingIndex.supportsBlock(
                "test:cooking_pot"));
        assertFalse(ExternalMachineBindingIndex.supportsBlock(
                "test:furnace"));
    }
}
