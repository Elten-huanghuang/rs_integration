package com.huanghuang.rsintegration.recipe.viewer;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.network.binding.BindingStorage.BindingEntry;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class RecipeCraftTooltipTest extends BootstrapTest {
    @Test
    void usesNativeTranslationKeyOfTheBoundMachine() {
        assertMachineName("worktable", "eidolon_worktable||block.eidolon.worktable", "block.eidolon.worktable");
        assertMachineName("crucible", "eidolon_ritual||block.eidolon.crucible", "block.eidolon.crucible");
        assertMachineName("ritual", "eidolon_ritual||block.eidolon.brazier", "block.eidolon.brazier");
        assertMachineName("block.minecraft.furnace", "block.minecraft.furnace", "block.minecraft.furnace");
    }

    @Test
    void usesAStandaloneActionForRecipesWithoutAMachine() {
        assertEquals("gui.rs_integration.jei.remote_craft",
                translationOf(RecipeCraftTooltip.resolve("unregistered", null, null)).getKey());
        assertEquals("gui.rs_integration.jei.fluid_container_craft",
                translationOf(RecipeCraftTooltip.resolve("fluid_container", ModType.FLUID_CONTAINER, null)).getKey());
        assertEquals("gui.rs_integration.jei.rs_auto_craft",
                translationOf(RecipeCraftTooltip.resolve("generic", ModType.GENERIC, null)).getKey());
    }

    private static void assertMachineName(String filter, String blockKey, String expectedNameKey) {
        BindingEntry binding = new BindingEntry(Level.OVERWORLD.location(), BlockPos.ZERO, blockKey, null, null);
        TranslatableContents tooltip = translationOf(RecipeCraftTooltip.resolve(filter, null, binding));
        assertEquals("gui.rs_integration.jei.remote_craft_via", tooltip.getKey());
        assertEquals(1, tooltip.getArgs().length);
        Component name = assertInstanceOf(Component.class, tooltip.getArgs()[0]);
        assertEquals(expectedNameKey, translationOf(name).getKey());
    }

    private static TranslatableContents translationOf(Component component) {
        return assertInstanceOf(TranslatableContents.class, component.getContents());
    }
}
