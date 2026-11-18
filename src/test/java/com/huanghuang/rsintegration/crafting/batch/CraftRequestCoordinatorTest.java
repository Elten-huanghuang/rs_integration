package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.planning.PlanningProgressServer;
import com.huanghuang.rsintegration.crafting.planning.TypedPreviewAdmissionQueue;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CraftRequestCoordinatorTest extends BootstrapTest {
    @Test
    void cancelRemovesOnlyTheMatchingPreviewFromBothQueues() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerPlayer otherPlayer = mock(ServerPlayer.class);
        UUID playerId = UUID.randomUUID();
        UUID otherPlayerId = UUID.randomUUID();
        when(player.getUUID()).thenReturn(playerId);
        when(player.hasDisconnected()).thenReturn(true);
        when(otherPlayer.getUUID()).thenReturn(otherPlayerId);

        ResourceLocation recipeId = new ResourceLocation("rsi", "test");
        var requests = CraftPlanningRuntime.planRequests();
        var typedQueue = CraftPlanningRuntime.typedPreviewRequests();
        var admitted = new ArrayList<Long>();
        Consumer<ServerPlayer> action = ignored -> {};

        try {
            long cancelled = CraftRequestCoordinator.beginPreview(player, 1L, recipeId);
            long retained = CraftRequestCoordinator.beginPreview(player, 2L, recipeId);
            long other = CraftRequestCoordinator.beginPreview(otherPlayer, 1L, recipeId);
            assertNotEquals(0L, cancelled);
            assertNotEquals(0L, retained);
            assertNotEquals(0L, other);
            var retainedWarmUp = new DeferredCraftRequestQueue.Entry<>(playerId, true, retained, action);
            var otherWarmUp = new DeferredCraftRequestQueue.Entry<>(otherPlayerId, true, other, action);
            var warmUpExecution = new DeferredCraftRequestQueue.Entry<>(playerId, false, 0L, action);
            var queuedExecution = new DeferredCraftRequestQueue.Entry<>(playerId, false, 0L, action);

            assertTrue(CraftRequestQueues.offerWarmUp(
                    new DeferredCraftRequestQueue.Entry<>(playerId, true, cancelled, action)));
            assertTrue(CraftRequestQueues.offerWarmUp(retainedWarmUp));
            assertTrue(CraftRequestQueues.offerWarmUp(otherWarmUp));
            assertTrue(CraftRequestQueues.offerWarmUp(warmUpExecution));
            assertTrue(CraftRequestQueues.offerExecution(queuedExecution));
            enqueueTyped(typedQueue, playerId, cancelled, admitted);
            enqueueTyped(typedQueue, playerId, retained, admitted);
            enqueueTyped(typedQueue, otherPlayerId, other, admitted);

            CraftRequestCoordinator.cancelPlanning(player, 1L);

            assertFalse(requests.isCurrent(playerId, cancelled));
            assertTrue(requests.isCurrent(playerId, retained));
            assertTrue(requests.isCurrent(otherPlayerId, other));
            assertEquals(0L, PlanningProgressServer.generationForRequest(playerId, 1L));
            assertEquals(other, PlanningProgressServer.generationForRequest(otherPlayerId, 1L));
            assertSame(retainedWarmUp, CraftRequestQueues.pollWarmUp());
            assertSame(otherWarmUp, CraftRequestQueues.pollWarmUp());
            assertSame(warmUpExecution, CraftRequestQueues.pollWarmUp());
            assertNull(CraftRequestQueues.pollWarmUp());
            assertSame(queuedExecution, CraftRequestQueues.pollExecution());
            assertNull(CraftRequestQueues.pollExecution());
            typedQueue.run(3, System.nanoTime(), -1L, request -> true);
            assertEquals(List.of(retained, other), admitted);
        } finally {
            CraftRequestCoordinator.onPlayerLogout(playerId);
            CraftRequestCoordinator.onPlayerLogout(otherPlayerId);
        }
    }

    private static void enqueueTyped(TypedPreviewAdmissionQueue queue, UUID playerId,
                                     long generation, List<Long> admitted) {
        assertEquals(TypedPreviewAdmissionQueue.OfferResult.QUEUED, queue.offer(
                new TypedPreviewAdmissionQueue.Request(playerId, generation, System.nanoTime(),
                        () -> admitted.add(generation), () -> {}, ignored -> {})));
    }
}
