package com.huanghuang.rsintegration.mods.farmersdelight;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.feature.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.reflection.probes.FarmersDelightReflection;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vectorwing.farmersdelight.common.Configuration;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;
import vectorwing.farmersdelight.common.crafting.ingredient.ChanceResult;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CuttingBoardReusableToolTest extends BootstrapTest {
    private Class<?> previousBoardClass;
    private final ServerLevel level = mock(ServerLevel.class);
    private final ServerPlayer player = mock(ServerPlayer.class);
    private final ExtractionLedger ledger = mock(ExtractionLedger.class);
    private final ResonanceStorageView resonance = mock(ResonanceStorageView.class);
    private CuttingBoardRecipe recipe;

    @BeforeEach
    void prepareRecipeAndBoard() {
        previousBoardClass = FarmersDelightReflection.cuttingBoardBEClass;
        FarmersDelightReflection.cuttingBoardBEClass = BlockEntity.class;
        CommentedConfig config = CommentedConfig.inMemory();
        Configuration.COMMON_CONFIG.correct(config);
        Configuration.COMMON_CONFIG.setConfig(config);
        recipe = new CuttingBoardRecipe(new ResourceLocation("test", "reusable_tool"), "",
                Ingredient.of(Items.PAPER), Ingredient.of(Items.IRON_SWORD),
                NonNullList.of(ChanceResult.EMPTY,
                        new ChanceResult(new ItemStack(Items.STICK), 1.0F)), "");
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(mock(BlockEntity.class));
        when(level.getRandom()).thenReturn(RandomSource.create(1L));
        when(ledger.resonanceReturnTarget(any(), any())).thenReturn(
                new ExtractionLedger.ResonanceReturnTarget(resonance, 7));
        when(resonance.insertView(eq(7), any(), anyInt(), eq(false))).thenReturn(ItemStack.EMPTY);
    }

    @AfterEach
    void restoreReflectionProbe() {
        FarmersDelightReflection.cuttingBoardBEClass = previousBoardClass;
    }

    @Test
    void freshOperationContinuesActualDamageAndReturnsToTheOriginalResonanceSlot() throws Exception {
        CuttingBoardBatchDelegate previous = delegate();
        ItemStack tool = tool(10, 1);
        assertTrue(start(previous, 64, tool));
        assertEquals(64, outputCount(previous));
        previous.onBatchFinished(player);

        CuttingBoardBatchDelegate next = delegate();
        assertTrue(previous.transferReusableMaterialsTo(next));
        assertTrue(start(next, 10, tool));
        assertEquals(10, outputCount(next));
        next.onBatchFinished(player);
        previous.releaseReusableMaterials(player);
        next.releaseReusableMaterials(player);
        next.releaseReusableMaterials(player);

        ArgumentCaptor<ItemStack> actual = ArgumentCaptor.forClass(ItemStack.class);
        verify(resonance).insertView(eq(7), actual.capture(), eq(1), eq(false));
        verify(resonance).markDirty(player);
        assertEquals(84, actual.getValue().getDamageValue());
        assertEquals("actual-tool", actual.getValue().getTag().getString("owner"));
        assertEquals(10, tool.getDamageValue(), "材料视图必须保持为独立模板");
    }

    @Test
    void replacementToolsAreUsedOnceAndExhaustedStockCannotBeRebuiltFromTheTemplate() throws Exception {
        ItemStack tools = tool(Items.IRON_SWORD.getMaxDamage() - 2, 2);
        CuttingBoardBatchDelegate first = delegate();
        assertTrue(start(first, 3, tools));
        assertEquals(3, outputCount(first));
        first.onBatchFinished(player);
        CuttingBoardBatchDelegate second = delegate();
        assertTrue(first.transferReusableMaterialsTo(second));
        assertTrue(start(second, 1, tools));
        assertEquals(1, outputCount(second));
        second.onBatchFinished(player);
        CuttingBoardBatchDelegate third = delegate();
        assertTrue(second.transferReusableMaterialsTo(third));

        assertFalse(start(third, 1, tools));
        assertTrue(third.lastStartFailureDetail().contains("no usable tool units"));
        third.releaseReusableMaterials(player);
        verifyNoInteractions(resonance);
    }

    @Test
    void anotherBoardCannotTakeOwnershipOfTheTool() throws Exception {
        CuttingBoardBatchDelegate previous = delegate();
        assertTrue(start(previous, 1, tool(0, 1)));
        previous.collectAllResults(player);
        previous.onBatchFinished(player);
        CuttingBoardBatchDelegate next = delegate();
        set(next, "myPos", BlockPos.ZERO.east());

        assertFalse(previous.transferReusableMaterialsTo(next));
        previous.releaseReusableMaterials(player);

        ArgumentCaptor<ItemStack> actual = ArgumentCaptor.forClass(ItemStack.class);
        verify(resonance).insertView(eq(7), actual.capture(), eq(1), eq(false));
        assertEquals(1, actual.getValue().getDamageValue());
    }

    private CuttingBoardBatchDelegate delegate() throws Exception {
        CuttingBoardBatchDelegate delegate = spy(new CuttingBoardBatchDelegate());
        set(delegate, "player", player);
        set(delegate, "myLevel", level);
        set(delegate, "myPos", BlockPos.ZERO);
        set(delegate, "recipe", recipe);
        doReturn(List.of(new IngredientSpec(Ingredient.of(Items.PAPER), 1),
                new IngredientSpec(Ingredient.of(Items.IRON_SWORD), 1,
                        InputConsumption.durability(1L))))
                .when(delegate).getRequiredMaterials();
        return delegate;
    }

    private boolean start(CuttingBoardBatchDelegate delegate, int operations, ItemStack tools) {
        delegate.prepareGraphBatch(operations);
        return delegate.startPreparedOperation(player,
                List.of(new ItemStack(Items.PAPER, operations), tools.copy()), ledger);
    }

    private int outputCount(CuttingBoardBatchDelegate delegate) {
        return delegate.collectAllResults(player).stream().mapToInt(ItemStack::getCount).sum();
    }

    private static ItemStack tool(int damage, int count) {
        ItemStack tool = new ItemStack(Items.IRON_SWORD, count);
        tool.setDamageValue(damage);
        tool.getOrCreateTag().putString("owner", "actual-tool");
        return tool;
    }

    private static void set(CuttingBoardBatchDelegate delegate, String name, Object value) throws Exception {
        Field field = CuttingBoardBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(delegate, value);
    }
}
