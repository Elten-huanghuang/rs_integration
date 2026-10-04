package com.huanghuang.rsintegration.network.binding;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DedicatedGuiOnlyBindingContractTest {

    @Test
    void dedicatedTargetsStayExactAndGuiOnly() throws IOException {
        assertGuiOnlyTarget("mods/goety/GoetyRSModule.java", "goety:dark_anvil");
        assertGuiOnlyTarget("mods/ironsspellbooks/IronSpellBooksRSModule.java",
                "irons_spellbooks:inscription_table");
        assertGuiOnlyTarget("mods/apotheosis/ApotheosisRSModule.java",
                "apotheosis:augmenting_table");
        assertGuiOnlyTarget("RSIntegrationMod.java", "tetra:basic_workbench");
        assertGuiOnlyTarget("RSIntegrationMod.java", "tetra:forged_workbench");
    }

    @Test
    void inscriptionTableNormalizesItsLeftHalfToTheVisibleRightHalf() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/huanghuang/rsintegration/network/binding/BindingEventHandler.java"));
        int table = source.indexOf("irons_spellbooks:inscription_table");
        int nextMachine = source.indexOf("ars_nouveau:scribes_table", table);
        assertTrue(table >= 0 && nextMachine > table);

        String resolver = source.substring(table, nextMachine);
        assertTrue(resolver.contains("\"left\".equals(part)"));
        assertTrue(resolver.contains("facing.getClockWise()"));
        assertTrue(resolver.contains("if (blockKey.equals(rightKey)) return rightPos;"));
    }

    private static void assertGuiOnlyTarget(String relativeSource, String blockId) throws IOException {
        Path source = Path.of("src/main/java/com/huanghuang/rsintegration").resolve(relativeSource);
        String text = Files.readString(source);
        int id = text.indexOf("\"" + blockId + "\"");
        assertTrue(id >= 0, () -> "missing exact binding target for " + blockId);

        int start = text.lastIndexOf("new BindingEventHandler.MachineBindingTarget(", id);
        int end = text.indexOf("));", id);
        assertTrue(start >= 0 && end > id, () -> "invalid binding target for " + blockId);

        String declaration = text.substring(start, end);
        assertTrue(declaration.contains("ModType.CUSTOM_GUI"),
                () -> blockId + " must not expose crafting actions");
        assertTrue(declaration.contains("RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS"),
                () -> blockId + " must follow the machine GUI tabs setting");
    }
}
