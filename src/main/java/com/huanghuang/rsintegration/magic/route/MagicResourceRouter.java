package com.huanghuang.rsintegration.magic.route;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.magic.MagicResourceDiagnostics;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.magic.MagicResourceRouteCounters;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 资源到存储后端的路由。
 *
 * <p>台账里只放描述符；真正访问库存时才生成后端实例。解析规则按顺序是：
 * <ol>
 *   <li>候选为空 → {@code UNSUPPORTED}（显式失败，不按 0 处理）。</li>
 *   <li>有候选声明管辖当前范围 → 它即权威后端；可达则 {@code AVAILABLE}，
 *       不可达则 {@code UNREACHABLE}。priority 在这里不参与比较。</li>
 *   <li>没有候选声明管辖，且链路根本没有存储端点（纯原生路径）：只有一个候选时
 *       答案才确定（例如归墟盘上的 mana，与服务端挂载状态无关）；多个候选时无法
 *       判断该读哪一本账，判 {@code UNREACHABLE}，绝不聚合。</li>
 *   <li>有存储端点，但没有候选认识它（未接入的第三方后端）：说明缺少这个后端的
 *       映射，判 {@code AMBIGUOUS} 显式失败；只有配置里指名了后端才继续。</li>
 * </ol>
 *
 * <p>结果是纯函数：同样的 {@link MagicScope} 连续调用必然落到同一个后端描述符，
 * 所以预览与执行各调一次也不会分叉。</p>
 */
public final class MagicResourceRouter {
    private final Map<MagicResourceKind, List<MagicStorageBackendDescriptor>> byKind =
            new EnumMap<>(MagicResourceKind.class);
    private final List<MagicStorageBackendDescriptor> all = new ArrayList<>();
    private volatile AuthorityPolicy policy = AuthorityPolicy.SCOPE_ONLY;

    public synchronized void register(MagicStorageBackendDescriptor descriptor) {
        if (descriptor == null) return;
        all.removeIf(existing -> existing.id().equals(descriptor.id()));
        all.add(descriptor);
        for (MagicResourceKind kind : descriptor.supportedKinds()) {
            List<MagicStorageBackendDescriptor> list =
                    byKind.computeIfAbsent(kind, ignored -> new ArrayList<>());
            list.removeIf(existing -> existing.id().equals(descriptor.id()));
            list.add(descriptor);
            list.sort(Comparator.comparingInt(MagicStorageBackendDescriptor::priority)
                    .thenComparing(descriptor2 -> descriptor2.id().toString()));
        }
    }

    public void setPolicy(AuthorityPolicy next) {
        this.policy = next == null ? AuthorityPolicy.SCOPE_ONLY : next;
    }

    public AuthorityPolicy policy() {
        return policy;
    }

    /** 声明支持该资源的后端，按 priority 升序。 */
    public List<MagicStorageBackendDescriptor> candidates(MagicResourceKind kind) {
        return List.copyOf(byKind.getOrDefault(kind, List.of()));
    }

    public List<MagicStorageBackendDescriptor> descriptors() {
        return List.copyOf(all);
    }

    public BackendResolution resolve(MagicResourceKind kind, MagicScope scope) {
        List<MagicStorageBackendDescriptor> candidates = candidates(kind);
        if (candidates.isEmpty()) return BackendResolution.unsupported(candidates);

        // 1) 空间管辖决定权威后端；priority 在这一步不参与比较。
        for (MagicStorageBackendDescriptor descriptor : candidates) {
            if (!descriptor.ownsScope(scope)) continue;
            return descriptor.instantiate(scope).reachable(kind)
                    ? BackendResolution.available(descriptor, descriptor.channel(kind))
                    : BackendResolution.unreachable(descriptor, candidates);
        }

        // 2) 显式指名的后端优先于「没有空间归属」的兜底规则。注意它不能覆盖
        //    上一步的空间归属：玩家站在 RS 终端前时，配置不该把库存搬去 BD。
        MagicStorageBackendDescriptor pinned = pinnedCandidate(kind, candidates);
        if (pinned != null) {
            return BackendResolution.available(pinned, pinned.channel(kind));
        }

        if (scope.backendId() == null) {
            // 3) 链路没有存储端点。唯一候选时答案才确定（例如归墟盘上的 mana，
            //    与具体网络无关）；两种以上说明存在两本互不相干的账，不聚合，
            //    也不按 priority 偷选。
            if (candidates.size() == 1) {
                MagicStorageBackendDescriptor only = candidates.get(0);
                return only.instantiate(scope).reachable(kind)
                        ? BackendResolution.available(only, only.channel(kind))
                        : BackendResolution.unreachable(only, candidates);
            }
            return BackendResolution.unreachable(null, candidates);
        }

        // 4) 有端点，但没有候选声明管辖它：缺少这个后端的映射，显式失败。
        return BackendResolution.ambiguous(candidates);
    }

    /**
     * 带计数的解析：先算，再记一次计数器，最后返回同一个结果。
     *
     * <p>计数不参与判定，{@link #resolve} 依旧是纯函数；{@code record=false}
     * 供诊断命令使用，避免「看一眼」把计数器刷满。</p>
     */
    public BackendResolution resolveCounted(MagicResourceKind kind, MagicScope scope,
                                            boolean record) {
        BackendResolution resolution = resolve(kind, scope);
        if (record) {
            MagicResourceRouteCounters.record(kind, MagicResourceDiagnostics.linkName(scope),
                    resolution);
        }
        return resolution;
    }

    @Nullable
    private MagicStorageBackendDescriptor pinnedCandidate(
            MagicResourceKind kind, List<MagicStorageBackendDescriptor> candidates) {
        ResourceLocation pinned = policy.pinnedBackendId(kind);
        if (pinned == null) return null;
        for (MagicStorageBackendDescriptor descriptor : candidates) {
            if (descriptor.id().equals(pinned)) return descriptor;
        }
        // 配置指名的后端没有注册：不回退到其他后端，让 resolve 继续走显式失败分支。
        return null;
    }

    /** 启动时打印一次路由表；只读声明，不触碰任何库存或原生 API。 */
    public void logRouteTable() {
        RSIntegrationMod.LOGGER.info("[RSI-Magic] 资源路由表：");
        for (MagicResourceKind kind : MagicResourceKind.values()) {
            List<MagicStorageBackendDescriptor> candidates = candidates(kind);
            if (candidates.isEmpty()) {
                RSIntegrationMod.LOGGER.info(
                        "[RSI-Magic]   kind={} backends=[] （当前安装组合下无后端）", kind.id());
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (MagicStorageBackendDescriptor descriptor : candidates) {
                if (line.length() > 0) line.append(", ");
                line.append(descriptor.id()).append('(').append(descriptor.priority())
                        .append(", channel=").append(descriptor.channel(kind)).append(')');
            }
            RSIntegrationMod.LOGGER.info("[RSI-Magic]   kind={} backends=[{}]", kind.id(), line);
        }
        RSIntegrationMod.LOGGER.info(
                "[RSI-Magic] SOUL 空间管辖：权威后端由链路端点决定，priority 仅在全局池模式兜底");
    }
}
