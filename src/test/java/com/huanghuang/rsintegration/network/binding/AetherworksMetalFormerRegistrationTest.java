package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.mods.aetherworks.AetherworksRSModule;
import com.huanghuang.rsintegration.recipe.AetherworksMetalFormerRecipeHandler;
import com.huanghuang.rsintegration.util.ModIds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AetherworksMetalFormerRegistrationTest {
    @BeforeAll
    static void register() { AetherworksRSModule.INSTANCE.registerModType(); }

    @Test
    void classAndJeiCategorySelectMetalFormerInsteadOfAnvil() {
        ModType type = ModType.byId(ModIds.ID_AETHERWORKS_METAL_FORMER);
        assertSame(type, ModType.findByRecipeClass("net.sirplop.aetherworks.recipe.MetalFormerRecipe"));
        assertEquals(type.id(), ModType.filterForJeiUid("aetherworks:metal_forming"));
        assertEquals(type.id(), ModType.filterForRecipeClass("net.sirplop.aetherworks.recipe.MetalFormerRecipe"));
        assertEquals("gui.rs_integration.jei.aetherworks_metal_former_craft", type.jeiTooltipKey());
        assertEquals(ModType.GraphExecutionAudit.FLAT_REQUIRED, type.graphExecutionAudit());
    }

    @Test
    void nativeAndExplicitBindingsAllowAllRecipeFolders() {
        ModType type = ModType.byId(ModIds.ID_AETHERWORKS_METAL_FORMER);
        assertSame(type, ModType.fromBlockKey("block.aetherworks.forge_metal_former"));
        assertSame(type, ModType.fromBlockKey(type.id() + "||block.aetherworks.forge_metal_former"));
        assertNull(AltarBindingRegistry.normalizeSubType("metal_former", type));
        assertNull(AltarBindingRegistry.normalizeSubType("metal_forming", type));
        assertNull(AltarBindingRegistry.normalizeSubType("custom_recipes", type));
        var handler = new AetherworksMetalFormerRecipeHandler();
        assertTrue(handler.isCompatibleBinding(null, "block.aetherworks.forge_metal_former"));
        assertFalse(handler.isCompatibleBinding(null, "block.aetherworks.forge_anvil"));
        assertFalse(handler.isCompatibleBinding(null, "block.aetherworks.forge_tool_station"));
    }
}
