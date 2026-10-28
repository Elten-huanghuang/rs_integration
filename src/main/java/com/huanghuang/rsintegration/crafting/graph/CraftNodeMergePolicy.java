package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 根据配方处理器声明的即时批次契约，判断同类图节点能否汇总。
 */
public final class CraftNodeMergePolicy {
    /**
     * 没有方块实体、执行一次即出结果的机器类型。
     *
     * <p>这类机器由 {@code VanillaMachineBatchDelegate} 按整批一次性结算（最多 64 次），
     * 图里若把它们按件拆成十几个单件节点，每个节点都要单独排一次绑定的机器，
     * 结果就是「每 1~3 秒才切一刀」。合并成一个节点后一次派发就能切完整批。
     */
    private static final Set<String> INSTANT_STATELESS_MACHINES =
            Set.of("vanilla_stonecutter", "smithing");

    private CraftNodeMergePolicy() {}

    public static Predicate<CraftNode> forLevel(Level level) {
        Objects.requireNonNull(level, "level");
        Map<ResourceLocation, Boolean> decisions = new HashMap<>();
        return node -> decisions.computeIfAbsent(node.recipeId(),
                ignored -> canMerge(level, node));
    }

    private static boolean canMerge(Level level, CraftNode node) {
        if (ModType.GENERIC.id().equals(node.modTypeId())) return true;
        // 无状态即时机器不需要读配方处理器：它们的批次契约就是「一次算完整批」。
        if (INSTANT_STATELESS_MACHINES.contains(node.modTypeId())) return true;
        Recipe<?> recipe = level.getRecipeManager().byKey(node.recipeId()).orElse(null);
        if (recipe == null) return false;
        ModRecipeHandler handler = ModRecipeHandlers.claimFor(recipe).winner();
        if (handler == null || !handler.modType(recipe).id().equals(node.modTypeId())) return false;
        return handler.instantRecipePolicy(recipe).enabled();
    }
}
