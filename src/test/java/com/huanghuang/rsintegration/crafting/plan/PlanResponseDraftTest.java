package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlanResponseDraftTest extends BootstrapTest {
    @Test
    void detachesMutableStacksArraysAndCollectionsBeforeCaching() {
        ItemStack target = new ItemStack(Items.DIAMOND, 2);
        ItemStack input = new ItemStack(Items.COAL, 3);
        ItemStack base = new ItemStack(Items.IRON_INGOT, 1);
        int[] code = {1, 2};
        List<PlanStep> steps = new ArrayList<>(List.of(new PlanStep(
                new ResourceLocation("test", "recipe"), target, 1, List.of(input))));
        Map<IngredientKey, PlanResponse.Availability> materials = new LinkedHashMap<>();
        materials.put(IngredientKey.of(input), new PlanResponse.Availability(3, 3));
        LinkedHashSet<String> machines = new LinkedHashSet<>(List.of("generic"));

        PlanResponseDraft draft = new PlanResponseDraft(true, "diamond", target, steps,
                materials, List.of(), "test:recipe", null, null, 0, 0, 0,
                List.of(Component.literal("warning")), 1, code, null, null, 0L,
                false, false, false, base, machines, Map.of(), target, null, true);

        target.setCount(64);
        input.setCount(64);
        base.setCount(64);
        code[0] = 99;
        steps.clear();
        materials.clear();
        machines.clear();

        PlanResponse response = draft.toResponse();
        assertEquals(true, response.executionBlocked());
        assertEquals(2, response.targetResult().getCount());
        assertEquals(3, response.steps().get(0).inputs().get(0).getCount());
        assertEquals(1, response.baseItem().getCount());
        assertEquals(1, response.embersCode()[0]);
        assertEquals(1, response.materials().size());
        assertEquals(1, response.boundMachineTypes().size());
        assertNotSame(draft.targetResult(), response.targetResult());

        ItemStack exposed = draft.targetResult();
        exposed.setCount(32);
        int[] exposedCode = draft.embersCode();
        exposedCode[0] = 77;
        ItemStack exposedStepInput = draft.steps().get(0).inputs().get(0);
        exposedStepInput.setCount(22);
        assertEquals(2, draft.targetResult().getCount());
        assertEquals(1, draft.embersCode()[0]);
        assertEquals(3, draft.steps().get(0).inputs().get(0).getCount());
    }

    @Test
    void exposedCollectionsAreUnmodifiable() {
        PlanResponseDraft draft = new PlanResponseDraft(false, "", ItemStack.EMPTY,
                List.of(), Map.of(), List.of(), "", null, null, 0, 0, 0,
                List.of(), 1, null, null, null, 0L, false, false, false,
                null, new LinkedHashSet<>(), Map.of(), null, null);

        assertThrows(UnsupportedOperationException.class,
                () -> draft.boundMachineTypes().add("machine"));
        assertThrows(UnsupportedOperationException.class,
                () -> draft.materials().put(IngredientKey.of(new ItemStack(Items.STONE)),
                        new PlanResponse.Availability(1, 1)));
    }
}
