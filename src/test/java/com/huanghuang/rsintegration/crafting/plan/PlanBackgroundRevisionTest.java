package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.planning.PlanningProgressTracker;
import com.huanghuang.rsintegration.crafting.planning.PlanningTasksScreen;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PlanBackgroundRevisionTest extends BootstrapTest {
    private static final ResourceLocation RECIPE = new ResourceLocation("test", "same_recipe");

    @BeforeEach @AfterEach void clear() {
        PlanBackgroundClient.clear();
        PlanningProgressTracker.clear();
    }

    @Test void hundredsOfQuantityEditsKeepOneLatestEntryAndDoNotEvictIndependentPlan() {
        var independent = mock(PlanResponse.class);
        var latest = mock(PlanResponse.class);
        PlanBackgroundClient.remember(1, independent);
        PlanBackgroundClient.remember(2, latest);
        for (long request = 3; request < 200; request++) {
            PlanBackgroundClient.replaceRevision(request - 1, request);
            PlanningProgressTracker.start(request, RECIPE);
            assertEquals(2, PlanningTasksScreen.taskCount());
            PlanningProgressTracker.responseReceived(request);
            PlanBackgroundClient.remember(request, latest);
        }
        assertEquals(List.of(199L, 1L), List.copyOf(PlanBackgroundClient.completedPlans().keySet()));
        assertSame(independent, PlanBackgroundClient.completedPlans().get(1L));
        assertEquals(2, PlanningTasksScreen.taskCount());
        assertFalse(PlanningProgressTracker.acceptsResponse(198));
    }

    @Test void replacingPendingRequestRemovesCancelledRowAndCannotCaptureLateResponse() {
        PlanningProgressTracker.start(10, RECIPE);
        PlanBackgroundClient.detach(10);
        PlanningProgressTracker.cancel(10);
        PlanBackgroundClient.replaceRevision(10, 11);
        PlanningProgressTracker.start(11, RECIPE);
        assertEquals(List.of(11L), PlanningProgressTracker.tasks().stream().map(task -> task.requestId()).toList());
        assertFalse(PlanningProgressTracker.acceptsResponse(10));
        assertFalse(PlanBackgroundClient.capture(10, mock(PlanResponse.class)));
        PlanBackgroundClient.detach(11);
        assertTrue(PlanBackgroundClient.capture(11, mock(PlanResponse.class)));
        PlanningProgressTracker.responseReceived(11);
        assertEquals(1, PlanningTasksScreen.taskCount());
    }

    @Test void lateCompletionDoesNotMoveAnOlderIndependentPlanAheadOfNewerRequests() {
        var plan = mock(PlanResponse.class);
        PlanBackgroundClient.remember(30, plan);
        PlanBackgroundClient.remember(10, plan);
        PlanBackgroundClient.remember(20, plan);
        assertEquals(List.of(30L, 20L, 10L), List.copyOf(PlanBackgroundClient.completedPlans().keySet()));
    }

    @Test void dismissingAnUnconfirmedPlanRemovesOnlyThatCachedPreview() {
        var first = mock(PlanResponse.class);
        var second = mock(PlanResponse.class);
        PlanBackgroundClient.remember(40, first);
        PlanBackgroundClient.remember(41, second);

        assertTrue(PlanBackgroundClient.dismiss(40));
        assertFalse(PlanBackgroundClient.dismiss(40));
        assertEquals(List.of(41L), List.copyOf(PlanBackgroundClient.completedPlans().keySet()));
        assertSame(second, PlanBackgroundClient.completedPlans().get(41L));
    }
}
