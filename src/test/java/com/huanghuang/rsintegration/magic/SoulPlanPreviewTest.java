package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.SessionCraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 预览口径的唯一入口：未知必须表达为「不可达」，不能退化成 0。
 */
class SoulPlanPreviewTest extends BootstrapTest {
    @Test
    void unknownEndpointIsUnreachableNotZero() {
        ServerPlayer player = mock(ServerPlayer.class);

        SoulPlanPreview.Estimate estimate = SoulPlanPreview.estimate(player, null, 500);

        assertEquals(SoulPlanPreview.SourceKind.UNREACHABLE, estimate.source());
        assertFalse(estimate.reachable());
        assertTrue(estimate.authoritative().isEmpty());
        assertEquals(-1, estimate.legacyAvailable());
        assertEquals(0, estimate.backendShare());
        assertEquals(500, estimate.fallbackShare());
    }

    @Test
    void unreachableEstimateCountsAsInsufficientForPreparation() {
        // 预览显示不可达时，执行侧必须按「一个都没有」判缺料，而不是当成免费。
        assertEquals(0, new SoulPlanPreview.Estimate(300,
                java.util.OptionalInt.empty(), SoulPlanPreview.SourceKind.UNREACHABLE)
                .authoritativeOrZero());
    }

    @Test
    void sharesSplitTotalByAuthoritativeAmount() {
        SoulPlanPreview.Estimate estimate = new SoulPlanPreview.Estimate(1_000,
                java.util.OptionalInt.of(400), SoulPlanPreview.SourceKind.RS_DISK);

        assertEquals(400, estimate.backendShare());
        assertEquals(600, estimate.fallbackShare());
        assertEquals(400, estimate.legacyAvailable());
        assertTrue(estimate.reachable());
    }

    @Test
    void sharesNeverExceedTotal() {
        SoulPlanPreview.Estimate estimate = new SoulPlanPreview.Estimate(100,
                java.util.OptionalInt.of(9_999), SoulPlanPreview.SourceKind.BD_NETWORK);

        assertEquals(100, estimate.backendShare());
        assertEquals(0, estimate.fallbackShare());
    }

    @Test
    void endpointBackendIdDecidesTheSource() {
        ServerPlayer player = mock(ServerPlayer.class);
        assertEquals(SoulPlanPreview.SourceKind.BD_NETWORK,
                SoulPlanPreview.estimate(player, endpoint("beyonddimensions"), 10).source());
        // RS 端点在没有 UnifiedDiskManager 时是「读不出来」，不是 0。
        SoulPlanPreview.Estimate rs = SoulPlanPreview.estimate(player, endpoint("refinedstorage"), 10);
        assertFalse(rs.reachable());
    }

    private static CraftStorageEndpoint endpoint(String backend) {
        StorageSession session = mock(StorageSession.class);
        when(session.reference()).thenReturn(new StorageReference(
                new StorageBackendId(backend), backend.equals("beyonddimensions") ? "7" : backend + "-network"));
        return new SessionCraftStorageEndpoint(session);
    }
}
