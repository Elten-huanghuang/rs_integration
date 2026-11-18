package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CraftProductionHistoryTest extends BootstrapTest {
    @Test
    void countsActualVariantsAndLargeBatchesButNotReturnedInputs() {
        var history = new CraftProductionHistory();
        ItemStack intermediate = new ItemStack(Items.IRON_INGOT, 3);
        ItemStack variant = intermediate.copyWithCount(1);
        variant.getOrCreateTag().putInt("variant", 1);
        var variantKey = IntegrationResourceKey.item(variant);

        history.record(intermediate);
        history.record(variant, OutputKind.SECONDARY);
        history.record(variant, 3_000_000_000L);
        history.record(CollectedOutput.primary(variantKey, 4, ResourceLocation.tryParse("test:result")));
        history.record(new ItemStack(Items.BUCKET), OutputKind.CONTAINER);
        history.record(new ItemStack(Items.DIAMOND), OutputKind.CATALYST_RETURN);
        intermediate.shrink(2);
        variant.getOrCreateTag().putInt("variant", 2);
        var snapshot = history.snapshot();
        assertEquals(Map.of(IntegrationResourceKey.item(intermediate), 3L, variantKey, 3_000_000_005L), snapshot);
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }
}
