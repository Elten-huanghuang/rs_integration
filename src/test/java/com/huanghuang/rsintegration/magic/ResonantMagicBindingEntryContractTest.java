package com.huanghuang.rsintegration.magic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResonantMagicBindingEntryContractTest {
    private static final Path HANDLER = Path.of("src/main/java/com/huanghuang/rsintegration/"
            + "network/binding/BindingEventHandler.java");
    private static final Path OVERLAY = Path.of("src/main/java/com/huanghuang/rsintegration/"
            + "magic/ResonantMagicBindingHintOverlay.java");

    @Test
    void resonantBlocksUseTheExistingSneakBindingEntry() throws IOException {
        String source = Files.readString(HANDLER);
        int target = source.indexOf("if (block instanceof ResonantMagicBlockMarker)");
        int ordinaryMachines = source.indexOf("MachineBindingTarget matched = findEnabledTarget(block)");
        assertTrue(target >= 0 && target < ordinaryMachines,
                "resonant blocks must be handled by the shared right-click binding entry");
        assertTrue(source.contains("handleResonantMagicBinding(event, player)"));
        assertTrue(source.contains("event.setCancellationResult(InteractionResult.CONSUME)"));
        assertTrue(source.contains("block.rs_integration.resonant_magic.bound"));
        assertTrue(source.contains("block.rs_integration.resonant_magic.unbound"));
    }

    @Test
    void bindingHintDoesNotRequireClientBlockEntityToBeSynchronized() throws IOException {
        String source = Files.readString(OVERLAY);
        assertTrue(source.contains("instanceof ResonantMagicBlockMarker"));
        assertTrue(source.contains("if (held.isEmpty() || !RSBindingHook.INSTANCE.matches(held)) return;"));
        assertFalse(source.contains("binding_hint.hold_terminal"));
        assertTrue(source.contains("entity != null && entity.isBound()"));
    }

    @Test
    void sharedBindingHintSkipsResonantBlocksAndEmptyHands() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/huanghuang/rsintegration/"
                + "network/binding/BindingHintOverlay.java"));
        assertTrue(source.contains("instanceof ResonantMagicBlockMarker) return;"));
        assertTrue(source.contains("if (held.isEmpty() && mc.player.getOffhandItem().isEmpty()) return;"));
    }
}
