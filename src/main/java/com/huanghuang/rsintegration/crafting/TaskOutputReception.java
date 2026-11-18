package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.IntegrationResourceKey.ResourceKind;
import com.huanghuang.rsintegration.feature.experience.ExperienceFluidNormalization;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 任务收货进度及实物持有。机器实际抽取与网络入口认领共用同一本账，取走后移交发布或恢复层。
 * 网络按对象身份隔离，只有激活的等待项可以收货；所有操作限于所属服务器线程。
 */
public final class TaskOutputReception {
    private TaskOutputReception() {}

    private enum State { PREPARED, ACTIVE, CLOSED }
    private static final ThreadLocal<Map<Object, Waiters>> NETWORKS =
            new ThreadLocal<>();

    private static final class Waiters {
        final Set<Session> sessions = new LinkedHashSet<>();
        final Map<IntegrationResourceKey, WaitingBatches> resources = new LinkedHashMap<>();
        final Map<ResourceLocation, Integer> itemTypes = new HashMap<>();
        final Map<ResourceLocation, Integer> fluidTypes = new HashMap<>();
        private List<Session> sessionSnapshot;

        List<Session> sessionsSnapshot() {
            if (sessionSnapshot == null) sessionSnapshot = List.copyOf(sessions);
            return sessionSnapshot;
        }

        Map<ResourceLocation, Integer> types(ResourceKind kind) {
            return kind == ResourceKind.ITEM ? itemTypes : fluidTypes;
        }

        void add(IntegrationResourceKey resource, Batch batch) {
            WaitingBatches waiting = resources.get(resource);
            if (waiting == null) {
                waiting = new WaitingBatches();
                resources.put(resource, waiting);
                types(resource.kind()).merge(resource.id(), 1, Integer::sum);
            }
            if (waiting.batches.add(batch)) waiting.snapshot = null;
        }

        void remove(IntegrationResourceKey resource, Batch batch) {
            WaitingBatches waiting = resources.get(resource);
            if (waiting == null || !waiting.batches.remove(batch)) return;
            waiting.snapshot = null;
            if (waiting.batches.isEmpty()) {
                resources.remove(resource);
                types(resource.kind()).compute(resource.id(), (id, count) -> count == 1 ? null : count - 1);
            }
        }
    }

    private static final class WaitingBatches {
        final Set<Batch> batches = new LinkedHashSet<>();
        private List<Batch> snapshot;

        List<Batch> snapshot() {
            if (snapshot == null) snapshot = List.copyOf(batches);
            return snapshot;
        }
    }

    @Nullable
    private static Waiters networkWaiters(Object network) {
        Map<Object, Waiters> networks = NETWORKS.get();
        return networks == null ? null : networks.get(network);
    }

    /** 入库热路径先判断是否值得构造资源键；这里只读索引，不改模拟状态或检查机器。 */
    public static boolean hasWaitingResources(Object network) {
        Waiters waiters = networkWaiters(network);
        return waiters != null && !waiters.resources.isEmpty();
    }

    /** 复制 NBT 前先按类型和注册 ID 粗筛；同 ID 的所有 NBT 等待项都保留精确匹配机会。 */
    public static boolean hasWaitingResourceType(Object network, ResourceKind kind, ResourceLocation id) {
        Waiters waiters = networkWaiters(network);
        if (waiters == null || id == null) return false;
        if (kind == ResourceKind.FLUID) id = ExperienceFluidNormalization.normalizeFluidId(id);
        return waiters.types(kind).containsKey(id);
    }

    /** 返回入口实际交给任务的数量；模拟只计算容量，不改变进度或实物。 */
    public static long receive(Object network, IntegrationResourceKey resource, long amount, boolean simulate) {
        Waiters waiters = networkWaiters(network);
        if (waiters == null || amount <= 0) return 0;
        resource = ExperienceFluidNormalization.normalizeResource(resource);
        WaitingBatches batches = waiters.resources.get(resource);
        if (batches == null) return 0;
        long accepted = 0;
        // 收货或资格失效会移除索引；有序快照在成员不变时复用，避免每次小额入库都复制。
        for (Batch batch : batches.snapshot()) {
            if (accepted == amount) break;
            if (!batch.session.valid()) {
                if (!simulate) batch.session.unbindNetwork();
                continue;
            }
            accepted += batch.receiveFromNetwork(resource, amount - accepted, simulate);
        }
        return accepted;
    }

    /** 网络移除或停机后撤销资格，已经收到的实物仍由会话持有。 */
    public static void invalidateNetwork(Object network) {
        Waiters waiters = networkWaiters(network);
        if (waiters != null) {
            for (Session session : waiters.sessionsSnapshot()) session.unbindNetwork();
        }
    }

    /** 网络 tick 清理失效会话，即使没有新的入库也不留下等待项。 */
    public static void pruneNetwork(Object network) {
        Waiters waiters = networkWaiters(network);
        if (waiters != null) {
            for (Session session : waiters.sessionsSnapshot()) {
                if (!session.valid()) session.unbindNetwork();
            }
        }
    }

    /** min 是必达量，max 只是进度上限；概率产物的 min 必须为零。 */
    public record Demand(String port, IntegrationResourceKey resource, long min, long max) {
        public Demand {
            Objects.requireNonNull(port, "port");
            Objects.requireNonNull(resource, "resource");
            resource = ExperienceFluidNormalization.normalizeResource(resource);
            if (port.isBlank() || min < 0 || max < min) throw new IllegalArgumentException("invalid demand");
        }
    }

    public record Received(IntegrationResourceKey resource, long amount) {}

    /** 每次执行独立持有产物，不依赖 RS、BD 或其他存储后端的原生对象。 */
    public static Session open() { return new Session(); }

    /** 机器只能登记物理批次和移交实物，不能取走任务资产或关闭整个操作。 */
    public static final class Writer {
        private final Session session;
        private Writer(Session session) { this.session = session; }
        public Batch prepare(List<Demand> demands) { return session.prepare(demands); }
        @Nullable public Batch current() { return session.current(); }
        public void activate() { if (current() != null) current().activate(); }
        public void finishBatch() { if (current() != null) current().close(); }
        public void unbindNetwork() { session.unbindNetwork(); }
        public void bindTo(CraftStorageEndpoint endpoint) {
            if (endpoint != null && endpoint.session() != null) endpoint.session().bindOutputReception(session);
        }
        public void hold(ItemStack actual) {
            session.checkThread();
            if (session.closed) throw new IllegalStateException("操作已关闭");
            if (actual == null || actual.isEmpty()) return;
            var key = ExperienceFluidNormalization.normalizeResource(resource(actual));
            if (current() == null) session.hold(key, actual.getCount());
            else current().collect(key, actual.getCount());
        }
    }

    public static final class Session implements AutoCloseable {
        public Writer writer() { return new Writer(this); }
        private final Thread owner = Thread.currentThread();
        private final Map<IntegrationResourceKey, Long> pending = new LinkedHashMap<>();
        private final Map<IntegrationResourceKey, Long> received = new LinkedHashMap<>();
        private Batch current;
        private boolean closed;
        private Object network;
        private BooleanSupplier networkValid;

        private Session() {}

        /** 后端将会话绑定到真实入库对象，不能使用坐标、包装器或玩家身份代替。 */
        public void bindNetwork(Object identity, BooleanSupplier valid) {
            checkThread();
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(valid, "valid");
            if (closed) throw new IllegalStateException("收货上下文已关闭");
            if (network == identity) {
                networkValid = valid;
                return;
            }
            unbindNetwork();
            network = identity;
            networkValid = valid;
            Map<Object, Waiters> networks = NETWORKS.get();
            if (networks == null) {
                networks = new IdentityHashMap<>();
                NETWORKS.set(networks);
            }
            Waiters waiters = networks.computeIfAbsent(identity, ignored -> new Waiters());
            waiters.sessions.add(this);
            waiters.sessionSnapshot = null;
            if (current != null) current.index();
        }

        public void unbindNetwork() {
            checkThread();
            if (network == null) return;
            if (current != null) current.unindex();
            Map<Object, Waiters> networks = NETWORKS.get();
            Waiters waiters = networkWaiters(network);
            if (waiters != null) {
                waiters.sessions.remove(this);
                waiters.sessionSnapshot = null;
                if (waiters.sessions.isEmpty()) networks.remove(network);
            }
            network = null;
            networkValid = null;
            if (networks != null && networks.isEmpty()) NETWORKS.remove();
        }

        private boolean valid() {
            checkThread();
            if (closed || network == null) return false;
            try {
                return networkValid.getAsBoolean();
            } catch (RuntimeException | LinkageError failure) {
                return false;
            }
        }

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
            resource = ExperienceFluidNormalization.normalizeResource(resource);
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
            List<ItemStack> result = stacks(pending);
            pending.clear();
            return result;
        }

        /** 仅供整批端口校核，不是一次新的实物移交。 */
        public List<ItemStack> cumulativeStacks() {
            checkThread();
            return stacks(received);
        }

        private static List<ItemStack> stacks(Map<IntegrationResourceKey, Long> resources) {
            List<ItemStack> result = new ArrayList<>();
            for (var entry : resources.entrySet()) {
                long left = entry.getValue();
                while (left > 0) {
                    int count = (int) Math.min(Integer.MAX_VALUE, left);
                    ItemStack stack = stack(entry.getKey(), count);
                    if (stack.isEmpty()) throw new IllegalStateException("已收资源无法转交: " + entry.getKey());
                    result.add(stack);
                    left -= count;
                }
            }
            return List.copyOf(result);
        }

        /** 关闭后不再接收，已经持有的资源仍可且必须被执行层移交或恢复。 */
        @Override public void close() {
            checkThread();
            if (closed) return;
            closed = true;
            if (current != null) current.close();
            unbindNetwork();
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
            index();
        }

        private void index() {
            if (state != State.ACTIVE || session.network == null) return;
            Waiters waiters = networkWaiters(session.network);
            for (int i = 0; i < demands.size(); i++) {
                if (received[i] < demands.get(i).max()) {
                    waiters.add(demands.get(i).resource(), this);
                }
            }
        }

        private void unindex() {
            if (session.network == null) return;
            Waiters waiters = networkWaiters(session.network);
            if (waiters == null) return;
            for (Demand demand : demands) {
                waiters.remove(demand.resource(), this);
            }
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

        public boolean closed() {
            session.checkThread();
            return state == State.CLOSED;
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
            return allocate(ExperienceFluidNormalization.normalizeResource(resource), amount, false, false);
        }

        private long receiveFromNetwork(IntegrationResourceKey resource, long amount, boolean simulate) {
            session.checkThread();
            if (state != State.ACTIVE) return 0;
            // 网络入口可以拒收，累计计数已满时将余量交还原存储路径。
            amount = Math.min(amount, Long.MAX_VALUE - session.received.getOrDefault(resource, 0L));
            amount = Math.min(amount, Long.MAX_VALUE - session.pending.getOrDefault(resource, 0L));
            return allocate(resource, amount, true, simulate);
        }

        private long allocate(IntegrationResourceKey resource, long amount, boolean network, boolean simulate) {
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
            if (simulate) return accepted;
            long held = network ? accepted : amount;
            if (held == 0) return 0;
            session.hold(resource, held);
            for (int i = 0; i < allocation.length; i++) received[i] += allocation[i];
            // 只移除已满足的资源，保留其他等待项原有的先后顺序。
            boolean remaining = false;
            for (int i = 0; i < demands.size(); i++) {
                if (resource.equals(demands.get(i).resource()) && received[i] < demands.get(i).max()) {
                    remaining = true;
                    break;
                }
            }
            if (!remaining && session.network != null) {
                Waiters waiters = networkWaiters(session.network);
                waiters.remove(resource, this);
            }
            return accepted;
        }

        @Override public void close() {
            session.checkThread();
            unindex();
            state = State.CLOSED;
        }
    }

    public static IntegrationResourceKey resource(ItemStack stack) {
        IntegrationResourceKey fluid = InkFluidSupport.resource(stack);
        return fluid != null ? ExperienceFluidNormalization.normalizeResource(fluid) : IntegrationResourceKey.item(stack);
    }

    public static IntegrationResourceKey resource(FluidStack stack) {
        return ExperienceFluidNormalization.normalizeResource(IntegrationResourceKey.fluid(stack));
    }

    private static ItemStack stack(IntegrationResourceKey resource, int amount) {
        if (resource.kind() == IntegrationResourceKey.ResourceKind.FLUID) return InkFluidSupport.tokenFor(resource, amount);
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(resource.id()), amount);
        if (!resource.identity().isEmpty()) stack.setTag(resource.identity());
        return stack;
    }
}
