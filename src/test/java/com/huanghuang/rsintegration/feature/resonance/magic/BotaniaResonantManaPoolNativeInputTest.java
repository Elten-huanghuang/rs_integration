package com.huanghuang.rsintegration.feature.resonance.magic;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BotaniaResonantManaPoolNativeInputTest extends BootstrapTest {
    @Test
    void nativeManaInputStillWorksAfterInventoryExceedsIntMax() {
        BotaniaResonantManaPoolBlockEntity pool = mock(
                BotaniaResonantManaPoolBlockEntity.class, CALLS_REAL_METHODS);
        doReturn(21_000_000_000L).when(pool).currentAmount();
        doReturn(Long.MAX_VALUE).when(pool).resourceCapacity();
        doReturn(0).when(pool).insert(anyInt());

        pool.receiveMana(Integer.MAX_VALUE);

        verify(pool).insert(Integer.MAX_VALUE);
    }
}
