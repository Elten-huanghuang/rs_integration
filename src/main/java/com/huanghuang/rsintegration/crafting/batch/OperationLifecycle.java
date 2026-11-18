package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.OperationExecutionKernel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import javax.annotation.Nullable;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.function.Consumer;

/** 一次派发拥有一个收货会话；调度器只决定何时执行各步，不接触机器槽或网络资格。 */
public final class OperationLifecycle {
    private final IBatchDelegate prepared;
    private final TaskOutputReception.Session reception;
    private boolean collected;
    private boolean cleaned;
    private final List<ItemStack> pendingHandoff = new ArrayList<>();
    private OperationOutputSource source;

    private OperationOutputSource source() {
        if (source == null) source = prepared.outputSource();
        return source;
    }

    public OperationLifecycle(IBatchDelegate prepared) {
        this(prepared, TaskOutputReception.open());
    }

    public OperationLifecycle(IBatchDelegate prepared, TaskOutputReception.Session reception) {
        this.prepared = prepared;
        this.reception = reception;
    }

    public boolean start(OperationStartContext context) {
        boolean accepted = false;
        try {
            MachineOperation machine = prepared.machineOperation();
            machine.bindProduction(reception.writer());
            accepted = machine.start(context);
            return accepted;
        } finally {
            if (!accepted) reception.close();
        }
    }

    public void transferAvailable(ServerLevel level) {
        source().transferAvailable(level);
    }

    public MachineOperation.CraftObservation advanceAndObserve(ServerLevel level) {
        MachineOperation machine = prepared.machineOperation();
        machine.advance(level);
        source().transferSignalled();
        return machine.observe(level);
    }

    public MachineOperation.CraftObservation poll(ServerLevel level) {
        transferAvailable(level);
        return advanceAndObserve(level);
    }

    public OperationCollection collect(ServerPlayer player, int operations, Supplier<List<ItemStack>> capture) {
        if (collected) throw new IllegalStateException("同一操作不能重复终结收取");
        var contract = prepared.outputContract();
        var handoff = source().collectFinal(player);
        collected = true;
        pendingHandoff.addAll(handoff.stacks());
        var captured = capture.get();
        pendingHandoff.addAll(captured);
        reception.close();
        var cumulative = contract == null || contract.ports().isEmpty() ? List.<ItemStack>of() : reception.cumulativeStacks();
        var held = reception.drainStacks();
        pendingHandoff.addAll(held);
        var result = OperationCollection.assess(handoff, contract, operations, captured, held, cumulative);
        pendingHandoff.clear();
        return result;
    }

    public List<ItemStack> drainHeld() { return reception.drainStacks(); }

    /** 将实物先交给任务，短缺仍须结清已消费的材料；清理抛错不能撤销前面的所有权。 */
    public boolean finish(ServerPlayer player, int operations, Supplier<List<ItemStack>> capture,
                          Consumer<List<ItemStack>> publish, @Nullable OperationExecutionKernel.Session kernel,
                          OperationExecutionKernel.SettlementAction settle) {
        var result = collect(player, operations, capture);
        publish.accept(result.stacks());
        boolean complete = completeOutput(result);
        if (kernel == null) settle.settle();
        else kernel.settle(settle);
        cleanup(player);
        return complete;
    }

    private boolean completeOutput(OperationCollection result) {
        if (result.settlement() != null) return result.settlement().complete();
        var expected = prepared.getExpectedProduction();
        if (expected != null) {
            long actual = reception.received(TaskOutputReception.resource(expected.item()));
            for (var stack : result.physical()) {
                if (ItemStack.isSameItem(stack, expected.item())) actual += stack.getCount();
            }
            return actual >= expected.count();
        }
        var world = prepared.getExpectedOutput();
        return world == null || world.isEmpty() || !result.physical().isEmpty();
    }

    public List<ItemStack> retireHolding() {
        reception.close();
        var held = drainHeld();
        if (pendingHandoff.isEmpty()) return held;
        var recovered = new ArrayList<>(pendingHandoff);
        pendingHandoff.clear();
        recovered.addAll(held);
        return List.copyOf(recovered);
    }

    /** 离线停服不能访问玩家专属取货路径，只交出操作已持有的捕获物和资源。 */
    public List<ItemStack> collectForShutdown(@Nullable ServerPlayer player, int operations,
                                              Supplier<List<ItemStack>> capture) {
        if (player != null) return collect(player, operations, capture).stacks();
        if (collected) throw new IllegalStateException("同一操作不能重复终结收取");
        List<ItemStack> outputs = new ArrayList<>(capture.get());
        outputs.addAll(retireHolding());
        collected = true;
        return List.copyOf(outputs);
    }

    public void discardOrRecover(boolean started, @Nullable ServerPlayer player, String reason) {
        if (started) recover(player, reason);
        else prepared.releasePreparationResources();
    }

    public void cleanup(@Nullable ServerPlayer player) {
        reception.close();
        if (cleaned) return;
        cleaned = true;
        prepared.machineOperation().cleanup(player);
    }

    public void recover(@Nullable ServerPlayer player, String reason) {
        reception.close();
        if (cleaned) return;
        if (collected) {
            cleanup(player);
            return;
        }
        cleaned = true;
        prepared.machineOperation().recover(player, reason);
    }
}
