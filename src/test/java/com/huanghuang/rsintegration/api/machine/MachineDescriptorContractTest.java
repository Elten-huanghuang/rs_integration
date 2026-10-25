package com.huanghuang.rsintegration.api.machine;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineDescriptorContractTest {
    private static final ResourceLocation MACHINE_ID =
            new ResourceLocation("test", "machine");
    private static final ResourceLocation PROCESS =
            new ResourceLocation("test", "process");

    @Test
    void descriptorRequiresAtLeastOneAcceptedProcess() {
        assertThrows(IllegalArgumentException.class, () -> descriptor(
                Set.of(), List.of(), List.of(), List.of(), MachineExecutionMode.TICKED_AND_BUSY));
    }

    @Test
    void descriptorRejectsDuplicatePortIdsAndPhysicalBindings() {
        MachineItemPort input = itemPort("input", 0, InputOutputAccess.INPUT, "ingredients");
        MachineItemPort duplicateId = itemPort(
                "input", 1, InputOutputAccess.INPUT, "ingredients");
        MachineItemPort duplicatePhysical = itemPort(
                "other", 0, InputOutputAccess.INPUT, "ingredients");

        assertThrows(IllegalArgumentException.class, () -> descriptor(
                Set.of(PROCESS), List.of(input, duplicateId), List.of(), List.of(),
                MachineExecutionMode.TICKED_AND_BUSY));
        assertThrows(IllegalArgumentException.class, () -> descriptor(
                Set.of(PROCESS), List.of(input, duplicatePhysical), List.of(), List.of(),
                MachineExecutionMode.TICKED_AND_BUSY));
    }

    @Test
    void customPortsRequireCustomExecutionMode() {
        MachineItemPort custom = new MachineItemPort(
                "custom", 0, null, InputOutputAccess.INPUT,
                ItemPortAccess.CUSTOM, "ingredients", null, 64);

        assertThrows(IllegalArgumentException.class, () -> descriptor(
                Set.of(PROCESS), List.of(custom), List.of(), List.of(),
                MachineExecutionMode.TICKED_AND_BUSY));
        assertTrue(descriptor(
                Set.of(PROCESS), List.of(custom), List.of(), List.of(),
                MachineExecutionMode.CUSTOM).itemPorts().contains(custom));
    }

    @Test
    void descriptorFingerprintIgnoresSetAndListOrdering() {
        MachineItemPort first = itemPort(
                "input", 0, InputOutputAccess.INPUT, "ingredients");
        MachineItemPort second = new MachineItemPort(
                "output", 1, Direction.UP, InputOutputAccess.OUTPUT,
                ItemPortAccess.ITEM_HANDLER, null, "output", 64);
        MachineDescriptor forward = descriptor(
                Set.of(PROCESS, new ResourceLocation("test", "other")),
                List.of(first, second), List.of(), List.of(),
                MachineExecutionMode.TICKED_AND_BUSY);
        MachineDescriptor reversed = descriptor(
                Set.of(new ResourceLocation("test", "other"), PROCESS),
                List.of(second, first), List.of(), List.of(),
                MachineExecutionMode.TICKED_AND_BUSY);

        assertEquals(forward.descriptorFingerprint(), reversed.descriptorFingerprint());
        MachineDescriptor changed = descriptor(
                Set.of(PROCESS), List.of(first, second), List.of(), List.of(),
                MachineExecutionMode.TICKED_AND_BUSY);
        assertNotEquals(forward.descriptorFingerprint(), changed.descriptorFingerprint());
    }

    @Test
    void stateValidatesBusyAndProgressInvariants() {
        MachineState idle = new MachineState(
                true, true, false, 0, 0, null, null, 1L, null);

        assertTrue(idle.valid());
        assertFalse(idle.busy());
        assertThrows(IllegalArgumentException.class, () -> new MachineState(
                true, true, true, 0, 10, null, null, 1L, null));
        assertThrows(IllegalArgumentException.class, () -> new MachineState(
                false, false, true, 0, 0, PROCESS, PROCESS, 1L, null));
        assertFalse(MachineState.invalid().loaded());
    }

    @Test
    void supportScopeAndDescriptorResultDefensivelyCopyCollections() {
        List<BlockPos> offsets = new ArrayList<>();
        offsets.add(BlockPos.ZERO);
        SupportScope scope = new SupportScope(offsets, List.of(), true);
        offsets.add(new BlockPos(1, 0, 0));

        assertEquals(List.of(BlockPos.ZERO), scope.supportOffsets());
        assertThrows(UnsupportedOperationException.class,
                () -> scope.supportOffsets().add(new BlockPos(2, 0, 0)));

        IntegrationDiagnostic diagnostic = new IntegrationDiagnostic(
                IntegrationDiagnosticCode.MACHINE_DESCRIPTOR_INVALID,
                IntegrationSeverity.ERROR,
                MACHINE_ID,
                Map.of());
        MachineDescriptorResult rejected =
                MachineDescriptorResult.rejected(diagnostic);
        assertEquals(List.of(diagnostic), rejected.diagnostics());
        assertThrows(IllegalArgumentException.class,
                () -> new MachineDescriptorResult(null, List.of()));
    }

    private static MachineDescriptor descriptor(
            Set<ResourceLocation> processes,
            List<MachineItemPort> itemPorts,
            List<MachineFluidPort> fluidPorts,
            List<MachineEnergyPort> energyPorts,
            MachineExecutionMode executionMode
    ) {
        return new MachineDescriptor(
                MACHINE_ID,
                1L,
                new HashSet<>(processes),
                itemPorts,
                fluidPorts,
                energyPorts,
                executionMode,
                true,
                ConcurrencyCapabilities.machineSlot(),
                new SupportScope(List.of(BlockPos.ZERO), List.of(), true),
                false
        );
    }

    private static MachineItemPort itemPort(
            String portId, int slot, InputOutputAccess direction, String group) {
        return new MachineItemPort(
                portId, slot, null, direction, ItemPortAccess.ITEM_HANDLER,
                direction == InputOutputAccess.INPUT ? group : null,
                direction == InputOutputAccess.OUTPUT ? group : null,
                64);
    }
}
