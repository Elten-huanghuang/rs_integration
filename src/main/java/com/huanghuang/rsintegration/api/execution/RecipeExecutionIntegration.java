package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.MachineExecutionMode;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * 为无法用标准 I/O 安全表达的特殊机器提供完整执行生命周期。
 *
 * <p>只有 RecipeView 使用 {@code RecipeExecutionKind.CUSTOM} 且 MachineDescriptor
 * 使用 {@link MachineExecutionMode#CUSTOM} 时才需要该接口。实现必须按 operationId
 * 幂等，并且只能在服务端主线程访问世界。</p>
 */
public interface RecipeExecutionIntegration {
    /**
     * 返回稳定且带自身 namespace 的执行集成 ID。
     */
    ResourceLocation id();

    /**
     * 判断该执行器是否承接给定配方和机器。
     */
    boolean supports(RecipeView recipe, MachineContext machine);

    /**
     * 从当前 claim、预留材料和机器上下文生成真实执行计划。
     */
    ExecutionPlanResult createPlan(ExecutionRequestContext context);

    /**
     * 首次启动操作。等待机器时可返回 RETRY，不能把暂未就绪伪装成永久失败。
     */
    ExecutionResult start(OperationContext context, ExecutionPlan plan);

    /**
     * 服务端重启后恢复操作。
     *
     * <p>默认拒绝恢复。需要跨重启恢复的实现必须只从 {@link OperationSnapshot} 读取
     * 可序列化状态，并重新校验 live recipe、机器和 descriptor。</p>
     */
    default ExecutionResult resume(
            OperationContext context,
            ExecutionPlan plan,
            OperationSnapshot snapshot
    ) {
        return ExecutionResult.rejected(new IntegrationDiagnostic(
                IntegrationDiagnosticCode.EXECUTION_OPERATION_STALE,
                IntegrationSeverity.ERROR,
                id(),
                Map.of("reason", "resume_not_supported")
        ));
    }
}
