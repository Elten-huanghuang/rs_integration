package com.huanghuang.rsintegration.network.binding;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless contracts for the optional-mod GUI fallback, not dedicated machine targets. */
class CustomGuiBindingContractTest {
    private static final Path SOURCE = Path.of("src/main/java/com/huanghuang/rsintegration/"
            + "network/binding/BindingEventHandler.java");

    @Test
    void manualFallbackRejectsBlocksWithoutMenusBeforeCreatingBinding() throws IOException {
        String source = Files.readString(SOURCE);
        String manual = source.substring(source.indexOf("public static void onRightClickBlock("),
                source.indexOf("public static void handleExplicitBind("));
        int customList = manual.indexOf("CUSTOM_GUI_MACHINE_MODS");
        int menuGuard = manual.indexOf("if (!(be instanceof MenuProvider)) return;");
        int fallback = manual.indexOf("matched = new MachineBindingTarget(");
        int persist = manual.indexOf("BindingStorage.addBinding(");
        assertTrue(customList >= 0 && menuGuard > customList);
        assertTrue(fallback > menuGuard && persist > fallback,
                "a mod namespace alone must not authorize binding decorative blocks");
        assertTrue(manual.lastIndexOf("if (matched == null) {", menuGuard) >
                manual.indexOf("findEnabledTarget(block)"),
                "dedicated machine registrations must bypass the generic GUI guard");
    }

    @Test
    void nearbyFallbackKeepsTheSameMenuRequirement() throws IOException {
        String source = Files.readString(SOURCE);
        String nearby = source.substring(source.indexOf("static NearbyTarget prepareNearbyTarget("),
                source.indexOf("static NearbyBindResult bindNearbyTarget("));
        int menuGuard = nearby.indexOf("if (!(be instanceof MenuProvider)) return null;");
        assertTrue(menuGuard >= 0);
        assertTrue(nearby.indexOf("target = new MachineBindingTarget(") > menuGuard);
    }
}
