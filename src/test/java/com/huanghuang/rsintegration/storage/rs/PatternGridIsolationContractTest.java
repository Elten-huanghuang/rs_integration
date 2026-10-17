package com.huanghuang.rsintegration.storage.rs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 确保 RSI 的虚拟界面入口不会重新接管 RS 原生样板终端。 */
class PatternGridIsolationContractTest {
    private static final Path ROOT = Path.of("src", "main", "java", "com", "huanghuang",
            "rsintegration", "mixin", "refinedstorage");

    @Test
    void patternGridIsExcludedFromClientInputAndMachineTabMixins() throws IOException {
        String mouse = read("GridScreenMouseMixin.java");
        assertTrue(mouse.contains("onMousePressedPre") && mouse.contains("GridType.PATTERN"));
        assertTrue(count(mouse, "GridType.PATTERN") >= 5,
                "鼠标按下、拖拽、释放和滚轮入口都必须保留样板终端原生行为");

        String tabs = read("GridScreenMachineTabMixin.java");
        int patternGuard = tabs.indexOf("GridType.PATTERN");
        int bindingSync = tabs.indexOf("RSSidePanelNetworkHandler.sendRequestSync");
        if (bindingSync < 0) bindingSync = tabs.indexOf("RSSidePanelNetworkHandler.sendBindingSyncRequest");
        assertTrue(patternGuard >= 0 && patternGuard < bindingSync,
                "样板终端应在机器绑定同步之前直接退出");

        for (String file : new String[]{"GridScreenKeyboardMixin.java", "GridScreenTooltipMixin.java"})
            assertTrue(read(file).contains("GridType.PATTERN"), file);

        String unifiedScreen = read("UnifiedGridScreenMixin.java");
        assertTrue(count(unifiedScreen, "GridType.PATTERN") >= 3,
                "混合列表的 tick、点击和滚轮入口都必须排除样板终端");

        String unifiedClient = Files.readString(Path.of("src", "main", "java", "com", "huanghuang",
                "rsintegration", "unifiedgrid", "client", "UnifiedGridClient.java"));
        assertTrue(count(unifiedClient, "GridType.PATTERN") >= 3,
                "客户端混合视图分发器也必须排除样板终端");
    }

    private static String read(String file) throws IOException {
        return Files.readString(ROOT.resolve(file));
    }

    private static int count(String value, String needle) {
        int count = 0;
        for (int offset = 0; (offset = value.indexOf(needle, offset)) >= 0; offset += needle.length()) count++;
        return count;
    }
}
