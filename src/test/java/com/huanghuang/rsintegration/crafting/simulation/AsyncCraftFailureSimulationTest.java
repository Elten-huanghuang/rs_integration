package com.huanghuang.rsintegration.crafting.simulation;

import com.huanghuang.rsintegration.crafting.CraftOutputInterceptor;
import com.huanghuang.rsintegration.crafting.OperationExecutionKernel;
import com.huanghuang.rsintegration.crafting.OperationResourceCoordinator;
import com.huanghuang.rsintegration.crafting.TerminationCoordinator;
import com.huanghuang.rsintegration.crafting.TerminationService;
import com.huanghuang.rsintegration.crafting.graph.AllocationId;
import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DagScheduler;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialBroker;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeAdmissionCoordinator;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OperationBudget;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic end-to-end fault simulations for the asynchronous craft primitives. */
class AsyncCraftFailureSimulationTest extends BootstrapTest {

    @Test
    void cancellationBeforeStartReleasesReservationAndRollsBackStartBudget() {
        Simulation simulation = new Simulation(Scenario.normal(4));

        simulation.cancel("cancelled before delegate start");

        simulation.assertTerminal(Outcome.CANCELLED);
        assertEquals(List.of(FakeDelegate.State.STARTING, FakeDelegate.State.FAILED),
                simulation.delegate.history());
        assertEquals(0, simulation.craftBudget.starts());
        assertEquals(1, simulation.report.preStartOperations());
    }

    @Test
    void failureAfterMaterialCommitButBeforeMachineWorkingRefundsOnce() {
        Simulation simulation = new Simulation(Scenario.failingAtStart());

        simulation.runner.runNextTick();

        simulation.assertTerminal(Outcome.FAILED);
        assertEquals(List.of(FakeDelegate.State.STARTING, FakeDelegate.State.FAILED),
                simulation.delegate.history());
        assertEquals(1, simulation.refundCount);
        assertEquals(1, simulation.report.inFlightOperations());
    }

    @Test
    void destroyingMachineWhileWorkingStopsTheNodeAndRefundsOnce() {
        Simulation simulation = new Simulation(Scenario.normal(10));
        simulation.runner.runNextTick();
        assertEquals(FakeDelegate.State.WORKING, simulation.delegate.state());

        simulation.delegate.destroyMachine();
        simulation.runner.runNextTick();

        simulation.assertTerminal(Outcome.FAILED);
        assertEquals(List.of(FakeDelegate.State.STARTING, FakeDelegate.State.WORKING,
                FakeDelegate.State.FAILED), simulation.delegate.history());
        assertEquals(1, simulation.refundCount);
    }

    @Test
    void playerGoingOfflineStopsWorkingWithoutAnotherDelegateTick() {
        Simulation simulation = new Simulation(Scenario.normal(10));
        simulation.runner.runNextTick();
        int workBeforeOffline = simulation.delegate.workTicks();

        simulation.playerOnline = false;
        simulation.runner.runNextTick();

        simulation.assertTerminal(Outcome.OFFLINE);
        assertEquals(workBeforeOffline, simulation.delegate.workTicks());
        assertEquals(TerminationCoordinator.Cause.OFFLINE, simulation.report.cause());
    }

    @Test
    void timeoutUsesVirtualTicksAndNeverWaitsOnWallClock() {
        Simulation simulation = new Simulation(Scenario.normal(100).withDeadline(3));

        simulation.runner.runTicks(3);

        simulation.assertTerminal(Outcome.TIMED_OUT);
        assertEquals(1, simulation.delegate.workTicks());
        assertEquals("timeout", simulation.report.reason());
    }

    @Test
    void occupiedOutputSlotRejectsStartAndRefundsCommittedMaterial() {
        Simulation simulation = new Simulation(Scenario.withOccupiedOutput());

        simulation.runner.runNextTick();

        simulation.assertTerminal(Outcome.FAILED);
        assertEquals(List.of(FakeDelegate.State.STARTING, FakeDelegate.State.FAILED),
                simulation.delegate.history());
        assertEquals(1, simulation.refundCount);
    }

    @Test
    void completionAndCancellationInTheSameTickPublishOnlyTheFirstTerminal() {
        Simulation completionFirst = new Simulation(Scenario.normal(100));
        completionFirst.runner.runNextTick();
        long raceTick = completionFirst.runner.now() + 1;
        completionFirst.runner.scheduleAt(raceTick, completionFirst.delegate::completeNow);
        completionFirst.runner.scheduleAt(raceTick, () -> completionFirst.cancel("same tick"));

        completionFirst.runner.runNextTick();

        completionFirst.assertTerminal(Outcome.SUCCEEDED);
        assertEquals(MaterialBroker.ReservationState.SETTLED, completionFirst.reservationState());

        Simulation cancellationFirst = new Simulation(Scenario.normal(100));
        cancellationFirst.runner.runNextTick();
        raceTick = cancellationFirst.runner.now() + 1;
        cancellationFirst.runner.scheduleAt(raceTick, () -> cancellationFirst.cancel("same tick"));
        cancellationFirst.runner.scheduleAt(raceTick, cancellationFirst.delegate::completeNow);

        cancellationFirst.runner.runNextTick();

        cancellationFirst.assertTerminal(Outcome.CANCELLED);
        assertEquals(MaterialBroker.ReservationState.REFUNDED, cancellationFirst.reservationState());
    }

    @Test
    void duplicateAndConflictingTerminalCallbacksAreIdempotent() {
        Simulation simulation = new Simulation(Scenario.failingAtStart());
        simulation.runner.runNextTick();

        simulation.delegate.replayTerminal(FakeDelegate.State.FAILED);
        simulation.delegate.replayTerminal(FakeDelegate.State.DONE);
        simulation.cancel("late cancellation");

        simulation.assertTerminal(Outcome.FAILED);
        assertEquals(1, simulation.refundCount);
        assertEquals(1, simulation.terminalPublications);
    }

    private enum Outcome {
        SUCCEEDED,
        CANCELLED,
        FAILED,
        OFFLINE,
        TIMED_OUT
    }

    private record Scenario(int workTicks, boolean failOnStart, boolean outputOccupied,
                            long deadlineTick) {
        static Scenario normal(int workTicks) {
            return new Scenario(workTicks, false, false, Long.MAX_VALUE);
        }

        static Scenario failingAtStart() {
            return new Scenario(1, true, false, Long.MAX_VALUE);
        }

        static Scenario withOccupiedOutput() {
            return new Scenario(1, false, true, Long.MAX_VALUE);
        }

        Scenario withDeadline(long deadlineTick) {
            return new Scenario(workTicks, failOnStart, outputOccupied, deadlineTick);
        }
    }

    private static final class Simulation {
        private static final NodeId NODE = new NodeId(0);
        private static final UUID CRAFT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

        final DeterministicTickRunner runner = new DeterministicTickRunner();
        final AssetLedger assets = new AssetLedger(1);
        final MachineLeaseRegistry machines = new MachineLeaseRegistry();
        final CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        final OperationBudget globalBudget = new OperationBudget(1, 8);
        final OperationBudget craftBudget = new OperationBudget(1, 8);
        final MaterialBroker broker = new MaterialBroker();
        final DagScheduler scheduler = new DagScheduler(singleNodeGraph());
        final NodeAdmissionCoordinator admissions = new NodeAdmissionCoordinator(scheduler, broker);
        final OperationExecutionKernel kernel = new OperationExecutionKernel(
                new OperationResourceCoordinator(machines, captures, globalBudget));
        final MaterialKey input = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        final MaterialKey output = MaterialKey.of(new ItemStack(Items.GOLD_INGOT));
        final MaterialSource.InitialPool inputSource = new MaterialSource.InitialPool(input);
        final MaterialSource.ProducerOutput outputSource = new MaterialSource.ProducerOutput(
                new OutputPortId(NODE, 0));
        final Scenario scenario;
        final FakeDelegate delegate;

        NodeAdmissionCoordinator.Admission admission;
        OperationExecutionKernel.Session session;
        Outcome outcome;
        TerminationCoordinator.Report report;
        boolean playerOnline = true;
        int refundCount;
        int terminalPublications;

        Simulation(Scenario scenario) {
            this.scenario = scenario;
            this.delegate = new FakeDelegate(scenario.workTicks(), scenario.failOnStart(),
                    scenario.outputOccupied(), this::onDelegateTerminal);
            broker.publish(inputSource, input, 1);
            prepare();
        }

        private void prepare() {
            scheduler.claim(NODE);
            admission = admissions.tryAdmitClaimed(new NodeAdmissionCoordinator.Candidate(NODE,
                    List.of(new MaterialBroker.Request(inputSource, input, 1))));
            assertNotNull(admission);
            session = kernel.tryPrepare(CRAFT_ID, NODE, 0, craftBudget,
                    new MachineLeaseRegistry.MachineKey(
                            new ResourceLocation("minecraft", "overworld"),
                            new BlockPos(0, 64, 0), "simulation_machine"),
                    new OperationResourceCoordinator.CaptureRequest(
                            new ResourceLocation("minecraft", "overworld"),
                            new AABB(0, 64, 0, 1, 65, 1), output.toStack(1)));
            assertNotNull(session);
            runner.scheduleNext(this::attemptStart);
        }

        private void attemptStart() {
            if (outcome != null) return;
            assertTrue(session.commit(() -> {
                admissions.commit(admission);
                assets.moveInventoryToMachine();
                return true;
            }));
            session.tryStart(delegate::start);
            if (outcome == null && delegate.state() == FakeDelegate.State.WORKING) {
                runner.scheduleNext(this::pumpWorkingDelegate);
            }
        }

        private void pumpWorkingDelegate() {
            if (outcome != null) return;
            if (!playerOnline) {
                terminate(Outcome.OFFLINE, TerminationCoordinator.Cause.OFFLINE, "player offline");
                return;
            }
            if (runner.now() >= scenario.deadlineTick()) {
                terminate(Outcome.TIMED_OUT, TerminationCoordinator.Cause.FAILURE, "timeout");
                return;
            }
            delegate.tick();
            if (outcome == null && delegate.state() == FakeDelegate.State.WORKING) {
                runner.scheduleNext(this::pumpWorkingDelegate);
            }
        }

        void cancel(String reason) {
            terminate(Outcome.CANCELLED, TerminationCoordinator.Cause.CANCELLED, reason);
        }

        private void onDelegateTerminal(FakeDelegate.State state) {
            if (outcome != null) return;
            if (state == FakeDelegate.State.DONE) {
                assertEquals(OperationExecutionKernel.CompletionResult.SUCCEEDED,
                        session.complete(() -> true, () -> admissions.settleMaterial(admission)));
                broker.publish(outputSource, output, 1);
                assertEquals(1, broker.drainAvailable(outputSource, output, 1).stream()
                        .mapToInt(ItemStack::getCount).sum());
                assets.moveMachineToDelivered();
                scheduler.succeed(NODE);
                publishSuccess();
            } else if (state == FakeDelegate.State.FAILED) {
                terminate(Outcome.FAILED, TerminationCoordinator.Cause.FAILURE,
                        delegate.failureReason());
            }
        }

        private void publishSuccess() {
            if (outcome != null) return;
            outcome = Outcome.SUCCEEDED;
            terminalPublications++;
            session.close();
            scheduler.stopScheduling();
            runner.stop();
        }

        private void terminate(Outcome terminal, TerminationCoordinator.Cause cause, String reason) {
            if (outcome != null) return;
            outcome = terminal;
            terminalPublications++;
            delegate.abortWithoutCallback();
            report = new TerminationService().terminate(CRAFT_ID, cause, reason,
                    TerminationService.Policy.REFUND_AND_DELIVER, new TerminationService.Actions() {
                        @Override
                        public void classify(TerminationService.Session termination) {
                            termination.classify(switch (session.terminalClass()) {
                                case PRE_START -> TerminationCoordinator.OperationState.PRE_START;
                                case IN_FLIGHT -> TerminationCoordinator.OperationState.IN_FLIGHT;
                                case SETTLED -> TerminationCoordinator.OperationState.SETTLED;
                            });
                        }

                        @Override
                        public void settleCaptured(boolean deliverCaptured) {
                            session.drainCapture();
                        }

                        @Override
                        public void closeOperationScope() {
                            session.close();
                        }

                        @Override
                        public void cleanupGraph() {
                            scheduler.stopScheduling();
                            for (NodeId running : List.copyOf(scheduler.runningNodes())) {
                                if (terminal == Outcome.FAILED || terminal == Outcome.TIMED_OUT) {
                                    scheduler.fail(running);
                                } else {
                                    scheduler.releaseClaim(running);
                                }
                            }
                        }

                        @Override public void recoverGraphSurplus() {}

                        @Override
                        public void refundLedger() {
                            MaterialBroker.ReservationState state = reservationState();
                            if (state == MaterialBroker.ReservationState.RESERVED) {
                                admissions.releaseMaterial(admission);
                            } else if (state == MaterialBroker.ReservationState.COMMITTED) {
                                admissions.refundCommittedMaterial(admission);
                                assets.moveMachineToInventory();
                                refundCount++;
                            }
                        }

                        @Override public void deliverSettledAssets() {}
                        @Override public void closeLedger() {}
                        @Override public void notifyOwner() {}
                    });
            runner.stop();
        }

        MaterialBroker.ReservationState reservationState() {
            return broker.state(admission.materialToken());
        }

        void assertTerminal(Outcome expected) {
            assertEquals(expected, outcome);
            assertEquals(1, assets.total(), "asset conservation");
            assertTrue(refundCount <= 1, "refund must execute at most once");
            assertEquals(0, machines.size(), "machine leases");
            assertEquals(0, captures.size(), "capture leases");
            assertEquals(0, CraftOutputInterceptor.activeZoneCount(), "capture handles");
            assertEquals(0, craftBudget.active(), "craft permits");
            assertEquals(0, globalBudget.active(), "global permits");
            assertEquals(1, terminalPublications, "terminal publication count");
            assertEquals(0, broker.heldBy(NODE), "broker reservations");
            assertTrue(scheduler.isStopping());
            assertTrue(scheduler.isDrained(), "running graph nodes");
            assertEquals(0, scheduler.readyCount());
            assertEquals(0, runner.pendingTasks());

            if (expected == Outcome.SUCCEEDED) {
                assertEquals(0, assets.inventory());
                assertEquals(1, assets.delivered());
                assertEquals(0, broker.available(inputSource, input));
            } else {
                assertEquals(1, assets.inventory());
                assertEquals(0, assets.delivered());
                assertEquals(1, broker.available(inputSource, input));
                assertNotNull(report);
                assertTrue(report.clean(), report.toString());
            }

            int workTicksAtTerminal = delegate.workTicks();
            runner.runTicks(3);
            assertEquals(workTicksAtTerminal, delegate.workTicks(),
                    "delegate work continued after terminal state");
            assertEquals(0, runner.pendingTasks());
        }

        private static CraftPlanGraph singleNodeGraph() {
            NodeId nodeId = NODE;
            MaterialKey input = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
            MaterialKey output = MaterialKey.of(new ItemStack(Items.GOLD_INGOT));
            InputPortId inputPort = new InputPortId(nodeId, 0);
            OutputPortId outputPort = new OutputPortId(nodeId, 0);
            MaterialSource.InitialPool inputSource = new MaterialSource.InitialPool(input);
            CraftNode node = new CraftNode(nodeId, new ResourceLocation("test", "simulation"),
                    "generic", new ResourceLocation("test", "simulation"), 1,
                    List.of(), List.of(), false, null, null,
                    List.of(new InputDemand(inputPort, Ingredient.of(input.toStack(1)), 1,
                            DemandRole.CONSUMED, input.toStack(1))),
                    List.of(new OutputDeclaration(outputPort, output, 1, OutputKind.PRIMARY)));
            return new CraftPlanGraph(1, List.of(node),
                    List.of(new MaterialAllocation(new AllocationId(0), inputPort,
                            inputSource, input, 1)),
                    List.of(new RootDemand(Ingredient.of(output.toStack(1)), 1, 0,
                            output.toStack(1), List.of(new RootAllocation(
                            new MaterialSource.ProducerOutput(outputPort), output, 1)))),
                    List.of(), List.of(nodeId));
        }
    }

    private static final class DeterministicTickRunner {
        private final Map<Long, List<Runnable>> tasks = new TreeMap<>();
        private long tick;
        private long sequence;
        private boolean stopped;

        long now() {
            return tick;
        }

        void scheduleNext(Runnable task) {
            scheduleAt(tick + 1, task);
        }

        void scheduleAt(long scheduledTick, Runnable task) {
            if (stopped) return;
            if (scheduledTick <= tick) throw new IllegalArgumentException("task must target a future tick");
            tasks.computeIfAbsent(scheduledTick, ignored -> new ArrayList<>()).add(
                    new SequencedTask(sequence++, task));
        }

        void runNextTick() {
            tick++;
            List<Runnable> current = tasks.remove(tick);
            if (current == null) return;
            for (Runnable task : List.copyOf(current)) task.run();
        }

        void runTicks(int count) {
            for (int index = 0; index < count; index++) runNextTick();
        }

        int pendingTasks() {
            return tasks.values().stream().mapToInt(List::size).sum();
        }

        void stop() {
            stopped = true;
            tasks.clear();
        }

        private static final class SequencedTask implements Runnable {
            private final long sequence;
            private final Runnable action;

            private SequencedTask(long sequence, Runnable action) {
                this.sequence = sequence;
                this.action = action;
            }

            @Override
            public void run() {
                action.run();
            }

            @Override
            public String toString() {
                return "tick-task-" + sequence;
            }
        }
    }

    private static final class AssetLedger {
        private int inventory;
        private int machine;
        private int delivered;

        AssetLedger(int inventory) {
            this.inventory = inventory;
        }

        void moveInventoryToMachine() {
            assertEquals(1, inventory);
            inventory--;
            machine++;
        }

        void moveMachineToInventory() {
            assertEquals(1, machine);
            machine--;
            inventory++;
        }

        void moveMachineToDelivered() {
            assertEquals(1, machine);
            machine--;
            delivered++;
        }

        int inventory() { return inventory; }
        int delivered() { return delivered; }
        int total() { return inventory + machine + delivered; }
    }

    private static final class FakeDelegate {
        enum State {
            STARTING,
            WORKING,
            DONE,
            FAILED
        }

        interface TerminalListener {
            void onTerminal(State state);
        }

        private final int requiredWorkTicks;
        private final boolean failOnStart;
        private final boolean outputOccupied;
        private final TerminalListener listener;
        private final List<State> history = new ArrayList<>();
        private State state = State.STARTING;
        private boolean machineAlive = true;
        private int workTicks;
        private String failureReason = "delegate failed";

        FakeDelegate(int requiredWorkTicks, boolean failOnStart, boolean outputOccupied,
                     TerminalListener listener) {
            this.requiredWorkTicks = requiredWorkTicks;
            this.failOnStart = failOnStart;
            this.outputOccupied = outputOccupied;
            this.listener = listener;
            history.add(State.STARTING);
        }

        boolean start() {
            if (failOnStart) {
                fail("machine rejected start");
                return false;
            }
            if (outputOccupied) {
                fail("output slot occupied");
                return false;
            }
            transition(State.WORKING);
            return true;
        }

        void tick() {
            if (state != State.WORKING) return;
            if (!machineAlive) {
                fail("machine destroyed");
                return;
            }
            workTicks++;
            if (workTicks >= requiredWorkTicks) completeNow();
        }

        void completeNow() {
            if (state != State.WORKING) {
                replayTerminal(State.DONE);
                return;
            }
            transition(State.DONE);
            listener.onTerminal(State.DONE);
        }

        void destroyMachine() {
            machineAlive = false;
        }

        void replayTerminal(State terminal) {
            if (terminal != State.DONE && terminal != State.FAILED) {
                throw new IllegalArgumentException("not terminal: " + terminal);
            }
            listener.onTerminal(terminal);
        }

        void abortWithoutCallback() {
            if (state == State.STARTING || state == State.WORKING) transition(State.FAILED);
        }

        private void fail(String reason) {
            failureReason = reason;
            transition(State.FAILED);
            listener.onTerminal(State.FAILED);
        }

        private void transition(State next) {
            state = next;
            history.add(next);
        }

        State state() { return state; }
        int workTicks() { return workTicks; }
        String failureReason() { return failureReason; }
        List<State> history() { return List.copyOf(history); }
    }
}
