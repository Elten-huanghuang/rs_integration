package com.huanghuang.rsintegration.mods.ironfurnaces;

import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;

import java.util.Comparator;
import java.util.Locale;

/** 稳定地按更多熔炉实际等级排序，保证优先使用更快的机器。 */
public final class IronFurnaceMachinePriority {

    private IronFurnaceMachinePriority() {}

    public static Comparator<BoundMachine> comparator() {
        return Comparator.comparingInt((BoundMachine machine) -> priority(machine.blockKey()))
                .reversed()
                .thenComparing(machine -> machine.dim().toString())
                .thenComparing(machine -> machine.pos().asLong());
    }

    static int priority(String blockKey) {
        if (blockKey == null) return 0;
        String key = blockKey.toLowerCase(Locale.ROOT);
        if (!key.startsWith("ironfurnaces_")) return 0;
        if (key.contains("rainbow")) return 10_000;
        if (key.contains("allthemodium")) return 9_000;
        if (key.contains("vibranium")) return 9_500;
        if (key.contains("unobtainium")) return 9_600;
        if (key.contains("million")) return 9_700;
        if (key.contains("obsidian")) return 7_500;
        if (key.contains("silver")) return 3_500;
        if (key.contains("netherite")) return 8_000;
        if (key.contains("diamond")) return 7_000;
        if (key.contains("emerald")) return 6_000;
        if (key.contains("gold")) return 5_000;
        if (key.contains("iron")) return 4_000;
        if (key.contains("copper")) return 3_000;
        if (key.contains("crystal")) return 2_000;
        if (key.contains("stone")) return 1_000;
        return 100;
    }
}
