package com.huanghuang.rsintegration.api.recipe;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.util.Optional;

/**
 * 把一种真实配方投影为 RSI 可递归规划的材料和输出。
 *
 * <p>一个集成只能 claim 自己能完整解释的配方类型。{@link #inspect} 返回的
 * {@link RecipeView} 必须使用真实 recipe ID、真实数量、真实可选材料和真实产物，
 * 不得保存 live recipe、Ingredient、BlockEntity 或其他可变世界对象。</p>
 *
 * @param <R> 该集成支持的配方类型
 */
public interface RecipeIntegration<R extends Recipe<?>> {
    /**
     * 返回稳定且带自身 namespace 的集成 ID。
     */
    ResourceLocation id();

    /**
     * 判断该集成是否 claim 给定配方。
     *
     * <p>该方法可能处于配方扫描路径，只能读取配方类型或不可变字段，不得访问世界。</p>
     */
    boolean supports(Recipe<?> recipe);

    /**
     * 把 live recipe 投影为不可变配方视图。
     *
     * <p>默认在服务端主线程执行。无法投影时应返回带稳定诊断的 rejected 结果。</p>
     */
    RecipeInspectionResult inspect(RecipeContext context, R recipe);

    /**
     * 声明投影线程合同。只有完全无世界访问且无共享可变状态时才能返回
     * {@link RecipeProjectionThread#BACKGROUND_SAFE}。
     */
    default RecipeProjectionThread projectionThread() {
        return RecipeProjectionThread.SERVER_MAIN;
    }

    /**
     * 声明该配方由 RSI 标准 I/O 执行、自定义执行或仅用于展示。
     */
    default RecipeExecutionKind executionKind() {
        return RecipeExecutionKind.NONE;
    }

    /**
     * 返回可选的动态可用性检查。
     *
     * <p>检查在服务端主线程执行，可用于玩家解锁状态、权限或研究进度。</p>
     */
    default Optional<RecipeAvailabilityProvider> availabilityProvider() {
        return Optional.empty();
    }

    /**
     * 将 JEI 展示 recipe 解析为可被服务端 RecipeManager 找到的真实配方。
     *
     * <p>用于目标模组先构造 JEI 专用展示记录、随后才映射到运行期配方的场景。
     * {@code displayed} 是 JEI 输入槽中最先出现的一个实际展示物品，可能为空。
     * 实现应根据展示记录自己的稳定 ID 查找 live recipe，不能依赖槽位顺序猜测
     * recipe ID。API 未声明或不支持时必须返回空。</p>
     */
    default Optional<Recipe<?>> resolveJeiRecipe(
            Object jeiRecipe,
            ItemStack displayed,
            RegistryAccess registries
    ) {
        return Optional.empty();
    }
}
