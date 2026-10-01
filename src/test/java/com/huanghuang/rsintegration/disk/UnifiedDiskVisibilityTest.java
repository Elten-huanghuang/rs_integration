package com.huanghuang.rsintegration.disk;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.config.RSStorageConfig;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.RegistryObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class UnifiedDiskVisibilityTest extends BootstrapTest {
    private final RegistryObject<Item> original = ModItems.UNIFIED_STORAGE_DISK;

    @AfterEach void cleanup() {
        ModItems.UNIFIED_STORAGE_DISK = original;
        UnifiedDiskVisibility.disconnect();
        RSStorageConfig.SPEC.setConfig(null);
    }

    private void enabled(boolean value) {
        CommentedConfig config = CommentedConfig.inMemory();
        RSStorageConfig.SPEC.correct(config);
        config.set("unifiedDisk.enabled", value);
        RSStorageConfig.SPEC.setConfig(config);
    }

    @SuppressWarnings("unchecked")
    @Test void absenceAlwaysHidesAndServerStateOverridesLocalConfigUntilDisconnect() {
        ModItems.UNIFIED_STORAGE_DISK = null;
        assertFalse(UnifiedDiskVisibility.visible());
        UnifiedDiskVisibility.fromServer(true);
        assertFalse(UnifiedDiskVisibility.visible());
        UnifiedDiskVisibility.disconnect();
        ModItems.UNIFIED_STORAGE_DISK = mock(RegistryObject.class);
        assertTrue(UnifiedDiskVisibility.visible());
        enabled(false); assertFalse(UnifiedDiskVisibility.visible());
        UnifiedDiskVisibility.fromServer(true); assertTrue(UnifiedDiskVisibility.visible());
        UnifiedDiskVisibility.disconnect(); assertFalse(UnifiedDiskVisibility.visible());
        enabled(true); UnifiedDiskVisibility.fromServer(false); assertFalse(UnifiedDiskVisibility.visible());
        UnifiedDiskVisibility.disconnect(); assertTrue(UnifiedDiskVisibility.visible());
    }

    @Test void recipeConditionUsesServerConfigAndRecipeRequiresBothConditions() throws Exception {
        var condition = new UnifiedDiskEnabledCondition();
        enabled(false); assertFalse(condition.test(null));
        // 客户端展示覆盖不能让服务器重新开放合成配方。
        UnifiedDiskVisibility.fromServer(true); assertFalse(condition.test(null));
        enabled(true); assertTrue(condition.test(null));
        var json = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/data/rs_integration/recipes/unified_storage_disk.json"))).getAsJsonObject();
        var conditions = json.getAsJsonArray("conditions");
        assertEquals(2, conditions.size());
        assertEquals("forge:mod_loaded", conditions.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("refinedstorage", conditions.get(0).getAsJsonObject().get("modid").getAsString());
        assertEquals(condition.getID().toString(), conditions.get(1).getAsJsonObject().get("type").getAsString());
    }
}
