package com.huanghuang.rsintegration.feature.sidepanel;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
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
        RSIntegrationConfig.SERVER_SPEC.setConfig(CommentedConfig.inMemory());
        fullSync(8);
    }

    @AfterEach
    void end() {
        RSSidePanelClient.clearOnLogout();
        RSIntegrationConfig.SERVER_SPEC.setConfig(null);
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
        if (RSSidePanelClient.dataModel.tick(20, System.currentTimeMillis() + 10_000, 2_000)) requests.incrementAndGet();
    }

    private void acknowledge(boolean success) {
        SyncHandler.onOperationResult(new RSSidePanelOperationResultPacket(17, success, id, success ? 2 : 0,
                success ? RSSidePanelOperationResultPacket.ErrorCode.NONE
                        : RSSidePanelOperationResultPacket.ErrorCode.CURSOR_FULL));
    }

    private RSSidePanelSyncPacket chunk(long generation, int index, UUID slot, int count) {
        return new RSSidePanelSyncPacket(List.of(slot), List.of(new ItemStack(Items.STONE, count)),
                List.of(1L), List.of(false), 2, true, "test", index, 2, generation);
    }

    @Test void reorderedChunksAndLogoutDoNotApplyAnOldGeneration() {
        UUID second = UUID.randomUUID();
        SyncHandler.onSyncReceived(chunk(4, 1, second, 10));
        SyncHandler.onSyncReceived(chunk(3, 0, id, 99));
        assertEquals(8, entry().getCount());
        SyncHandler.onSyncReceived(chunk(4, 0, id, 12));
        assertEquals(12, entry().getCount());
        assertEquals(10, RSSidePanelClient.dataModel.getById(second).getCount());
        SyncHandler.onSyncReceived(chunk(5, 1, second, 33));
        RSSidePanelClient.clearOnLogout();
        SyncHandler.onSyncReceived(chunk(1, 0, id, 7));
        assertNull(entry());
        SyncHandler.onSyncReceived(chunk(1, 1, second, 8));
        assertEquals(7, entry().getCount());
        assertEquals(8, RSSidePanelClient.dataModel.getById(second).getCount());
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
