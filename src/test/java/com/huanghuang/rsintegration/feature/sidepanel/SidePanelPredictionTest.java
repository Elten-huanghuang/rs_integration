package com.huanghuang.rsintegration.feature.sidepanel;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SidePanelPredictionTest extends BootstrapTest {
    private final UUID id = UUID.randomUUID();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() {
        fullSync(8);
    }

    @AfterEach
    void end() {
        RSSidePanelClient.clearOnLogout();
    }

    private void fullSync(int count) {
        SyncHandler.onSyncReceived(new RSSidePanelSyncPacket(List.of(id),
                List.of(new ItemStack(Items.STONE, count)), List.of(42L), List.of(true),
                1, true, "test"));
    }

    private PanelStack entry() {
        return RSSidePanelClient.dataModel.getById(id);
    }

    private void extract(int count) {
        SidePanelMouseHandler.predictExtraction(17L, entry(), count);
    }

    private void timeout() {
        SidePanelTickManager.timeoutStaleExtractions(System.currentTimeMillis() + 10_000, 2_000,
                requests::incrementAndGet);
    }

    private void acknowledge(boolean success) {
        SyncHandler.onOperationResult(new RSSidePanelOperationResultPacket(17, success, id, success ? 2 : 0,
                success ? RSSidePanelOperationResultPacket.ErrorCode.NONE
                        : RSSidePanelOperationResultPacket.ErrorCode.CURSOR_FULL));
    }

    @Test
    void successWaitsForAuthoritativeQuantity() {
        extract(3);
        assertEquals(5, entry().getCount());
        acknowledge(true);
        assertEquals(5, entry().getCount());
        SyncHandler.onDeltaReceived(id, new ItemStack(Items.STONE, 6), 100, false);
        timeout();
        assertEquals(0, requests.get());
        assertEquals(6, entry().getCount());
        assertEquals(100, entry().timestamp);
        assertFalse(entry().craftable);
    }

    @Test
    void failureWithoutDeltaRequestsConfirmationBeforeRestoringSnapshot() {
        extract(8);
        acknowledge(false);
        assertEquals(0, entry().getCount());
        assertFalse(entry().getStack().isEmpty());
        timeout();
        assertEquals(1, requests.get());
        assertEquals(0, entry().getCount());
        timeout();
        assertEquals(8, entry().getCount());
        assertEquals(42, entry().timestamp);
        assertTrue(entry().craftable);
        timeout();
        assertEquals(1, requests.get());
    }

    @Test
    void fullSyncSupersedesPendingRecoveryAndLateReceipt() {
        extract(3);
        timeout();
        fullSync(12);
        acknowledge(false);
        timeout();
        timeout();
        assertEquals(12, entry().getCount());
        assertEquals(1, requests.get());
    }
}
