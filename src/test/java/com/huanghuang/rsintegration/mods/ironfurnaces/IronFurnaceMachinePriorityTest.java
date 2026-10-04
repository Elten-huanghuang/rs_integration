package com.huanghuang.rsintegration.mods.ironfurnaces;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IronFurnaceMachinePriorityTest {

    @Test
    void fasterIronFurnaceTierComesFirst() {
        BoundMachine ordinary = machine("ironfurnaces_furnace||block.ironfurnaces.iron_furnace", 1);
        BoundMachine diamond = machine("ironfurnaces_furnace||block.ironfurnaces.diamond_furnace", 2);

        List<BoundMachine> machines = List.of(ordinary, diamond);
        machines = machines.stream().sorted(IronFurnaceMachinePriority.comparator()).toList();

        assertEquals(diamond, machines.get(0));
        assertEquals(7_000, IronFurnaceMachinePriority.priority(diamond.blockKey()));
    }

    @Test
    void unrelatedMachinesKeepTheirExistingOrder() {
        BoundMachine first = machine("some_machine||block.example.first", 1);
        BoundMachine second = machine("some_machine||block.example.second", 2);

        assertEquals(0, IronFurnaceMachinePriority.compare(first, second));
    }

    private static BoundMachine machine(String blockKey, int x) {
        return new BoundMachine(new ResourceLocation("minecraft", "overworld"),
                new BlockPos(x, 64, 0), ModType.GENERIC, blockKey);
    }
}
