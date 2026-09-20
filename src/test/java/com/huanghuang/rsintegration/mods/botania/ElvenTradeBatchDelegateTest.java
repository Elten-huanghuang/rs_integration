package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElvenTradeBatchDelegateTest extends BootstrapTest {
    @Test
    void botaniaBatchOptionsDefaultToEnabled() {
        assertTrue(RSIntegrationConfig.ENABLE_BOTANIA_MANA_POOL_BATCH.getDefault());
        assertEquals(1024, RSIntegrationConfig.BOTANIA_MANA_POOL_BATCH_LIMIT.getDefault());
        assertTrue(RSIntegrationConfig.ENABLE_BOTANIA_ELVEN_TRADE_INPUT_BUFFER.getDefault());
        assertEquals(64, RSIntegrationConfig.BOTANIA_ELVEN_TRADE_INPUT_BUFFER_LIMIT.getDefault());
    }

    @Test
    void adjacentPortalsHaveDistinctCaptureColumns() {
        assertTrue(!ElvenTradeBatchDelegate.captureRegion(BlockPos.ZERO)
                .intersects(ElvenTradeBatchDelegate.captureRegion(BlockPos.ZERO.east())));
    }

    @Test
    void infersSameOperationCountAcrossEveryIngredient() {
        List<IngredientSpec> specs = List.of(
                new IngredientSpec(Ingredient.of(Items.STONE), 1),
                new IngredientSpec(Ingredient.of(Items.DIAMOND), 1));

        assertEquals(8, ElvenTradeBatchDelegate.inferOperations(specs, List.of(
                new ItemStack(Items.STONE, 8), new ItemStack(Items.DIAMOND, 8))));
        assertEquals(0, ElvenTradeBatchDelegate.inferOperations(specs, List.of(
                new ItemStack(Items.STONE, 8), new ItemStack(Items.DIAMOND, 7))));
    }

    @Test
    void buildsSingleOperationViewForDynamicOutputs() {
        List<IngredientSpec> specs = List.of(
                new IngredientSpec(Ingredient.of(Items.STONE), 1),
                new IngredientSpec(Ingredient.of(Items.DIAMOND), 1));
        List<ItemStack> single = ElvenTradeBatchDelegate.oneOperationMaterials(specs, List.of(
                new ItemStack(Items.STONE, 8), new ItemStack(Items.DIAMOND, 8)), 8);

        assertEquals(1, single.get(0).getCount());
        assertEquals(1, single.get(1).getCount());
    }

    @Test
    void accumulatesAndConsolidatesEveryOutput() {
        ItemStack tagged = new ItemStack(Items.EMERALD, 2);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", "alfheim");
        tagged.setTag(tag);

        List<ItemStack> outputs = ElvenTradeBatchDelegate.consolidateOutputs(List.of(
                tagged, tagged.copyWithCount(1), new ItemStack(Items.DIAMOND)), 8);

        assertEquals(2, outputs.size());
        ItemStack emeralds = outputs.stream()
                .filter(stack -> stack.is(Items.EMERALD)).findFirst().orElseThrow();
        assertEquals(24, emeralds.getCount());
        assertTrue(ItemStack.isSameItemSameTags(tagged, emeralds));
        assertEquals(8, outputs.stream().filter(stack -> stack.is(Items.DIAMOND))
                .findFirst().orElseThrow().getCount());
    }
}
