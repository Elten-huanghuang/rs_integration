package com.huanghuang.rsintegration.machine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeyondDimensionsFavoriteExclusionContractTest {
    private static final Path CLIENT_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "machine", "BeyondDimensionsMachineHubClient.java");
    private static final Path JEI_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "network", "RSJeiPlugin.java");
    private static final Path RS_JEI_HOOK_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "network", "RSJeiOptionalHooks.java");
    private static final Path EMI_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "compat", "emi", "RSEmiPlugin.java");
    private static final Path RS_EMI_HOOK_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "compat", "emi", "RSEmiOptionalHooks.java");

    @Test
    void favoriteStripIsExcludedFromJeiAndEmiSidebars() throws IOException {
        String clientSource = Files.readString(CLIENT_SOURCE);
        String jeiSource = Files.readString(JEI_SOURCE);
        String rsJeiHookSource = Files.readString(RS_JEI_HOOK_SOURCE);
        String emiSource = Files.readString(EMI_SOURCE);
        String rsEmiHookSource = Files.readString(RS_EMI_HOOK_SOURCE);

        assertTrue(clientSource.contains("getFavoriteExtraAreas(Screen screen)"));
        assertTrue(jeiSource.contains("registerOptionalBeyondDimensionsGuiHandlers"));
        assertTrue(jeiSource.contains("getFavoriteExtraAreas(screen)"));
        assertTrue(rsJeiHookSource.contains("MachineFavoritesClient.getJeiExtraAreas(screen)"));
        assertTrue(emiSource.contains("addGenericExclusionArea"));
        assertTrue(emiSource.contains("getFavoriteExtraAreas(screen)"));
        assertTrue(emiSource.contains("registerGridExclusion"));
        assertFalse(emiSource.contains("GridScreen"),
                "the common EMI plugin must not hard-link optional RS client classes");
        assertTrue(rsEmiHookSource.contains("addExclusionArea(GridScreen.class"));
        assertTrue(rsEmiHookSource.contains("MachineFavoritesClient.getJeiExtraAreas(screen)"));
    }
}
