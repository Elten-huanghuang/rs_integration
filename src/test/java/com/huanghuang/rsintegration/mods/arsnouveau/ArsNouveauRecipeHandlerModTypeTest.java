package com.huanghuang.rsintegration.mods.arsnouveau;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.world.item.crafting.Recipe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/** 一个处理器覆盖三台机器时，执行类型必须按配方类型判定。 */
class ArsNouveauRecipeHandlerModTypeTest {

    @BeforeAll
    static void registerArsTypes() {
        registerType(ModIds.ID_ARS_APPARATUS, "apparatus");
        registerType(ModIds.ID_ARS_IMBUEMENT, "imbuement");
        registerType(ModIds.ID_ARS_SCRIBES_TABLE, "scribes_table");
    }

    private static void registerType(String id, String keyword) {
        if (ModType.byId(id) == ModType.GENERIC) {
            ModType.register(id, new String[0], new String[]{keyword},
                    new String[]{keyword}, () -> null);
        }
    }

    @Test
    void apparatusRecipesResolveToTheApparatusMachine() {
        // 附魔装置配方曾落回处理器的默认注魔台类型，
        // 导致玩家明明绑定了附魔装置却提示“没有可用的已绑定机器”。
        assertRecipeTypeResolvesTo(ArsRecipeClassifier.TYPE_APPARATUS, ModIds.ID_ARS_APPARATUS);
        assertRecipeTypeResolvesTo(ArsRecipeClassifier.TYPE_ENCHANTMENT, ModIds.ID_ARS_APPARATUS);
        assertRecipeTypeResolvesTo(ArsRecipeClassifier.TYPE_ARMOR_UPGRADE, ModIds.ID_ARS_APPARATUS);
    }

    @Test
    void imbuementRecipeResolvesToTheImbuementMachine() {
        assertRecipeTypeResolvesTo(ArsRecipeClassifier.TYPE_IMBUEMENT, ModIds.ID_ARS_IMBUEMENT);
    }

    @Test
    void glyphRecipeResolvesToTheScribesTable() {
        assertRecipeTypeResolvesTo(ArsRecipeClassifier.TYPE_GLYPH, ModIds.ID_ARS_SCRIBES_TABLE);
    }

    private static void assertRecipeTypeResolvesTo(String recipeTypeId, String expectedMachineTypeId) {
        assertEquals(expectedMachineTypeId,
                ArsNouveauRecipeHandler.machineTypeId(recipeTypeId));

        Recipe<?> recipe = mock(Recipe.class);
        try (MockedStatic<ArsTileAccess> access = mockStatic(ArsTileAccess.class)) {
            access.when(() -> ArsTileAccess.recipeTypeId(recipe)).thenReturn(recipeTypeId);
            ModType resolved = new ArsNouveauRecipeHandler().modType(recipe);
            assertNotSame(ModType.GENERIC, resolved,
                    "测试用的 Ars 机器类型没有注册");
            assertEquals(expectedMachineTypeId, resolved.id());
        }
    }
}
