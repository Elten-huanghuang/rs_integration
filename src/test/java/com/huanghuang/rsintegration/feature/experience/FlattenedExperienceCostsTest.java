package com.huanghuang.rsintegration.feature.experience;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class FlattenedExperienceCostsTest extends BootstrapTest {
    @Test
    void anvilChargesTheSamePointsAtLowAndHighLevels() {
        for (int level : new int[]{3, 30, 100, 100_000_000, ExperiencePoints.MAX_LEVEL}) {
            assertEquals(27L, FlattenedExperienceCosts.points(level, 3, 0));
            assertEquals(7L, FlattenedExperienceCosts.points(level, 1, 0));
        }
    }

    @Test
    void enchantingUsesTheOptionRequirementRatherThanThePlayersLevel() {
        assertEquals(306L, FlattenedExperienceCosts.points(30, 3, 30));
        assertEquals(306L, FlattenedExperienceCosts.points(100, 3, 30));
        assertEquals(306L, FlattenedExperienceCosts.points(ExperiencePoints.MAX_LEVEL, 3, 30));
        assertEquals(99L, FlattenedExperienceCosts.points(100, 3, 15));
        assertEquals(27L, FlattenedExperienceCosts.points(100, 3, 0));
    }

    @Test
    void formulaMatchesTheReferenceLevelSumAcrossTheVanillaBreakpoints() {
        for (int level : new int[]{0, 1, 15, 16, 17, 30, 31, 32, 60, 100}) {
            for (int cost = 0; cost <= 40; cost++) {
                for (int requirement : new int[]{0, 15, 16, 30, 31, 60}) {
                    int start = Math.max(0, Math.min(level, requirement) - cost);
                    long expected = 0;
                    for (int i = start; i < start + cost; i++) {
                        expected += ExperiencePoints.toNextLevel(i);
                    }
                    assertEquals(expected, FlattenedExperienceCosts.points(level, cost, requirement));
                }
            }
        }
    }

    @Test
    void extremeInputsDoNotOverflowOrCreateNegativeCosts() {
        assertEquals(0L, FlattenedExperienceCosts.points(100, -1, 30));
        assertEquals(ExperiencePoints.forLevel(ExperiencePoints.MAX_LEVEL),
                FlattenedExperienceCosts.points(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertTrue(FlattenedExperienceCosts.points(ExperiencePoints.MAX_LEVEL, 2,
                ExperiencePoints.MAX_LEVEL) > Integer.MAX_VALUE);
    }

    @Test
    void chargePreservesPartialProgressAsPointsAndDoesNotChangeScore() {
        Player player = player(100, 0.5F);
        long before = ExperiencePoints.current(player);
        AtomicInteger calls = new AtomicInteger();
        FlattenedExperienceCosts.chargeLevels(player, -3, 0, change -> {
            calls.incrementAndGet();
            player.experienceLevel += change;
        });
        assertEquals(1, calls.get());
        assertEquals(27L, before - ExperiencePoints.current(player));
        verifyNoScoreAward(player);
    }

    @Test
    void canceledForgeLevelEventDoesNotChargeOrRewriteThePlayersState() {
        Player player = player(100, 0.5F);
        long before = ExperiencePoints.current(player);
        FlattenedExperienceCosts.chargeLevels(player, -3, 0, change -> {});
        assertEquals(before, ExperiencePoints.current(player));
        assertEquals(100, player.experienceLevel);
        assertEquals(0.5F, player.experienceProgress);
    }

    @Test
    void forgeAdjustedLevelCostIsRespected() {
        Player player = player(100, 0);
        long before = ExperiencePoints.current(player);
        FlattenedExperienceCosts.chargeLevels(player, -3, 0,
                change -> player.experienceLevel -= 1);
        assertEquals(7L, before - ExperiencePoints.current(player));
    }

    @Test
    void positiveAndZeroChangesKeepTheirOriginalBehavior() {
        Player player = player(40, 0.25F);
        FlattenedExperienceCosts.chargeLevels(player, 5, 0,
                change -> player.experienceLevel += change);
        assertEquals(45, player.experienceLevel);
        assertEquals(0.25F, player.experienceProgress);
        AtomicInteger calls = new AtomicInteger();
        FlattenedExperienceCosts.chargeLevels(player, 0, 0, change -> calls.incrementAndGet());
        assertEquals(1, calls.get());
    }

    @Test
    void unsupportedExperienceStateDelegatesWithoutTryingToConvertIt() {
        Player player = player(Integer.MAX_VALUE, Float.NaN);
        FlattenedExperienceCosts.chargeLevels(player, -3, 0,
                change -> player.experienceLevel += change);
        assertEquals(Integer.MAX_VALUE - 3, player.experienceLevel);
        assertTrue(Float.isNaN(player.experienceProgress));
    }

    @Test
    void hugeBalancesRemainLongAndTheClientIsExplicitlySynchronized() {
        ServerPlayer player = mock(ServerPlayer.class);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        player.experienceLevel = 100_000_000;
        player.experienceProgress = 0;
        player.totalExperience = Integer.MAX_VALUE;
        long before = ExperiencePoints.current(player);
        FlattenedExperienceCosts.chargeLevels(player, -3, 0,
                change -> player.experienceLevel += change);
        long after = ExperiencePoints.current(player);
        assertTrue(after < before);
        assertTrue(before - after < 128L, "高等级只允许 float 表示精度造成的向下取整");
        assertEquals(Integer.MAX_VALUE, player.totalExperience);
        verify(player.connection).send(any(ClientboundSetExperiencePacket.class));
    }

    @Test
    void repeatedChargesCannotIncreaseOrOverdrawTheExperienceBalance() {
        Player player = player(60, 0.5F);
        for (int i = 0; i < 200; i++) {
            long before = ExperiencePoints.current(player);
            FlattenedExperienceCosts.chargeLevels(player, -3, 0,
                    change -> player.experienceLevel = Math.max(0, player.experienceLevel + change));
            long after = ExperiencePoints.current(player);
            assertTrue(after >= 0 && after <= before);
            if (before > 27L) assertTrue(before - after >= 27L);
        }
    }

    private static Player player(int level, float progress) {
        Player player = mock(Player.class);
        player.experienceLevel = level;
        player.experienceProgress = progress;
        player.totalExperience = (int) Math.min(Integer.MAX_VALUE, ExperiencePoints.current(player));
        return player;
    }

    private static void verifyNoScoreAward(Player player) {
        verify(player, never()).increaseScore(anyInt());
    }
}
