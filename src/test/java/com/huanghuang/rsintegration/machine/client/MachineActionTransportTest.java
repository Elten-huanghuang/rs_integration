package com.huanghuang.rsintegration.machine.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MachineActionTransportTest {
    @Test
    void selectsProtocolFromLoadedBackendAndBindingSource() {
        assertEquals(MachineActionTransport.Backend.BEYOND_DIMENSIONS,
                MachineActionTransport.resolveBackend(
                        "beyonddimensions:net_terminal_item", true));
        assertEquals(MachineActionTransport.Backend.REFINED_STORAGE,
                MachineActionTransport.resolveBackend(
                        "beyonddimensions:net_terminal_item", false));
        assertEquals(MachineActionTransport.Backend.REFINED_STORAGE,
                MachineActionTransport.resolveBackend(
                        "refinedstorage:grid", true));
        assertEquals(MachineActionTransport.Backend.REFINED_STORAGE,
                MachineActionTransport.resolveBackend(null, true));
    }
}
