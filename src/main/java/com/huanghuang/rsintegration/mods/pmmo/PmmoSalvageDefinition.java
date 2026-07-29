package com.huanghuang.rsintegration.mods.pmmo;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;

/** Immutable projection of one PMMO item's complete salvage table. */
public record PmmoSalvageDefinition(ResourceLocation inputId, List<Output> outputs) {
    public PmmoSalvageDefinition {
        outputs = List.copyOf(outputs);
    }

    public record Output(ResourceLocation outputId, int salvageMax,
                         double baseChance, double maxChance,
                         Map<String, Double> chancePerLevel,
                         Map<String, Integer> levelRequirements,
                         Map<String, Long> xpAwards) {
        public Output {
            chancePerLevel = Map.copyOf(chancePerLevel);
            levelRequirements = Map.copyOf(levelRequirements);
            xpAwards = Map.copyOf(xpAwards);
        }
    }
}
