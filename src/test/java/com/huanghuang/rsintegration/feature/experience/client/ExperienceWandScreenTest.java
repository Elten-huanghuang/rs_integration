package com.huanghuang.rsintegration.feature.experience.client;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExperienceWandScreenTest extends BootstrapTest {
    @Test
    void fluidAmountsKeepEveryDigitThroughLongMaxValue() {
        assertEquals("0", ExperienceWandScreen.format(0L));
        assertEquals("2,147,483,648", ExperienceWandScreen.format((long) Integer.MAX_VALUE + 1L));
        assertEquals("99,845,381,177,416", ExperienceWandScreen.format(99_845_381_177_416L));
        assertEquals("9,223,372,036,854,775,807", ExperienceWandScreen.format(Long.MAX_VALUE));
    }
}
