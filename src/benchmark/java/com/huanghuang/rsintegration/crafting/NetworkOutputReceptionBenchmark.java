package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageOutputReception;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.mockito.Mockito.mock;

/**
 * 测量新增分流层本身；下游回调直接接收，不包含磁盘 I/O、管道扫描或完整 Forge tick。
 * 网络 mock 仅作身份，不在计时区域调用 Mockito；资格检查固定为有效。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx1g"})
public class NetworkOutputReceptionBenchmark {
    private static final BiFunction<ItemStack, Integer, ItemStack> ACCEPT_ALL =
            (stack, amount) -> ItemStack.EMPTY;

    @State(Scope.Thread)
    public static class IdleState extends BootstrapTest {
        @Param({"0", "32"})
        public int tagFields;

        INetwork network;
        ItemStack matching;
        ItemStack unrelated;

        @Setup(Level.Trial)
        public void setupItems() {
            bootstrapMinecraft();
            network = mock(INetwork.class);
            matching = new ItemStack(Items.IRON_INGOT);
            unrelated = new ItemStack(Items.GOLD_INGOT);
            for (int i = 0; i < tagFields; i++) {
                matching.getOrCreateTag().putString("field_" + i, "value_" + i);
                unrelated.getOrCreateTag().putString("field_" + i, "value_" + i);
            }
            if (NetworkOutputReception.canReceive(network)) {
                throw new AssertionError("基准开始前不应存在等待项");
            }
        }
    }

    @State(Scope.Thread)
    public static class WaitingState extends IdleState {
        @Param({"1", "64", "1024", "8192"})
        public int waiters;

        TaskOutputReception.Session[] sessions;
        IntegrationResourceKey key;
        boolean expectsReception;

        @Setup(Level.Trial)
        public void setupWaiters(BenchmarkParams parameters) {
            expectsReception = parameters.getBenchmark().endsWith(".matchingPerform");
            key = IntegrationResourceKey.item(matching);
            sessions = new TaskOutputReception.Session[waiters];
            List<TaskOutputReception.Demand> demands = List.of(
                    new TaskOutputReception.Demand("out", key, Long.MAX_VALUE, Long.MAX_VALUE));
            for (int i = 0; i < sessions.length; i++) {
                sessions[i] = TaskOutputReception.open();
                sessions[i].bindNetwork(network, () -> true);
                sessions[i].prepare(demands).activate();
            }
        }

        @TearDown(Level.Trial)
        public void verifyAndClose() {
            try {
                long firstReceived = sessions[0].received(key);
                long firstHeld = sessions[0].drain().stream()
                        .mapToLong(TaskOutputReception.Received::amount).sum();
                if (firstReceived != firstHeld || sessions[0].current().complete()) {
                    throw new AssertionError("累计收货、实物或未完成状态不一致");
                }
                if (expectsReception ? firstReceived == 0 : firstReceived != 0) {
                    throw new AssertionError("模拟、未匹配或清理操作不能新增收货");
                }
                for (int i = 1; i < sessions.length; i++) {
                    if (sessions[i].received(key) != 0 || !sessions[i].drain().isEmpty()) {
                        throw new AssertionError("小额收货不应分配给后续等待项");
                    }
                }
            } finally {
                for (TaskOutputReception.Session session : sessions) session.close();
            }
            if (NetworkOutputReception.canReceive(network)) {
                throw new AssertionError("关闭会话后仍有索引残留");
            }
        }
    }

    @State(Scope.Thread)
    public static class DifferentItemsState extends IdleState {
        @Param({"4", "64", "1024"})
        public int resourceTypes;

        TaskOutputReception.Session[] sessions;
        List<ItemStack> deliveries;

        @Override
        @Setup(Level.Trial)
        public void setupItems() {
            super.setupItems();
            deliveries = BuiltInRegistries.ITEM.stream()
                    .filter(item -> item != Items.AIR)
                    .limit(resourceTypes).map(ItemStack::new).toList();
            if (deliveries.size() != resourceTypes || resourceTypes < 4) {
                throw new AssertionError("基准需要至少四种不同物品");
            }
            sessions = new TaskOutputReception.Session[resourceTypes];
            for (int i = 0; i < resourceTypes; i++) {
                ItemStack stack = deliveries.get(i);
                if (matching.hasTag()) stack.setTag(matching.getTag().copy());
                sessions[i] = TaskOutputReception.open();
                sessions[i].bindNetwork(network, () -> true);
                sessions[i].prepare(List.of(new TaskOutputReception.Demand("out",
                        IntegrationResourceKey.item(stack), Long.MAX_VALUE, Long.MAX_VALUE))).activate();
            }
        }

        @TearDown(Level.Trial)
        public void verifyAndClose() {
            try {
                long rounds = sessions[0].received(IntegrationResourceKey.item(deliveries.get(0)));
                if (rounds <= 0) throw new AssertionError("四种物品必须实际入账");
                for (int i = 0; i < resourceTypes; i++) {
                    long expected = i < 4 ? rounds : 0;
                    long held = sessions[i].drain().stream()
                            .mapToLong(TaskOutputReception.Received::amount).sum();
                    if (sessions[i].received(IntegrationResourceKey.item(deliveries.get(i))) != expected
                            || held != expected) {
                        throw new AssertionError("不同物品等待项不得串账");
                    }
                }
            } finally {
                for (TaskOutputReception.Session session : sessions) session.close();
            }
            if (NetworkOutputReception.canReceive(network)) {
                throw new AssertionError("关闭不同物品会话后仍有索引残留");
            }
        }
    }

    /** 一次调用依次交付四种物品；JMH 结果按每笔入账折算。 */
    @Benchmark
    @OperationsPerInvocation(4)
    public int differentItemsFourDeliveries(DifferentItemsState state) {
        int remainder = 0;
        for (int i = 0; i < 4; i++) {
            remainder += RefinedStorageOutputReception.insertItem(
                    state.network, state.deliveries.get(i), 1, Action.PERFORM, ACCEPT_ALL).getCount();
        }
        return remainder;
    }

    @Benchmark
    public ItemStack noWaiters(IdleState state) {
        return RefinedStorageOutputReception.insertItem(state.network, state.matching, 1,
                Action.PERFORM, ACCEPT_ALL);
    }

    @Benchmark
    public ItemStack unrelatedItem(WaitingState state) {
        return RefinedStorageOutputReception.insertItem(state.network, state.unrelated, 1,
                Action.PERFORM, ACCEPT_ALL);
    }

    @Benchmark
    public ItemStack matchingPerform(WaitingState state) {
        return RefinedStorageOutputReception.insertItem(state.network, state.matching, 1,
                Action.PERFORM, ACCEPT_ALL);
    }

    @Benchmark
    public ItemStack matchingSimulate(WaitingState state) {
        return RefinedStorageOutputReception.insertItem(state.network, state.matching, 1,
                Action.SIMULATE, ACCEPT_ALL);
    }

    @Benchmark
    public void pruneValidSessions(WaitingState state) {
        TaskOutputReception.pruneNetwork(state.network);
    }
}
