package com.huanghuang.rsintegration.mixin.beyonddimensions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.mixin.plugin.RSIntegrationMixinPlugin;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验超越维度终端界面混入的版本隔离。
 *
 * <p>0.7.x 的 {@code client.gui.BDBaseGUI} / {@code client.gui.DimensionsNetGUI} 在 0.8 被删除，
 * 换成 Compose 风格的 {@code client.ui.storage.StorageScreen}。两代混入不能同时生效：旧版类不在
 * 时新版必须放行，旧版类在时新版必须拒绝，反之亦然。这里同时确认配置里登记了两代混入，
 * 避免以后漏注册导致功能静默消失。</p>
 */
class BeyondDimensionsGuiMixinTest {

    private static final String MODERN_MARKER =
            "com/wintercogs/beyonddimensions/api/menu/interaction/SlotInteractions.class";
    private static final String LEGACY_SCREEN = "com.wintercogs.beyonddimensions.client.gui.DimensionsNetGUI";
    private static final String MODERN_SCREEN =
            "com.wintercogs.beyonddimensions.client.ui.storage.StorageScreen";

    @Test
    void mixinConfigRegistersBothGenerations() throws Exception {
        Set<String> clientMixins = clientMixins();
        for (String name : new String[]{
                "beyonddimensions.BDBaseGuiOverlayMixin",
                "beyonddimensions.DimensionsNetGuiAutoEatMixin",
                "beyonddimensions.DimensionsNetGuiSidebarMixin",
                "beyonddimensions.BDStorageScreenOverlayMixin",
                "beyonddimensions.BDStorageScreenAutoEatMixin"}) {
            assertTrue(clientMixins.contains(name), "rs_integration.mixins.json 缺少 " + name);
        }
    }

    @Test
    void modernScreenMixinsFollowTheInstalledGeneration() {
        RSIntegrationMixinPlugin plugin = new RSIntegrationMixinPlugin();
        boolean modern = testClassLoader().getResource(MODERN_MARKER) != null;

        assertEquals(modern, plugin.shouldApplyMixin(MODERN_SCREEN,
                        BDStorageScreenOverlayMixin.class.getName()),
                "0.8 的绘制混入应只在 0.8 及以上启用");
        assertEquals(modern, plugin.shouldApplyMixin(MODERN_SCREEN,
                        BDStorageScreenAutoEatMixin.class.getName()),
                "0.8 的控件混入应只在 0.8 及以上启用");

        // 旧版界面混入在 0.8 上必须拒绝，否则会在类消失后留下无效混入。
        assertFalse(plugin.shouldApplyMixin(MODERN_SCREEN,
                DimensionsNetGuiAutoEatMixin.class.getName()));
        assertFalse(plugin.shouldApplyMixin(MODERN_SCREEN,
                DimensionsNetGuiSidebarMixin.class.getName()));

        // 新版界面混入只认 0.8 的屏幕类，不能落到旧版类上。
        assertFalse(plugin.shouldApplyMixin(LEGACY_SCREEN,
                BDStorageScreenAutoEatMixin.class.getName()));
    }

    private static Set<String> clientMixins() throws Exception {
        JsonObject root;
        try (InputStream input = testClassLoader()
                .getResourceAsStream("rs_integration.mixins.json")) {
            assertNotNull(input, "找不到 rs_integration.mixins.json");
            root = JsonParser.parseReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonArray client = root.getAsJsonArray("client");
        assertNotNull(client, "rs_integration.mixins.json 缺少 client 段");
        Set<String> names = new HashSet<>();
        for (JsonElement element : client) names.add(element.getAsString());
        return names;
    }

    private static ClassLoader testClassLoader() {
        return BeyondDimensionsGuiMixinTest.class.getClassLoader();
    }
}
