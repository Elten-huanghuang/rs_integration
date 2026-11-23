package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.crafting.plan.PlanExtensionState;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** 客户端原生合成计划的只读展示扩展。 */
public final class RSIPlanExtensions {
    private RSIPlanExtensions() {}

    /** 在客户端初始化时按模组命名空间注册；同一 ID 只接受一次。 */
    public static boolean register(ResourceLocation id, Provider provider) {
        return PlanExtensionState.register(id, provider);
    }

    @FunctionalInterface
    public interface Provider {
        /** 在客户端主线程调用。可从任意线程回传完整展示内容；过期快照的回传会被忽略。 */
        void onPlan(Context context, Consumer<Decoration> publish);
    }

    public enum Source { INVENTORY, STORAGE_NETWORK, BACKPACKS }

    public record Network(String backendId, String networkId) {}

    /** 库存变化只更新 inventoryRevision；切换计划则生成新的 planId。
     * complete 为 false 时，仅包含能确认的外部材料。 */
    public record Context(UUID planId, long inventoryRevision, ResourceLocation recipeId, int craftCount,
                          Optional<Network> network, Set<Source> enabledSources,
                          List<Material> initialMaterials, boolean complete, List<SourceAllocation> sourceAllocations) {
        public Context(UUID planId, long inventoryRevision, ResourceLocation recipeId, int craftCount,
                       Optional<Network> network, Set<Source> enabledSources,
                       List<Material> initialMaterials, boolean complete) {
            this(planId, inventoryRevision, recipeId, craftCount, network, enabledSources, initialMaterials,
                    complete, List.of());
        }

        public Context {
            enabledSources = Set.copyOf(enabledSources);
            initialMaterials = List.copyOf(initialMaterials);
            sourceAllocations = List.copyOf(sourceAllocations);
        }
    }

    /** 任务外部需求，包括缺料。 */
    public record Material(IntegrationResourceKey key, long needed, long available, long missing) {}

    /** 已分配给注册材料源的外部需求，按物品身份和来源 ID 汇总。 */
    public record SourceAllocation(IntegrationResourceKey key, ResourceLocation sourceId, long count) {}

    public record MaterialNote(String note, List<String> tooltipLines) {
        public MaterialNote {
            tooltipLines = List.copyOf(tooltipLines);
        }
    }

    public record Decoration(List<String> summaryLines, Map<IntegrationResourceKey, MaterialNote> materials) {
        public Decoration {
            summaryLines = List.copyOf(summaryLines);
            materials = Map.copyOf(materials);
        }
    }
}
