package com.huanghuang.rsintegration.mods.tetra.client;

import com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryPacket;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TetraWorkbenchMaterialStateTest extends BootstrapTest {
    private static final String FILL_SLOT = "mmt_amulet/fill";

    @Test
    void includesMaterialsDeclaredDirectlyByTheSchematic() throws ReflectiveOperationException {
        ItemStack target = new ItemStack(Items.GOLD_INGOT);
        ItemStack sculk = new ItemStack(Items.SCULK, 32);
        ItemStack tagged = new ItemStack(Items.POTION);
        tagged.getOrCreateTag().putString("Potion", "minecraft:water");
        var schematic = new Schematic(target, 2,
                new Outcome(0, false, new Material(sculk)),
                new Outcome(1, false, new Material(new ItemStack(Items.POTION), tagged, tagged.copy())));

        assertEquals(List.of(sculk, tagged),
                TetraWorkbenchMaterialState.schematicMaterials(schematic, target, FILL_SLOT, Set.of()));
        assertEquals(List.of(tagged), TetraWorkbenchMaterialState.schematicMaterials(schematic, target,
                FILL_SLOT, Set.of(JeiNetworkInventoryPacket.key(sculk))));
    }

    @Test
    void respectsHiddenOutcomesAndTheNativeSlotCheck() throws ReflectiveOperationException {
        ItemStack target = new ItemStack(Items.GOLD_INGOT);
        ItemStack sculk = new ItemStack(Items.SCULK, 32);
        var schematic = new Schematic(target, 2,
                new Outcome(1, false, new Material(sculk)),
                new Outcome(0, true, new Material(sculk)),
                new Outcome(2, false, new Material(sculk)));

        assertEquals(List.of(),
                TetraWorkbenchMaterialState.schematicMaterials(schematic, target, FILL_SLOT, Set.of()));
        assertEquals(List.of(),
                TetraWorkbenchMaterialState.schematicMaterials(new Object(), target, FILL_SLOT, Set.of()));
    }

    public static final class Schematic {
        private final Definition definition;
        private final ItemStack target;
        private final int slots;

        Schematic(ItemStack target, int slots, Outcome... outcomes) {
            this.definition = new Definition(outcomes);
            this.target = target;
            this.slots = slots;
        }

        public int getNumMaterialSlots() {
            return slots;
        }

        public boolean acceptsMaterial(ItemStack actualTarget, String slot, int index, ItemStack candidate) {
            return actualTarget == target && FILL_SLOT.equals(slot)
                    && (index == 0 && candidate.is(Items.SCULK)
                        || index == 1 && candidate.is(Items.POTION) && candidate.hasTag());
        }
    }

    private record Definition(Outcome[] outcomes) {}

    private record Outcome(int materialSlot, boolean hidden, Material material) {}

    public record Material(ItemStack... stacks) {
        public ItemStack[] getApplicableItemStacks() {
            return stacks;
        }
    }
}
