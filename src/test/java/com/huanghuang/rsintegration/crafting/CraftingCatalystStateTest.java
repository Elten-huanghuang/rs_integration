package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingCatalystStateTest extends BootstrapTest {
    private static final NodeId NODE = new NodeId(0);

    @Test
    void mergedCatalystPortKeepsEachSelectedItemsActualNbt() {
        ItemStack first = charged(86, "first");
        ItemStack second = charged(60, "second");
        InputPortId input = new InputPortId(NODE, 0);
        CraftingCatalystState state = new CraftingCatalystState(List.of(
                new InputDemand(input, Ingredient.of(Items.TOTEM_OF_UNDYING), 2,
                        DemandRole.CATALYST, first)));
        ItemStack firstReturn = charged(64, "first");
        ItemStack secondReturn = charged(38, "second");
        state.record(specs(), new ItemStack[]{first, second}, List.of(firstReturn, secondReturn));
        List<ItemStack> inventory = new ArrayList<>(List.of(secondReturn.copy(), firstReturn.copy()));
        OutputDeclaration firstOutput = output(0, input, first);
        OutputDeclaration secondOutput = output(1, input, second);

        assertTrue(state.tracks(firstOutput));
        assertTrue(state.tracks(secondOutput));
        assertEquals(1, state.returnedQuantity(firstOutput));
        assertEquals(1, state.returnedQuantity(secondOutput));
        assertTrue(ItemStack.isSameItemSameTags(firstReturn, state.removeReturned(firstOutput, inventory).get(0)));
        assertTrue(ItemStack.isSameItemSameTags(secondReturn, state.removeReturned(secondOutput, inventory).get(0)));
        assertTrue(inventory.stream().allMatch(ItemStack::isEmpty));
    }

    @Test
    void sameItemCatalystsBindToTheirSelectedGraphPortsBeforeFallingBackToOrder() {
        ItemStack first = charged(86, "first");
        ItemStack second = charged(60, "second");
        InputPortId secondPort = new InputPortId(NODE, 0);
        InputPortId firstPort = new InputPortId(NODE, 1);
        CraftingCatalystState state = new CraftingCatalystState(List.of(
                new InputDemand(secondPort, StrictNBTIngredient.of(second), 1, DemandRole.CATALYST, second),
                new InputDemand(firstPort, StrictNBTIngredient.of(first), 1, DemandRole.CATALYST, first)));
        ItemStack firstReturn = charged(64, "first");
        ItemStack secondReturn = charged(38, "second");
        state.record(specs(), new ItemStack[]{first, second}, List.of(firstReturn, secondReturn));
        List<ItemStack> inventory = new ArrayList<>(List.of(firstReturn.copy(), secondReturn.copy()));

        assertTrue(ItemStack.isSameItemSameTags(secondReturn,
                state.removeReturned(output(0, secondPort, second), inventory).get(0)));
        assertTrue(ItemStack.isSameItemSameTags(firstReturn,
                state.removeReturned(output(1, firstPort, first), inventory).get(0)));
        assertTrue(inventory.stream().allMatch(ItemStack::isEmpty));
    }

    @Test
    void changedCatalystDoesNotTakeAnotherSlotsExactGraphPort() {
        ItemStack firstPlanned = charged(100, "first");
        ItemStack firstActual = charged(84, "first");
        ItemStack second = charged(80, "second");
        InputPortId secondPort = new InputPortId(NODE, 0);
        InputPortId firstPort = new InputPortId(NODE, 1);
        CraftingCatalystState state = new CraftingCatalystState(List.of(
                new InputDemand(secondPort, StrictNBTIngredient.of(second), 1, DemandRole.CATALYST, second),
                new InputDemand(firstPort, StrictNBTIngredient.of(firstPlanned), 1, DemandRole.CATALYST, firstPlanned)));
        ItemStack firstReturn = charged(62, "first");
        ItemStack secondReturn = charged(58, "second");
        state.record(specs(), new ItemStack[]{firstActual, second}, List.of(firstReturn, secondReturn));
        List<ItemStack> inventory = new ArrayList<>(List.of(firstReturn.copy(), secondReturn.copy()));

        assertTrue(ItemStack.isSameItemSameTags(firstReturn,
                state.removeReturned(output(0, firstPort, firstPlanned), inventory).get(0)));
        assertTrue(ItemStack.isSameItemSameTags(secondReturn,
                state.removeReturned(output(1, secondPort, second), inventory).get(0)));
        assertTrue(inventory.stream().allMatch(ItemStack::isEmpty));
    }

    private static List<IngredientSpec> specs() {
        return List.of(new IngredientSpec(Ingredient.of(Items.TOTEM_OF_UNDYING), 1, DemandRole.CATALYST),
                new IngredientSpec(Ingredient.of(Items.TOTEM_OF_UNDYING), 1, DemandRole.CATALYST));
    }

    private static OutputDeclaration output(int index, InputPortId input, ItemStack original) {
        return OutputDeclaration.item(new OutputPortId(NODE, index), MaterialKey.of(original), 1,
                OutputKind.CATALYST_RETURN, 1, 1, OutputProbability.guaranteed(), false, input);
    }

    private static ItemStack charged(int souls, String identity) {
        ItemStack stack = new ItemStack(Items.TOTEM_OF_UNDYING);
        stack.getOrCreateTag().putInt("Souls", souls);
        stack.getOrCreateTag().putString("identity", identity);
        return stack;
    }
}
