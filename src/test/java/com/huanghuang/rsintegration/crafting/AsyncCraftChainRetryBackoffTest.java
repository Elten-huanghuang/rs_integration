package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节点重试退避：等机器必须短退避，等材料保留长退避。
 */
class AsyncCraftChainRetryBackoffTest extends BootstrapTest {

    private static final String MACHINE_BUSY = "all bound machines are busy, unloaded, or unavailable";

    @Test
    void machineWaitBacksOffInTicksInsteadOfSeconds() {
        // 切石机这类机器一次只切一刀，长退避会让整条链每两秒才推进一步。
        for (int attempts = 1; attempts <= 6; attempts++) {
            int delay = AsyncCraftChain.retryDelayTicks(MACHINE_BUSY, attempts);
            assertTrue(delay > 0);
            assertTrue(delay <= 6, "机器等待的退避不该超过 6 tick，实际 " + delay);
        }
    }

    @Test
    void materialWaitKeepsLongBackoff() {
        assertEquals(2, AsyncCraftChain.retryDelayTicks("missing material", 1));
        assertEquals(40, AsyncCraftChain.retryDelayTicks("missing material", 6));
    }

    @Test
    void onlyMachineReasonsUseShortBackoff() {
        assertTrue(AsyncCraftChain.isMachineWait(MACHINE_BUSY));
        assertTrue(AsyncCraftChain.isMachineWait(
                "operation budget, machine, or capture is temporarily unavailable"));
        assertFalse(AsyncCraftChain.isMachineWait("missing material"));
        assertFalse(AsyncCraftChain.isMachineWait(null));
    }
}
