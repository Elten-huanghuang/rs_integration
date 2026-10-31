package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRSModule;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipe;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipe.Machine;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipeHandler;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class IronSpellBooksMachineBindingTest extends BootstrapTest {
    private final IronSpellBooksRecipeHandler handler = new IronSpellBooksRecipeHandler();

    @BeforeAll
    static void registerMachineTypes() {
        for (Machine machine : Machine.values()) {
            String id = machineTypeId(machine);
            if (ModType.findById(id) == null) {
                ModType.register(id, new String[0], new String[0], new String[0], () -> null);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Machine.class)
    void handlerUsesTheRecipesMachineInsteadOfTheDefaultScrollForge(Machine machine) {
        IronSpellBooksRecipe recipe = recipeFor(machine);
        ModType expected = ModType.byId(machineTypeId(machine));

        assertNotSame(ModType.GENERIC, expected);
        assertSame(expected, ModType.classifyRecipe(recipe));
        assertSame(expected, handler.modType(recipe));
    }

    @ParameterizedTest
    @EnumSource(Machine.class)
    void previewUsesTheBindingForTheRecipesMachine(Machine machine) {
        ServerPlayer player = mock(ServerPlayer.class);
        IronSpellBooksRecipe recipe = recipeFor(machine);
        ModType expected = ModType.byId(machineTypeId(machine));

        // 只绑定配方真正需要的机器，不能误查处理器默认的卷轴锻造台。
        try (MockedStatic<ModRecipeHandlers> claims = mockStatic(ModRecipeHandlers.class);
             MockedStatic<AltarBindingRegistry> bindings = mockStatic(AltarBindingRegistry.class)) {
            claims.when(() -> ModRecipeHandlers.claimFor(recipe))
                    .thenReturn(new ModRecipeHandlers.Claim(handler, List.of(handler)));
            bindings.when(() -> AltarBindingRegistry.hasBindingForRecipe(player, recipe, expected))
                    .thenReturn(true);

            ModType resolved = CraftMachineBindingSupport.resolveExecutionModType(
                    player, recipe, null, null);

            assertSame(expected, resolved);
            assertTrue(CraftMachineBindingSupport.hasBindingForExecutionType(
                    player, recipe, resolved));
        }
    }

    private static IronSpellBooksRecipe recipeFor(Machine machine) {
        IronSpellBooksRecipe recipe = mock(IronSpellBooksRecipe.class);
        when(recipe.machine()).thenReturn(machine);
        return recipe;
    }

    private static String machineTypeId(Machine machine) {
        return switch (machine) {
            case SCROLL_FORGE -> IronSpellBooksRSModule.SCROLL_FORGE_TYPE;
            case ARCANE_ANVIL -> IronSpellBooksRSModule.ARCANE_ANVIL_TYPE;
            case ALCHEMIST_CAULDRON -> IronSpellBooksRSModule.ALCHEMIST_CAULDRON_TYPE;
        };
    }
}
