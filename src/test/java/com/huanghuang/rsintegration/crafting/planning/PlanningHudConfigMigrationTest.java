package com.huanghuang.rsintegration.crafting.planning;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraftforge.fml.config.ModConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanningHudConfigMigrationTest {
    @AfterEach void unload() { RSIntegrationConfig.CLIENT_SPEC.setConfig(null); }

    @Test void legacyCornerMovesOnceAndCanBeChosenAgainAfterward() {
        var config = load(0.0, 0.0);
        RSIntegrationConfig.migratePlanningHudLayout(config);
        assertEquals(1.0, RSIntegrationConfig.PLANNING_HUD_HORIZONTAL_POSITION.get());
        assertEquals(0.5, RSIntegrationConfig.PLANNING_HUD_VERTICAL_POSITION.get());
        RSIntegrationConfig.PLANNING_HUD_HORIZONTAL_POSITION.set(0.0);
        RSIntegrationConfig.PLANNING_HUD_VERTICAL_POSITION.set(0.0);
        RSIntegrationConfig.migratePlanningHudLayout(config);
        assertEquals(0.0, RSIntegrationConfig.PLANNING_HUD_HORIZONTAL_POSITION.get());
        assertEquals(0.0, RSIntegrationConfig.PLANNING_HUD_VERTICAL_POSITION.get());
        verify(config, times(1)).save();
    }

    @Test void customPositionIsPreserved() {
        var config = load(0.25, 0.75);
        RSIntegrationConfig.migratePlanningHudLayout(config);
        assertEquals(0.25, RSIntegrationConfig.PLANNING_HUD_HORIZONTAL_POSITION.get());
        assertEquals(0.75, RSIntegrationConfig.PLANNING_HUD_VERTICAL_POSITION.get());
    }

    @Test void newClientsDefaultToRightCenter() {
        assertEquals(1.0, RSIntegrationConfig.PLANNING_HUD_HORIZONTAL_POSITION.getDefault());
        assertEquals(0.5, RSIntegrationConfig.PLANNING_HUD_VERTICAL_POSITION.getDefault());
    }

    private static ModConfig load(double horizontal, double vertical) {
        var values = CommentedConfig.inMemory();
        values.set("planningHud.horizontalPosition", horizontal);
        values.set("planningHud.verticalPosition", vertical);
        values.set("planningHud.layoutVersion", 0);
        RSIntegrationConfig.CLIENT_SPEC.setConfig(values);
        var config = mock(ModConfig.class);
        doReturn(RSIntegrationConfig.CLIENT_SPEC).when(config).getSpec();
        return config;
    }
}
