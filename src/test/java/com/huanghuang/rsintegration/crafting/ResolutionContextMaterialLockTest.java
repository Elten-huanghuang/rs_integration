package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;

class ResolutionContextMaterialLockTest extends BootstrapTest {

    @Test
    void missingIngredientSpecsDoNotFailWhileApplyingMaterialLocks() {
        Map<CraftingResolver.StackKey, Number> counts = Map.of();
        ResolutionContext context = new ResolutionContext(
                null, Map.of(), counts, null, false, null);
        context.withMaterialLocks(Map.of(
                "embers:alchemy/yuusha_qb|test", new ItemStack(Items.DIAMOND)));

        assertNull(context.lockedSpecs(
                new ResourceLocation("embers", "alchemy/yuusha_qb"), null));
    }
}
