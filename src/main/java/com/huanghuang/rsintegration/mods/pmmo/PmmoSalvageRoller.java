package com.huanghuang.rsintegration.mods.pmmo;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.function.ToIntFunction;

/** Pure implementation of PMMO's independent salvage rolls. */
public final class PmmoSalvageRoller {
    private PmmoSalvageRoller() {}

    public record Result(Map<ResourceLocation, Integer> outputs,
                         int attempts, int failedTargetAttempts,
                         Map<String, Long> xpAwards) {
        public Result {
            outputs = Map.copyOf(outputs);
            xpAwards = Map.copyOf(xpAwards);
        }
    }

    public static Result roll(PmmoSalvageDefinition definition,
                              ResourceLocation targetOutput, int attempts,
                              ToIntFunction<String> skillLevel,
                              DoubleSupplier random) {
        Map<ResourceLocation, Integer> outputs = new LinkedHashMap<>();
        Map<String, Long> xpAwards = new LinkedHashMap<>();
        int failedTargets = 0;

        for (int attempt = 0; attempt < attempts; attempt++) {
            boolean targetProduced = false;
            for (PmmoSalvageDefinition.Output output : definition.outputs()) {
                if (!meetsRequirements(output, skillLevel)) continue;
                double chance = output.baseChance();
                for (Map.Entry<String, Double> entry : output.chancePerLevel().entrySet()) {
                    chance += entry.getValue() * skillLevel.applyAsInt(entry.getKey());
                }
                chance = Math.min(output.maxChance(), chance);
                for (int roll = 0; roll < output.salvageMax(); roll++) {
                    if (random.getAsDouble() >= chance) continue;
                    outputs.merge(output.outputId(), 1, Math::addExact);
                    if (output.outputId().equals(targetOutput)) targetProduced = true;
                    for (Map.Entry<String, Long> xp : output.xpAwards().entrySet()) {
                        xpAwards.merge(xp.getKey(), xp.getValue(), Math::addExact);
                    }
                }
            }
            if (!targetProduced) failedTargets++;
        }
        return new Result(outputs, attempts, failedTargets, xpAwards);
    }

    public static boolean meetsRequirements(PmmoSalvageDefinition.Output output,
                                            ToIntFunction<String> skillLevel) {
        for (Map.Entry<String, Integer> requirement : output.levelRequirements().entrySet()) {
            if (skillLevel.applyAsInt(requirement.getKey()) < requirement.getValue()) return false;
        }
        return true;
    }

    public static double chance(PmmoSalvageDefinition.Output output,
                                ToIntFunction<String> skillLevel) {
        double chance = output.baseChance();
        for (Map.Entry<String, Double> entry : output.chancePerLevel().entrySet()) {
            chance += entry.getValue() * skillLevel.applyAsInt(entry.getKey());
        }
        return Math.min(output.maxChance(), chance);
    }
}
