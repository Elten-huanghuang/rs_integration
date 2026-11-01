package com.huanghuang.rsintegration.client.storage;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.ConfigEditorModel;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskTooltipBackgroundTest {
    @AfterEach
    void unloadClientConfig() {
        RSIntegrationConfig.CLIENT_SPEC.setConfig(null);
    }

    @Test
    void masterSwitchDisablesBackgroundsAndReloadingRestoresThem() {
        var id = new ResourceLocation("rs_integration", "unified_storage_disk");
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.CLIENT_SPEC.correct(config);
        config.set("tooltips.customBackgrounds", false);
        RSIntegrationConfig.CLIENT_SPEC.setConfig(config);
        assertNull(DiskTooltipBackground.themeFor(id));
        RSIntegrationConfig.TOOLTIP_CUSTOM_BACKGROUNDS.set(true);
        assertNotNull(DiskTooltipBackground.themeFor(id));
        assertFalse(DiskTooltipBackground.hasCustomBackground(null));
    }

    @Test
    void tooltipOptionsHaveDefaultsAndAppearInTheExistingClientConfigEditor() {
        var options = new ConfigEditorModel(ConfigEditorModel.defaultFiles()).entries().stream()
                .filter(option -> option.file().id().equals("client")
                        && option.category().equals("tooltips")).toList();
        assertEquals(3, options.size());
        for (var option : options) {
            assertTrue(option.isBoolean());
            assertEquals(true, option.parsed());
            assertFalse(option.requiresRestart());
            assertFalse(option.description().isBlank());
        }
    }

    @Test
    void customBackgroundColorsAreAppliedAfterOtherTooltipDecorators() throws Exception {
        var handler = DiskTooltipBackground.class.getMethod(
                "onTooltipColor", RenderTooltipEvent.Color.class);
        assertEquals(EventPriority.LOWEST, handler.getAnnotation(SubscribeEvent.class).priority());
    }

    @Test
    void specialItemsHaveBackgrounds() {
        for (String item : List.of("resonance_storage_disk", "resonance_network_generator",
                "unified_storage_disk", "resonant_experience_wand", "order_terminal",
                "resonant_mana_pool", "resonant_source_tank", "rs_pickup_upgrade",
                "rs_magnet_upgrade", "rs_refill_upgrade", "rs_feeding_upgrade", "rs_void_upgrade")) {
            assertNotNull(DiskTooltipBackground.themeFor(new ResourceLocation("rs_integration", item)), item);
        }
    }

    @Test
    void unrelatedItemsKeepTheVanillaTooltipBackground() {
        assertNull(DiskTooltipBackground.themeFor(
                new ResourceLocation("minecraft", "diamond")));
        assertNull(DiskTooltipBackground.themeFor(
                new ResourceLocation("rs_integration", "fluid_refund")));
        assertNull(DiskTooltipBackground.themeFor(
                new ResourceLocation("other_mod", "rs_void_upgrade")));
    }
}
