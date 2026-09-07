package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OperationBudget;
import com.huanghuang.rsintegration.crafting.loadbalancer.OperationQueue;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FlatParallelWindowTest extends BootstrapTest {
    @Test
    void bothGraphAndFlatGroupsReceiveSelectedStorageBeforeChildPreparation() throws IOException {
        List<String> flat = calls(AsyncCraftChain.class, "tryStartParallelWindow");
        List<String> graph = calls(AsyncCraftChain.class, "dispatchPreparedGraphNode");
        for (List<String> methodCalls : List.of(flat, graph)) {
            assertTrue(methodCalls.stream().anyMatch(call -> call.contains("ParallelCraftGroup#<init>")
                    && call.contains("CraftStorageEndpoint;")));
        }
        Class<?> group = com.huanghuang.rsintegration.crafting.loadbalancer.ParallelCraftGroup.class;
        List<String> preparation = calls(group, "prepareChildDelegate");
        int configure = indexOfCall(preparation, "#configureDelegate");
        int prepare = indexOfCall(preparation, "PreparationMessageScope#prepare");
        assertTrue(configure >= 0 && prepare > configure);
        assertTrue(calls(group, "configureDelegate").stream()
                .anyMatch(call -> call.contains("#setStorageEndpoint")));
    }

    private static int indexOfCall(List<String> calls, String fragment) {
        for (int index = 0; index < calls.size(); index++) {
            if (calls.get(index).contains(fragment)) return index;
        }
        return -1;
    }

    private static List<String> calls(Class<?> type, String method) throws IOException {
        List<String> calls = new ArrayList<>();
        try (var stream = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!name.equals(method)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            calls.add(owner + "#" + name + descriptor);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return calls;
    }
    @Test
    void smallAndLargeOrdersUseBoundedWindowsInsteadOfDisablingParallelism() {
        assertEquals(0, AsyncCraftChain.flatDispatchWindow(0, 32));
        assertEquals(1, AsyncCraftChain.flatDispatchWindow(1, 32));
        assertEquals(2, AsyncCraftChain.flatDispatchWindow(2, 32));
        assertEquals(32, AsyncCraftChain.flatDispatchWindow(32, 32));
        assertEquals(32, AsyncCraftChain.flatDispatchWindow(33, 32));
        assertEquals(32, AsyncCraftChain.flatDispatchWindow(100, 32));
        assertEquals(256, AsyncCraftChain.flatDispatchWindow(Integer.MAX_VALUE, 256));
        assertEquals(1, AsyncCraftChain.flatDispatchWindow(100, 1));
    }

    @Test
    void oneHundredOperationsFinishInFourWindowsWithoutDroppingTheRemainder() {
        int remaining = 100;
        List<Integer> windows = new ArrayList<>();
        while (remaining > 0) {
            int window = AsyncCraftChain.flatDispatchWindow(remaining, 32);
            windows.add(window);
            remaining = AsyncCraftChain.remainingAfterFlatBatch(remaining, window);
        }
        assertEquals(List.of(32, 32, 32, 4), windows);
    }

    @Test
    void progressAccumulatesPreviousWindowsWithoutReplacingTheOrderTotal() {
        assertEquals(0, AsyncCraftChain.completedFlatWindowOperations(100, 100, 0));
        assertEquals(32, AsyncCraftChain.completedFlatWindowOperations(100, 100, 32));
        assertEquals(32, AsyncCraftChain.completedFlatWindowOperations(100, 68, 0));
        assertEquals(37, AsyncCraftChain.completedFlatWindowOperations(100, 68, 5));
        assertEquals(100, AsyncCraftChain.completedFlatWindowOperations(100, 4, 4));
        assertEquals(Integer.MAX_VALUE, AsyncCraftChain.completedFlatWindowOperations(
                Integer.MAX_VALUE, 1, Integer.MAX_VALUE));
    }

    @Test
    void completionCannotSilentlyOverrunOrDropAnUnfinishedWindow() {
        assertEquals(68, AsyncCraftChain.remainingAfterFlatBatch(100, 32));
        assertEquals(1, AsyncCraftChain.remainingAfterFlatBatch(33, 32));
        assertThrows(IllegalArgumentException.class,
                () -> AsyncCraftChain.remainingAfterFlatBatch(4, 32));
        assertThrows(IllegalArgumentException.class,
                () -> AsyncCraftChain.remainingAfterFlatBatch(4, 0));
    }

    @Test
    void realQueueAndKernelRunTwoSameTypeMachinesInEveryWindow() {
        for (String type : List.of("vanilla_furnace", "vanilla_blast_furnace", "vanilla_smoker",
                "ironfurnaces_furnace", "botania_mana_pool")) {
            for (int total : List.of(1, 2, 3, 32, 33, 100)) {
                runSameTypeWindows(type, total);
            }
        }
    }

    private static void runSameTypeWindows(String type, int total) {
        MachineLeaseRegistry machines = new MachineLeaseRegistry();
        OperationBudget global = new OperationBudget(2, 200);
        OperationBudget craft = new OperationBudget(2, 200);
        OperationExecutionKernel kernel = new OperationExecutionKernel(
                new OperationResourceCoordinator(machines, new CaptureLeaseRegistry(), global));
        UUID craftId = UUID.randomUUID();
        int remaining = total;
        int completed = 0;
        while (remaining > 0) {
            int window = AsyncCraftChain.flatDispatchWindow(remaining, 32);
            OperationQueue queue = new OperationQueue(window);
            while (!queue.isComplete()) {
                List<OperationExecutionKernel.Session> active = new ArrayList<>();
                int workers = Math.min(2, queue.queuedOperations());
                for (int worker = 0; worker < workers; worker++) {
                    int operation = queue.claim(worker);
                    assertTrue(operation >= 0);
                    var machine = new MachineLeaseRegistry.MachineKey(
                            new ResourceLocation("minecraft", "overworld"),
                            new BlockPos(worker, 64, 0), type);
                    var session = kernel.tryPrepare(craftId, new NodeId(0), operation,
                            craft, machine, null);
                    assertNotNull(session);
                    assertTrue(session.commit(() -> true));
                    assertTrue(session.tryStart(() -> true));
                    active.add(session);
                }
                assertEquals(workers, machines.size());
                assertEquals(workers, queue.runningOperations());
                for (int worker = 0; worker < active.size(); worker++) {
                    final int currentWorker = worker;
                    assertEquals(OperationExecutionKernel.CompletionResult.SUCCEEDED,
                            active.get(worker).complete(() -> true, () -> queue.complete(currentWorker)));
                    active.get(worker).close();
                }
                assertEquals(0, machines.size());
            }
            completed += queue.completedOperations();
            remaining = AsyncCraftChain.remainingAfterFlatBatch(remaining, queue.completedOperations());
            assertEquals(completed, AsyncCraftChain.completedFlatWindowOperations(total, remaining, 0));
        }
        assertEquals(total, completed);
        assertEquals(0, craft.active());
        assertEquals(0, global.active());
    }

    @Test
    void samePhysicalMachineCannotBeClaimedTwice() {
        var machines = new MachineLeaseRegistry();
        var key = new MachineLeaseRegistry.MachineKey(new ResourceLocation("minecraft", "overworld"),
                BlockPos.ZERO, "botania_mana_pool");
        var first = machines.tryAcquire(key, new MachineLeaseRegistry.Owner(UUID.randomUUID(), new NodeId(0), 0));
        assertNotNull(first);
        assertNull(machines.tryAcquire(key,
                new MachineLeaseRegistry.Owner(UUID.randomUUID(), new NodeId(0), 1)));
        machines.releaseAll(List.of(first));
        assertEquals(0, machines.size());
    }

    @Test
    void flatMachineFilterUsesDelegateAwareOverloadForCatalystBindings() throws IOException {
        AtomicBoolean delegateAware = new AtomicBoolean();
        try (var stream = AsyncCraftChain.class.getResourceAsStream("AsyncCraftChain.class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!name.equals("tryStartParallelWindow")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            if (owner.endsWith("/LoadBalancer") && name.equals("filterAvailable")) {
                                delegateAware.set(descriptor.contains("IBatchDelegate;"));
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertTrue(delegateAware.get());
    }
}
