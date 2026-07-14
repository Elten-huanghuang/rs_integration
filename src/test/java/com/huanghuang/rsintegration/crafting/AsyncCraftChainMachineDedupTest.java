package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AsyncCraftChainMachineDedupTest {

    @Test
    void deduplicatesOnlyMatchingDimensionPositionAndModType() {
        ResourceLocation overworld = new ResourceLocation("minecraft", "overworld");
        BlockPos pos = new BlockPos(1, 64, 2);
        BoundMachine first = new BoundMachine(overworld, pos, ModType.GENERIC, "furnace");
        BoundMachine duplicate = new BoundMachine(overworld, pos, ModType.GENERIC, "furnace");
        BoundMachine otherType = new BoundMachine(overworld, pos, ModType.CUSTOM_GUI, "furnace");

        List<BoundMachine> result = AsyncCraftChain.deduplicateMachines(
                List.of(first, duplicate, otherType));

        assertEquals(2, result.size());
        assertSame(first, result.get(0));
        assertSame(otherType, result.get(1));
    }
}
