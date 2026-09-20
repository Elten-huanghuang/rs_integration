package com.huanghuang.rsintegration.mods.pmmo;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PmmoSalvageRollerTest {
    private static final ResourceLocation INPUT = id("test:input");
    private static final ResourceLocation TARGET = id("test:target");
    private static final ResourceLocation EXTRA = id("test:extra");

    @Test
    void salvageBatchCapsSynchronousAttempts() {
        PmmoSalvageBatchDelegate delegate = new PmmoSalvageBatchDelegate();

        assertEquals(PmmoSalvageBatchDelegate.MAX_ATTEMPTS_PER_BATCH,
                delegate.prepareFlatBatch(Integer.MAX_VALUE));
        assertEquals(0, delegate.prepareFlatBatch(0));
    }

    @Test
    void countMeansIndependentSalvageAttemptsAndPreservesOtherOutputs() {
        PmmoSalvageDefinition definition = new PmmoSalvageDefinition(INPUT, List.of(
                output(TARGET, 2, 0.5, 0.9, Map.of("mining", 0.1),
                        Map.of(), Map.of("mining", 3L)),
                output(EXTRA, 1, 1.0, 1.0, Map.of(), Map.of(), Map.of())));
        Queue<Double> random = new ArrayDeque<>(List.of(
                0.1, 0.8, 0.0,
                0.6, 0.2, 0.0));

        PmmoSalvageRoller.Result result = PmmoSalvageRoller.roll(
                definition, TARGET, 2, skill -> "mining".equals(skill) ? 2 : 0,
                random::remove);

        assertEquals(2, result.attempts());
        assertEquals(3, result.outputs().get(TARGET));
        assertEquals(2, result.outputs().get(EXTRA));
        assertEquals(0, result.failedTargetAttempts());
        assertEquals(9L, result.xpAwards().get("mining"));
    }

    @Test
    void failedAttemptIsReportedWithoutFabricatingOutputOrXp() {
        PmmoSalvageDefinition definition = new PmmoSalvageDefinition(INPUT, List.of(
                output(TARGET, 1, 0.25, 0.25, Map.of(), Map.of(),
                        Map.of("smithing", 8L))));

        PmmoSalvageRoller.Result result = PmmoSalvageRoller.roll(
                definition, TARGET, 1, skill -> 0, () -> 0.9);

        assertTrue(result.outputs().isEmpty());
        assertTrue(result.xpAwards().isEmpty());
        assertEquals(1, result.failedTargetAttempts());
    }

    @Test
    void chanceAndRequirementsAreEvaluatedOncePerBatch() {
        PmmoSalvageDefinition definition = new PmmoSalvageDefinition(INPUT, List.of(
                output(TARGET, 1, 0.25, 0.75, Map.of("mining", 0.1),
                        Map.of("smithing", 10), Map.of())));
        java.util.concurrent.atomic.AtomicInteger levelReads =
                new java.util.concurrent.atomic.AtomicInteger();

        PmmoSalvageRoller.Result result = PmmoSalvageRoller.roll(
                definition, TARGET, 64, skill -> {
                    levelReads.incrementAndGet();
                    return "smithing".equals(skill) ? 10 : 2;
                }, () -> 1.0);

        assertEquals(64, result.attempts());
        assertEquals(64, result.failedTargetAttempts());
        assertEquals(2, levelReads.get());
    }

    @Test
    void missingEverySelectedTargetStillCompletesTheAttemptOrder() {
        PmmoSalvageBatchDelegate delegate = new PmmoSalvageBatchDelegate();

        delegate.acceptExecution(new PmmoSalvageRuntime.Execution(List.of(), 32, 32));

        assertTrue(delegate.isMachineCraftFinished(null, null));
    }

    @Test
    void requirementsAndChanceClampMatchPmmoRules() {
        PmmoSalvageDefinition.Output output = output(
                TARGET, 1, 0.2, 0.75, Map.of("mining", 0.1),
                Map.of("smithing", 10), Map.of());

        assertFalse(PmmoSalvageRoller.meetsRequirements(output, skill -> 9));
        assertTrue(PmmoSalvageRoller.meetsRequirements(output, skill -> 10));
        assertEquals(0.75, PmmoSalvageRoller.chance(output, skill -> 20));
    }

    @Test
    void eligibilityReportsEveryMissingLevelWithActualValues() {
        PmmoSalvageDefinition.Output output = output(
                TARGET, 1, 1, 1, Map.of(),
                Map.of("mining", 12, "smithing", 8), Map.of());

        PmmoSalvageRuntime.Eligibility result = PmmoSalvageRuntime.evaluate(
                output, Map.of("mining", 7, "smithing", 8));

        assertEquals(PmmoSalvageRuntime.EligibilityState.LEVEL_TOO_LOW, result.state());
        assertEquals(List.of(new PmmoSalvageRuntime.MissingLevel("mining", 12, 7)),
                result.missing());
    }

    private static PmmoSalvageDefinition.Output output(
            ResourceLocation id, int max, double base, double cap,
            Map<String, Double> chancePerLevel, Map<String, Integer> requirements,
            Map<String, Long> xp) {
        return new PmmoSalvageDefinition.Output(
                id, max, base, cap, chancePerLevel, requirements, xp);
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.tryParse(value);
    }
}
