package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.huanghuang.rsintegration.crafting.GraphPreparationFixture.component;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncCraftChainPreparationTest {
    @Test
    void singleOperationStopsAtFirstReadyAndRetainsItsResources() throws Throwable {
        try (GraphPreparationFixture fixture = new GraphPreparationFixture(8, 1, true)) {
            Object result = fixture.prepare();
            Object prepared = component(result, "prepared");
            assertEquals("READY", component(result, "state").toString());
            assertEquals(List.of(0), fixture.probes);
            assertEquals(List.of(fixture.machines.get(0)), component(prepared, "machines"));
            assertEquals(1, component(prepared, "operationCost"));
            assertTrue((boolean) component(prepared, "parallelGroup"));
            assertEquals(2, fixture.delegates.size());
            assertEquals(1, fixture.delegates.get(0).releases);
            assertEquals(0, fixture.delegates.get(1).releases);
            assertSame(fixture.delegates.get(1), component(prepared, "delegate"));
            fixture.release(result);
            assertTrue(fixture.delegates.stream().allMatch(instance -> instance.releases == 1));
        }
    }

    @Test
    void rejectionAndRetryContinueUntilThirdMachineIsReady() throws Throwable {
        try (GraphPreparationFixture fixture = new GraphPreparationFixture(8, 1, true)) {
            fixture.resultAt = index -> switch (index) {
                case 0 -> IBatchDelegate.PreparationResult.fatal("unsupported machine");
                case 1 -> IBatchDelegate.PreparationResult.retry("needs energy");
                default -> IBatchDelegate.PreparationResult.ready();
            };
            Object result = fixture.prepare();
            assertEquals(List.of(0, 1, 2), fixture.probes);
            Object prepared = component(result, "prepared");
            assertEquals(List.of(fixture.machines.get(2)), component(prepared, "machines"));
            assertEquals(0, fixture.delegates.get(3).releases);
            fixture.release(result);
            assertTrue(fixture.delegates.stream().allMatch(instance -> instance.releases == 1));
        }
    }

    @Test
    void allFatalKeepsFirstErrorAndReleasesEveryCandidate() throws Throwable {
        try (GraphPreparationFixture fixture = new GraphPreparationFixture(8, 1, true)) {
            Component message = Component.literal("unsupported");
            fixture.resultAt = index -> IBatchDelegate.PreparationResult.fatal(
                    "unsupported " + index, message);
            Object result = fixture.prepare();
            assertEquals("FATAL", component(result, "state").toString());
            assertEquals("unsupported 0", component(result, "detail"));
            assertSame(message, component(result, "userMessage"));
            assertNull(component(result, "prepared"));
            assertEquals(8, fixture.probes.size());
            assertEquals(9, fixture.delegates.size());
            assertTrue(fixture.delegates.stream().allMatch(instance -> instance.releases == 1));
        }
    }

    @Test
    void allRetryKeepsWaitingMessageAndReleasesEveryCandidate() throws Throwable {
        try (GraphPreparationFixture fixture = new GraphPreparationFixture(8, 1, true)) {
            Component message = Component.literal("waiting for energy");
            fixture.resultAt = index -> IBatchDelegate.PreparationResult.waiting(
                    "needs energy " + index, message);
            Object result = fixture.prepare();
            assertEquals("RETRY", component(result, "state").toString());
            assertEquals("needs energy 0", component(result, "detail"));
            assertSame(message, component(result, "userMessage"));
            assertEquals(8, fixture.probes.size());
            assertTrue(fixture.delegates.stream().allMatch(instance -> instance.releases == 1));
        }
    }

    @Test
    void protectedAndUnloadedMachinesAreSkippedBeforePreparation() throws Throwable {
        try (GraphPreparationFixture fixture = new GraphPreparationFixture(8, 1, true)) {
            fixture.denied.add(0);
            fixture.unavailable.add(1);
            Object result = fixture.prepare();
            assertEquals(List.of(2), fixture.probes);
            assertEquals(List.of(fixture.machines.get(2)),
                    component(component(result, "prepared"), "machines"));
            fixture.release(result);
            assertTrue(fixture.delegates.stream().allMatch(instance -> instance.releases == 1));
        }
    }

    @Test
    void preparationExceptionReleasesCandidateAndContinues() throws Throwable {
        try (GraphPreparationFixture fixture = new GraphPreparationFixture(8, 1, true)) {
            fixture.resultAt = index -> {
                if (index == 0) throw new IllegalStateException("machine temporarily unavailable");
                return IBatchDelegate.PreparationResult.ready();
            };
            Object result = fixture.prepare();
            assertEquals(List.of(0, 1), fixture.probes);
            assertEquals(1, fixture.delegates.get(1).releases);
            fixture.release(result);
            assertTrue(fixture.delegates.stream().allMatch(instance -> instance.releases == 1));
        }
    }

    @Test
    void multipleOperationsStillProbeAllMachinesAndKeepParallelCapacity() throws Throwable {
        try (GraphPreparationFixture fixture = new GraphPreparationFixture(8, 3, true)) {
            Object result = fixture.prepare();
            Object prepared = component(result, "prepared");
            assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), fixture.probes);
            assertEquals(fixture.machines, component(prepared, "machines"));
            assertEquals(3, component(prepared, "operationCost"));
            assertTrue((boolean) component(prepared, "parallelGroup"));
            assertEquals(0, fixture.delegates.get(1).releases);
            assertEquals(9, fixture.delegates.size());
            fixture.release(result);
            assertTrue(fixture.delegates.stream().allMatch(instance -> instance.releases == 1));
        }
    }

    @Test
    void machinePriorityStillSelectsFastestFurnace() throws Throwable {
        try (GraphPreparationFixture fixture = new GraphPreparationFixture(3, 1, true)) {
            String[] keys = {"ironfurnaces_iron_furnace", "ironfurnaces_diamond_furnace",
                    "ironfurnaces_rainbow_furnace"};
            for (int i = 0; i < keys.length; i++) {
                BoundMachine machine = fixture.machines.get(i);
                fixture.machines.set(i, new BoundMachine(machine.dim(), machine.pos(),
                        machine.type(), keys[i]));
            }
            Object result = fixture.prepare();
            assertEquals(List.of(2), fixture.probes);
            fixture.release(result);
        }
    }

    @Test
    void explicitPreferenceStillPrecedesBindingOrder() throws Throwable {
        try (GraphPreparationFixture fixture = new GraphPreparationFixture(8, 1, true)) {
            fixture.chain.setMachineSelection(GraphPreparationFixture.RECIPE,
                    MachineSelectionMode.PREFERRED, Level.OVERWORLD.location(), new BlockPos(5, 64, 0));
            Object result = fixture.prepare();
            assertEquals(List.of(5), fixture.probes);
            fixture.release(result);
        }
    }

    @Test
    void preferredMachineRetryStillFallsBackToAnotherReadyMachine() throws Throwable {
        try (GraphPreparationFixture fixture = new GraphPreparationFixture(8, 1, true)) {
            fixture.chain.setMachineSelection(GraphPreparationFixture.RECIPE,
                    MachineSelectionMode.PREFERRED, Level.OVERWORLD.location(), new BlockPos(5, 64, 0));
            fixture.resultAt = index -> index == 5
                    ? IBatchDelegate.PreparationResult.retry("preferred altar needs souls")
                    : IBatchDelegate.PreparationResult.ready();
            Object result = fixture.prepare();
            assertEquals("READY", component(result, "state").toString());
            assertEquals(List.of(5, 0), fixture.probes);
            assertEquals(List.of(fixture.machines.get(0)),
                    component(component(result, "prepared"), "machines"));
            fixture.release(result);
            assertTrue(fixture.delegates.stream().allMatch(instance -> instance.releases == 1));
        }
    }

    @Test
    void exclusiveSelectionDoesNotFallBackWhenSelectedMachineRejects() throws Throwable {
        try (GraphPreparationFixture fixture = new GraphPreparationFixture(8, 1, true)) {
            fixture.chain.setMachineSelection(GraphPreparationFixture.RECIPE,
                    MachineSelectionMode.EXCLUSIVE, Level.OVERWORLD.location(), new BlockPos(5, 64, 0));
            fixture.resultAt = index -> index == 5
                    ? IBatchDelegate.PreparationResult.retry("selected machine needs energy")
                    : IBatchDelegate.PreparationResult.ready();
            Object result = fixture.prepare();
            assertEquals("RETRY", component(result, "state").toString());
            assertEquals(List.of(5), fixture.probes);
            assertEquals("selected machine needs energy", component(result, "detail"));
            assertTrue(fixture.delegates.stream().allMatch(instance -> instance.releases == 1));
        }
    }
}
