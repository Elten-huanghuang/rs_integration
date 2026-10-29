package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuttingBoardStartFailureDetailTest extends BootstrapTest {
    @Test
    void freshDelegateReportsNoStartFailure() {
        assertNull(new CuttingBoardBatchDelegate().lastStartFailureDetail());
    }

    @Test
    void rejectedStartExposesReasonForTheCraftLogAndFailureReport() {
        CuttingBoardBatchDelegate delegate = new CuttingBoardBatchDelegate();

        try (ExtractionLedger ledger = new ExtractionLedger()) {
            // 未经过 validateAndInit 时配方尚未解析，启动必须被拒绝；
            // 该分支在触碰玩家与方块实体之前返回，因此可以传入 null 玩家。
            boolean started = delegate.startPreparedOperation(null, List.of(), ledger);

            assertFalse(started);
        }
        String detail = delegate.lastStartFailureDetail();
        assertNotNull(detail);
        assertTrue(detail.contains("cutting board"), detail);
        assertTrue(detail.contains("recipe"), detail);
    }
}
