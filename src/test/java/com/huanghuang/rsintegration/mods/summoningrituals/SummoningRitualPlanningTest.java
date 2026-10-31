package com.huanghuang.rsintegration.mods.summoningrituals;

import com.almostreliable.summoningrituals.recipe.AltarRecipe;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class SummoningRitualPlanningTest extends BootstrapTest {
    @BeforeAll
    static void initializeModContext() throws ClassNotFoundException {
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName(RSIntegrationMod.class.getName());
        }
    }

    @Test
    void singleCraftReusesInstalledCatalystWithoutAnyPortableCatalyst() {
        ItemStackHandler inventory = new ItemStackHandler(3);
        inventory.setStackInSlot(2, new ItemStack(Items.STICK));

        List<ScaledIngredientSpec> missing = plan(inventory, 1);

        assertEquals(1, missing.size());
        assertEquals(DemandRole.CONSUMED, missing.get(0).role());
        assertEquals(3, missing.get(0).count());
        assertTrue(DirectMaterialAllocator.allocateScaledSingleVariant(missing,
                Map.of(new StackKey(Items.DIAMOND, null), 3L)).feasible());
    }

    @Test
    void batchSubtractsAltarStockAfterScalingAndRequiresNoExtraCatalyst() {
        ItemStackHandler inventory = new ItemStackHandler(3);
        inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, 2));
        inventory.setStackInSlot(2, new ItemStack(Items.STICK));

        List<ScaledIngredientSpec> missing = plan(inventory, 6);

        assertEquals(1, missing.size());
        assertEquals(16, missing.get(0).count());
        assertTrue(DirectMaterialAllocator.allocateScaledSingleVariant(missing,
                Map.of(new StackKey(Items.DIAMOND, null), 16L)).feasible());
        assertEquals(2, inventory.getStackInSlot(0).getCount());
        assertEquals(1, inventory.getStackInSlot(2).getCount());
    }

    @Test
    void absentCatalystIsRequiredOnceForTheWholeBatch() {
        List<ScaledIngredientSpec> missing = plan(new ItemStackHandler(3), 6);

        assertEquals(List.of(1L, 18L), missing.stream().map(ScaledIngredientSpec::count).toList());
        assertEquals(DemandRole.CATALYST, missing.get(0).role());
    }

    private static List<ScaledIngredientSpec> plan(ItemStackHandler inventory, int executions) {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        BlockEntity altar = mock(BlockEntity.class);
        when(player.level()).thenReturn(level);
        when(level.isLoaded(BlockPos.ZERO)).thenReturn(true);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(altar);
        when(altar.getCapability(ForgeCapabilities.ITEM_HANDLER)).thenReturn(LazyOptional.of(() -> inventory));
        AltarRecipe recipe = new AltarRecipe(Ingredient.of(Items.STICK),
                List.of(new AltarRecipe.Input(Ingredient.of(Items.DIAMOND), 3)));
        List<ScaledIngredientSpec> requested = new SummoningRitualRecipeHandler().getIngredients(recipe)
                .stream().map(spec -> ScaledIngredientSpec.of(spec,
                        spec.role() == DemandRole.CATALYST ? 1L : (long) spec.count() * executions))
                .toList();
        return SummoningRitualAltarBatchDelegate.planningScaledMaterials(
                player, recipe, null, BlockPos.ZERO, requested);
    }
}
