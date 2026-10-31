package com.huanghuang.rsintegration.feature.resonance.bd;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class BDResonanceDiskTooltipTest extends BootstrapTest {
    private static final String PREFIX = "rsi.resonance.bd.tooltip.";
    private static final UUID DISK_ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    @Test
    void collapsedBoundTooltipIncludesColoredShiftHintAndBindingValues() {
        List<Component> lines = tooltip(false, true);
        assertTrue(contains(lines, "intro"));
        assertTrue(contains(lines, "subtitle"));
        assertTrue(contains(lines, "expand"));
        assertFalse(contains(lines, "detail_1"));
        assertResonanceColors(lines);
        assertEquals(42, translation(lines, "network").getArgs()[0]);
        assertEquals("12345678", translation(lines, "uuid").getArgs()[0]);
    }

    @Test
    void expandedBoundTooltipColorsEveryGuideLineAndRetainsCapacity() {
        List<Component> lines = tooltip(true, true);
        assertTrue(contains(lines, "title"));
        assertFalse(contains(lines, "expand"));
        for (int i = 1; i <= 8; i++) assertTrue(contains(lines, "detail_" + i));
        assertResonanceColors(lines);
        assertEquals(BDResonanceDiskItem.CAPACITY, translation(lines, "capacity").getArgs()[0]);
        assertEquals(42, translation(lines, "network").getArgs()[0]);
        assertEquals("12345678", translation(lines, "uuid").getArgs()[0]);
    }

    @Test
    void unboundStatusRemainsWarmWarningInBothTooltipStates() {
        for (boolean expanded : new boolean[]{false, true}) {
            List<Component> lines = tooltip(expanded, false);
            assertTrue(contains(lines, "unbound"));
            assertFalse(contains(lines, "network"));
            assertFalse(contains(lines, "uuid"));
            Component warning = lines.stream()
                    .filter(line -> line.getString().contains(text("unbound"))).findFirst().orElseThrow();
            warning.visit((style, value) -> {
                if (!value.isBlank()) {
                    assertNotNull(style.getColor());
                    int rgb = style.getColor().getValue();
                    assertTrue((rgb >> 16 & 255) > (rgb & 255), "未绑定提示应保留暖色警示");
                }
                return Optional.empty();
            }, Style.EMPTY);
        }
    }

    private static List<Component> tooltip(boolean expanded, boolean bound) {
        var item = mock(BDResonanceDiskItem.class, CALLS_REAL_METHODS);
        var lines = new ArrayList<Component>();
        // 模拟键盘与绑定查询边界，运行真实物品提示入口，不启动游戏或注册新物品。
        try (var screen = mockStatic(Screen.class);
             var access = mockStatic(BDResonanceDiskAccess.class)) {
            screen.when(Screen::hasShiftDown).thenReturn(expanded);
            access.when(() -> BDResonanceDiskAccess.isBound(ItemStack.EMPTY)).thenReturn(bound);
            access.when(() -> BDResonanceDiskAccess.getDiskId(ItemStack.EMPTY))
                    .thenReturn(bound ? DISK_ID : null);
            access.when(() -> BDResonanceDiskAccess.getNetworkId(ItemStack.EMPTY))
                    .thenReturn(bound ? 42 : -1);
            item.appendHoverText(ItemStack.EMPTY, null, lines, TooltipFlag.Default.NORMAL);
        }
        return lines;
    }

    private static void assertResonanceColors(List<Component> lines) {
        for (Component line : lines) {
            line.visit((style, text) -> {
                if (!text.isBlank()) {
                    assertNotNull(style.getColor(), text);
                    int rgb = style.getColor().getValue();
                    assertTrue((rgb & 255) > (rgb >> 16 & 255), "共振文字应使用蓝紫或青蓝色：" + text);
                }
                return Optional.empty();
            }, Style.EMPTY);
        }
    }

    private static boolean contains(List<Component> lines, String suffix) {
        return lines.stream().anyMatch(line -> line.getString().contains(text(suffix)));
    }

    private static String text(String suffix) {
        return Component.translatable(PREFIX + suffix).getString();
    }

    private static TranslatableContents translation(List<Component> lines, String suffix) {
        return lines.stream().map(Component::getContents)
                .filter(TranslatableContents.class::isInstance)
                .map(TranslatableContents.class::cast)
                .filter(contents -> contents.getKey().equals(PREFIX + suffix))
                .findFirst().orElseThrow();
    }
}
