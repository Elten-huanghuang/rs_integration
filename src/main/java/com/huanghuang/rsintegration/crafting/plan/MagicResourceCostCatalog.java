package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.magic.MagicResourceCost;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 把已支持机器的配方费用统一投影成不可变魔法资源费用。
 *
 * <p>只有灵魂进入 RSI 递归供能：Mana 和 Source 已退回各自原生网络（Botania 火花
 * 网络 / Ars 的 Source 网络），RSI 不维护第二套递归供能账，所以这里不投影它们。
 * 统一盘里这两种资源的库存只保留手动充放、展示和旧数据兼容。</p>
 */
public final class MagicResourceCostCatalog {
    private MagicResourceCostCatalog() {}

    public static List<MagicResourceCost> costsFor(@Nullable Recipe<?> recipe) {
        if (recipe == null) return List.of();
        List<MagicResourceCost> costs = new ArrayList<>(1);
        add(costs, MagicResourceKind.SOUL, PlanWarnings.goetyRitualSoulCost(recipe));
        return MagicResourceCost.merge(costs);
    }

    private static void add(List<MagicResourceCost> costs,
                            MagicResourceKind kind, int amount) {
        if (amount > 0) costs.add(new MagicResourceCost(kind, amount));
    }
}
