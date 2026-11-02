package com.huanghuang.rsintegration.feature.foodorder.client;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodOrderDropdownTest extends BootstrapTest {

    @Test
    void outsideClickClosesDropdownAndReleasesFocusWithoutConsumingClick() {
        FoodOrderDropdown dropdown = new FoodOrderDropdown(
                null, 10, 10, 80, 16, FoodOrderViewMode.ALL, ignored -> {});
        dropdown.setFocused(true);
        dropdown.onPress();

        assertTrue(dropdown.isExpanded());
        assertFalse(dropdown.mouseClicked(200, 200, 0));
        assertFalse(dropdown.isExpanded());
        assertFalse(dropdown.isFocused());
    }
}
