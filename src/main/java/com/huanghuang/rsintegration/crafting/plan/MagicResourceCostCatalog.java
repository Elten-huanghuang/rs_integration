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
 * <p>Botania Mana 与 Goety 灵魂可以由共振方块绑定的 RS 网络承担；Ars Source
 * 继续使用原生 Source 网络，避免在缺少对应原生入口时吞掉能量。</p>
 */
public final class MagicResourceCostCatalog {
    private MagicResourceCostCatalog() {}

    public static List<MagicResourceCost> costsFor(@Nullable Recipe<?> recipe) {
        if (recipe == null) return List.of();
        List<MagicResourceCost> costs = new ArrayList<>(1);
        add(costs, MagicResourceKind.MANA, PlanWarnings.botaniaManaCost(recipe));
        add(costs, MagicResourceKind.SOUL, PlanWarnings.goetyRitualSoulCost(recipe));
        return MagicResourceCost.merge(costs);
    }

    private static void add(List<MagicResourceCost> costs,
                            MagicResourceKind kind, int amount) {
        if (amount > 0) costs.add(new MagicResourceCost(kind, amount));
    }
}
