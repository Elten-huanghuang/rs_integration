package com.huanghuang.rsintegration.mods.embers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class EreAlchemyProgressSavedDataTest {
    @Test
    void progressSurvivesNbtRoundTripUntilExplicitlyRemoved() {
        UUID playerId = UUID.randomUUID();
        String recipeId = "embers:test_alchemy";
        EreAlchemyProgressSavedData data = new EreAlchemyProgressSavedData();
        data.putProgress(playerId, recipeId, new EreAlchemyProgressSavedData.Progress(
                List.of(new int[]{0, 1}, new int[]{1, 0}),
                new int[]{1, 0},
                7,
                2,
                2,
                2));

        CompoundTag serialized = data.save(new CompoundTag());
        EreAlchemyProgressSavedData restored = EreAlchemyProgressSavedData.load(serialized);
        EreAlchemyProgressSavedData.Progress progress = restored.getProgress(playerId, recipeId);

        assertNotNull(progress);
        assertEquals(7, progress.attemptCount());
        assertEquals(2, progress.consecutiveZeroBlackPins());
        assertEquals(2, progress.candidates().size());
        assertArrayEquals(new int[]{1, 0}, progress.currentGuess());

        restored.removeProgress(playerId, recipeId);
        assertNull(restored.getProgress(playerId, recipeId));
    }

    @Test
    void returnedProgressCannotMutateStoredState() {
        UUID playerId = UUID.randomUUID();
        EreAlchemyProgressSavedData data = new EreAlchemyProgressSavedData();
        data.putProgress(playerId, "embers:test", new EreAlchemyProgressSavedData.Progress(
                List.of(new int[]{0}), new int[]{0}, 1, 0, 1, 1));

        EreAlchemyProgressSavedData.Progress first = data.getProgress(playerId, "embers:test");
        assertNotNull(first);
        first.currentGuess()[0] = 99;
        first.candidates().get(0)[0] = 99;

        EreAlchemyProgressSavedData.Progress second = data.getProgress(playerId, "embers:test");
        assertNotNull(second);
        assertArrayEquals(new int[]{0}, second.currentGuess());
        assertArrayEquals(new int[]{0}, second.candidates().get(0));
    }
}
