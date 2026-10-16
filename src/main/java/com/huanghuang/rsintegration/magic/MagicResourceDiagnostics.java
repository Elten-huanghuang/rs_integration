package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.magic.route.BackendResolution;
import com.huanghuang.rsintegration.magic.route.MagicScope;

import javax.annotation.Nullable;

/**
 * 路由结果的人话描述。
 *
 * <p>描述过程不访问任何库存：{@link BackendResolution} 是纯解析结果，所以诊断命令
 * 和日志可以随时调用，不会干扰正在执行的任务。</p>
 */
public final class MagicResourceDiagnostics {
    private MagicResourceDiagnostics() {}

    /** 一次路由结果的单行描述，形如 {@code 资源=goety:soul 链路=refinedstorage 权威后端=rs_integration:disk 状态=AVAILABLE 通道=DISK_MOUNTED}。 */
    public static String describeRoute(MagicScope scope, BackendResolution resolution) {
        return "资源=" + resolution.kind().name()
                + " 链路=" + linkName(scope)
                + " 权威后端=" + backendName(resolution)
                + " 状态=" + resolution.kind()
                + " 通道=" + resolution.channel()
                + " 候选=" + resolution.candidates().size();
    }

    /** 链路端点声明的后端名；没有端点时给出「无端点」。 */
    public static String linkName(@Nullable MagicScope scope) {
        if (scope == null) return "无端点";
        String name = scope.backendName();
        return name == null || name.isEmpty() ? "无端点" : name;
    }

    private static String backendName(BackendResolution resolution) {
        return resolution.backend() == null
                ? "无" : resolution.backend().id().toString();
    }
}
