package com.huanghuang.rsintegration.crafting.progress;

import com.huanghuang.rsintegration.crafting.plan.PlanBackgroundClient;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.batch.CraftStartedPacket;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionMonitorClientTest extends BootstrapTest {
    @BeforeEach @AfterEach void clear() { PlanBackgroundClient.clear(); CraftProgressTracker.clear(); }

    @Test void unsubmittedRsPlanRoutesToItsNativeNetworkWithoutActiveCraft() {
        var plan = mock(PlanResponse.class);
        var reference = new StorageReference(new StorageBackendId("refinedstorage"), "v1|minecraft:overworld@0,64,0");
        when(plan.storageReference()).thenReturn(reference);
        PlanBackgroundClient.remember(12, plan);
        var target = ExecutionMonitorClient.target();
        assertFalse(target.generic());
        assertEquals(reference, target.network());
        assertNull(target.craftId());
    }

    @Test void bdPlanKeepsGenericMonitorEvenWhenOlderRsPlanExists() {
        var older = mock(PlanResponse.class);
        when(older.storageReference()).thenReturn(new StorageReference(new StorageBackendId("refinedstorage"), "rs"));
        var latest = mock(PlanResponse.class);
        when(latest.storageReference()).thenReturn(new StorageReference(new StorageBackendId("beyonddimensions"), "1"));
        PlanBackgroundClient.remember(1, older);
        PlanBackgroundClient.remember(2, latest);
        assertTrue(ExecutionMonitorClient.target().generic());
    }

    @Test void recoveredCraftWithoutCachedPreviewStillRoutesByServerOwnedCraftIdentity() {
        UUID craft = UUID.randomUUID();
        CraftProgressTracker.onStarted(new CraftStartedPacket(craft, 8, true));
        assertEquals(craft, ExecutionMonitorClient.target().craftId());
        assertNull(ExecutionMonitorClient.target().network());
        CraftProgressTracker.onProgress(new CraftProgressSnapshot(craft, 1, CraftProgressSnapshot.Result.SUCCEEDED,
                CraftProgressSnapshot.Reason.NONE, 8, 8, 0, null));
        assertNull(ExecutionMonitorClient.target().craftId());
    }
}
