package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.api.execution.RecipeExecutionIntegration;
import com.huanghuang.rsintegration.api.execution.ExecutionIntegrationRegistry;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.machine.MachineIntegrationRegistry;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegrationRegistry;

import java.util.Optional;

/**
 * Integration API 的公共入口。
 *
 * <p>第三方应在 Mod 总线的 {@code FMLCommonSetupEvent} 中注册配方、机器和自定义
 * 执行器。注册在加载完成时冻结，运行期不能动态追加。所有注册方法都返回
 * {@link RegistrationResult}，调用方必须检查结果。</p>
 *
 * <p>该入口只在 RSI 已加载时可用。可选依赖 RSI 的模组应隔离直接引用本接口的类，
 * 避免在 RSI 缺失时提前加载。</p>
 */
public interface RSIIntegrationApi {
    int API_MAJOR = 1;
    int API_MINOR = 1;

    /**
     * 返回当前公共 API 版本。
     */
    ApiVersion version();

    /**
     * 返回当前注册阶段。
     */
    RegistrationPhase phase();

    /**
     * 返回只读配方集成查询面。
     */
    RecipeIntegrationRegistry recipes();

    /**
     * 返回只读机器集成查询面。
     */
    MachineIntegrationRegistry machines();

    /**
     * 返回只读执行集成查询面。
     */
    ExecutionIntegrationRegistry executions();

    /**
     * 注册一个配方集成。
     *
     * <p>相同 ID 已被注册时返回失败。多个集成匹配同一配方时按 priority 选择，
     * 最高 priority 并列会被视为 ambiguous。</p>
     */
    RegistrationResult registerRecipe(
            RecipeIntegration<?> integration,
            RegistrationOptions options
    );

    /**
     * 注册一个机器集成。
     *
     * <p>机器集成至少需要声明一个真实可绑定的方块或方块类。</p>
     */
    RegistrationResult registerMachine(
            MachineIntegration integration,
            RegistrationOptions options
    );

    /**
     * 注册一个自定义执行器。
     *
     * <p>只有 {@code RecipeExecutionKind.CUSTOM} 配方需要调用此方法。</p>
     */
    RegistrationResult registerExecution(
            RecipeExecutionIntegration integration,
            RegistrationOptions options
    );

    default RegistrationResult registerRecipe(RecipeIntegration<?> integration) {
        return registerRecipe(integration, RegistrationOptions.defaults());
    }

    default RegistrationResult registerMachine(MachineIntegration integration) {
        return registerMachine(integration, RegistrationOptions.defaults());
    }

    default RegistrationResult registerExecution(RecipeExecutionIntegration integration) {
        return registerExecution(integration, RegistrationOptions.defaults());
    }

    static Optional<RSIIntegrationApi> get() {
        return RSIIntegrationServices.current();
    }
}
