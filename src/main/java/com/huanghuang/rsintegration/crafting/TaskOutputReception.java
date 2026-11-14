package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * RSI 执行器实际取出的产物账本。资源由本次操作私有持有，再移交发布或恢复层。
 * 不监听网络入库，不认领外部搬运的物品；所有操作限于创建上下文的服务器线程。
 */
public final class TaskOutputReception {
    private TaskOutputReception() {}

    private enum State { PREPARED, ACTIVE, CLOSED }

    /** min 是必达量，max 只是进度上限；概率产物的 min 必须为零。 */
    public record Demand(String port, IntegrationResourceKey resource, long min, long max) {
        public Demand {
            Objects.requireNonNull(port, "port");
            Objects.requireNonNull(resource, "resource");
            if (port.isBlank() || min < 0 || max < min) throw new IllegalArgumentException("invalid demand");
        }
    }

    public record Received(IntegrationResourceKey resource, long amount) {}

    /** 每次执行独立持有产物，不依赖 RS、BD 或其他存储后端的原生对象。 */
    public static Session open() { return new Session(); }

    public static final class Session implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final Map<IntegrationResourceKey, Long> pending = new LinkedHashMap<>();
        private final Map<IntegrationResourceKey, Long> received = new LinkedHashMap<>();
        private Batch current;
        private boolean closed;

        private Session() {}

        private void checkThread() {
            if (owner != Thread.currentThread()) throw new IllegalStateException("收货操作必须在所属服务器线程执行");
        }

        /** 投料前准备，确认成功后 activate；启动失败关闭上下文时仍保留已取出的实物。 */
        public Batch prepare(List<Demand> demands) {
            checkThread();
            if (closed) throw new IllegalStateException("收货上下文已关闭");
            if (current != null && current.state != State.CLOSED) {
                throw new IllegalStateException("前一物理批次尚未结束");
            }
            current = new Batch(this, demands);
            return current;
        }

        @Nullable public Batch current() { return current; }
        public boolean closed() { return closed; }

        public long received(IntegrationResourceKey resource) {
            checkThread();
            return received.getOrDefault(resource, 0L);
        }

        private void hold(IntegrationResourceKey resource, long amount) {
            long newPending = Math.addExact(pending.getOrDefault(resource, 0L), amount);
            long newReceived = Math.addExact(received.getOrDefault(resource, 0L), amount);
            pending.put(resource, newPending);
            received.put(resource, newReceived);
        }

        public List<Received> drain() {
            checkThread();
            List<Received> result = pending.entrySet().stream()
                    .map(entry -> new Received(entry.getKey(), entry.getValue())).toList();
            pending.clear();
            return result;
        }

        /** 全部成功构造后才移交所有权，转换失败不会清空已持有的资源。 */
        public List<ItemStack> drainStacks() {
            checkThread();
            List<ItemStack> result = new ArrayList<>();
            for (var entry : pending.entrySet()) {
                long left = entry.getValue();
                while (left > 0) {
                    int count = (int) Math.min(Integer.MAX_VALUE, left);
                    ItemStack stack = stack(entry.getKey(), count);
                    if (stack.isEmpty()) throw new IllegalStateException("已收资源无法转交: " + entry.getKey());
                    result.add(stack);
                    left -= count;
                }
            }
            pending.clear();
            return List.copyOf(result);
        }

        /** 关闭后不再接收，已经持有的资源仍可且必须被执行层移交或恢复。 */
        @Override public void close() {
            checkThread();
            if (closed) return;
            closed = true;
            if (current != null) current.close();
        }
    }

    public static final class Batch implements AutoCloseable {
        private final Session session;
        private final List<Demand> demands;
        private final long[] received;
        private State state = State.PREPARED;

        private Batch(Session session, List<Demand> demands) {
            this.session = session;
            this.demands = List.copyOf(demands);
            Set<String> ports = new HashSet<>();
            Map<IntegrationResourceKey, Long> totals = new LinkedHashMap<>();
            for (Demand demand : this.demands) {
                if (!ports.add(demand.port())) throw new IllegalArgumentException("duplicate port " + demand.port());
                totals.merge(demand.resource(), demand.max(), Math::addExact);
            }
            received = new long[demands.size()];
        }

        public void activate() {
            session.checkThread();
            if (state != State.PREPARED) throw new IllegalStateException("批次无法激活");
            state = State.ACTIVE;
        }

        public long received(String port) {
            session.checkThread();
            for (int i = 0; i < demands.size(); i++) {
                if (demands.get(i).port().equals(port)) return received[i];
            }
            return 0;
        }

        public long remaining(String port) {
            session.checkThread();
            for (Demand demand : demands) {
                if (demand.port().equals(port)) return demand.max() - received(port);
            }
            return 0;
        }

        public boolean complete() {
            session.checkThread();
            if (state != State.ACTIVE || demands.isEmpty()) return false;
            for (int i = 0; i < demands.size(); i++) {
                if (received[i] < demands.get(i).min()) return false;
            }
            return true;
        }

        /**
         * 仅记录已经实际取出的资源，返回计入本批声明的数量；超额实物同样保留。
         * 每次调用对应一次新的实际提取；重复发布由 Session 的取走即清空规则防止。
         */
        public long collect(IntegrationResourceKey resource, long amount) {
            session.checkThread();
            Objects.requireNonNull(resource, "resource");
            if (amount <= 0) return 0;
            if (state == State.CLOSED) throw new IllegalStateException("不能向已关闭批次交货");
            long[] allocation = new long[demands.size()];
            long accepted = 0;
            // 同资源多端口先填必达量，再填可选上限。
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < demands.size() && accepted < amount; i++) {
                    Demand demand = demands.get(i);
                    if (!resource.equals(demand.resource())) continue;
                    long limit = pass == 0 ? demand.min() : demand.max();
                    long room = Math.max(0, limit - received[i] - allocation[i]);
                    long take = Math.min(amount - accepted, room);
                    allocation[i] += take;
                    accepted += take;
                }
            }
            // 先核对累计数量不会溢出，再推进批次进度，失败不留下半条账。
            session.hold(resource, amount);
            for (int i = 0; i < allocation.length; i++) received[i] += allocation[i];
            return accepted;
        }

        @Override public void close() {
            session.checkThread();
            state = State.CLOSED;
        }
    }

    public static IntegrationResourceKey resource(ItemStack stack) {
        IntegrationResourceKey fluid = InkFluidSupport.resource(stack);
        return fluid != null ? fluid : IntegrationResourceKey.item(stack);
    }

    private static ItemStack stack(IntegrationResourceKey resource, int amount) {
        if (resource.kind() == IntegrationResourceKey.ResourceKind.FLUID) return InkFluidSupport.tokenFor(resource, amount);
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(resource.id()), amount);
        if (!resource.identity().isEmpty()) stack.setTag(resource.identity());
        return stack;
    }
}
