package com.huanghuang.rsintegration.crafting.progress;

import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeProgress;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeState;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.Reason;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CraftExecutionClockTest extends BootstrapTest {
    @Test void pollingAndChangingWaitReasonsDoNotRenewTheProgressClock() {
        var now = new AtomicLong(100L);
        var clock = new CraftExecutionClock(now::get);
        now.set(1_100L);
        assertEquals(new CraftProgressTiming(1_000L, 1_000L), clock.sample(
                List.of(node(NodeState.READY, Reason.MACHINE_BUSY, 0, 0)), CraftOutputProgress.UNKNOWN, false));
        now.set(61_100L);
        assertEquals(new CraftProgressTiming(61_000L, 61_000L), clock.sample(
                List.of(node(NodeState.BLOCKED, Reason.WAITING_MATERIALS, 0, 0)), CraftOutputProgress.UNKNOWN, false));
    }

    @Test void dispatchPartialOutputAndCompletionRenewTheClockButSteadyProcessingDoesNot() {
        var now = new AtomicLong();
        var clock = new CraftExecutionClock(now::get);
        var processing = List.of(node(NodeState.RUNNING, Reason.NONE, 0, 2));
        now.set(5_000L);
        assertEquals(0L, clock.sample(processing, CraftOutputProgress.UNKNOWN, false).sinceProgressMillis());
        now.set(15_000L);
        assertEquals(10_000L, clock.sample(processing, CraftOutputProgress.UNKNOWN, false).sinceProgressMillis());
        clock.markProgress();
        now.set(18_000L);
        assertEquals(3_000L, clock.sample(processing, CraftOutputProgress.UNKNOWN, false).sinceProgressMillis());
        now.set(19_000L);
        assertEquals(0L, clock.sample(processing, new CraftOutputProgress(1, 10), false).sinceProgressMillis());
        now.set(20_000L);
        assertEquals(0L, clock.sample(List.of(node(NodeState.RUNNING, Reason.NONE, 2, 0)),
                new CraftOutputProgress(1, 10), false).sinceProgressMillis());
    }

    @Test void terminalTimesAreFrozenAndMissingDetailsDoNotLookLikeNewWork() {
        var now = new AtomicLong();
        var clock = new CraftExecutionClock(now::get);
        var nodes = List.of(node(NodeState.RUNNING, Reason.NONE, 0, 1));
        clock.sample(nodes, CraftOutputProgress.UNKNOWN, false);
        now.set(1_000L);
        clock.sample(List.of(), CraftOutputProgress.UNKNOWN, false);
        now.set(2_000L);
        assertEquals(2_000L, clock.sample(nodes, CraftOutputProgress.UNKNOWN, false).sinceProgressMillis());
        var ended = clock.sample(nodes, CraftOutputProgress.UNKNOWN, true);
        now.set(90_000L);
        clock.markProgress();
        assertEquals(ended, clock.sample(nodes, CraftOutputProgress.UNKNOWN, true));
    }

    @Test void unknownTimingIsDistinctFromZeroAndInvalidDurationsAreRejected() {
        assertFalse(CraftProgressTiming.UNKNOWN.known());
        assertTrue(new CraftProgressTiming(0, 0).known());
        assertThrows(IllegalArgumentException.class, () -> new CraftProgressTiming(10, 11));
        assertThrows(IllegalArgumentException.class, () -> new CraftProgressTiming(-1, 0));
    }

    private static NodeProgress node(NodeState state, Reason reason, int completed, int running) {
        return new NodeProgress(1, state, "test:recipe", "generic", ItemStack.EMPTY,
                completed, 10, running, "", reason, "", false);
    }
}
