package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewBindingCaptureTest {
    @Test
    void wideCaptureCarriesCandidateAndBindingStateAcrossSlices() {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        for (int index = 0; index < 96; index++) {
            MaterialRef output = new MaterialRef(ResourceLocation.parse("test:output_" + index), "");
            recipes.put(output, List.of(candidate(index, output, false), candidate(index, output, true)));
        }
        AtomicInteger runtimeCalls = new AtomicInteger();
        AtomicInteger machineCalls = new AtomicInteger();
        var capture = new PreviewBindingCapture(new ImmutableRecipeGraph(recipes), node -> {
            runtimeCalls.incrementAndGet();
            return !node.recipeId().getPath().startsWith("disabled_");
        }, node -> {
            machineCalls.incrementAndGet();
            int route = Integer.parseInt(node.modTypeId());
            return route != 47;
        });

        int slices = 0;
        while (true) {
            AtomicInteger budget = new AtomicInteger(5);
            int before = runtimeCalls.get();
            boolean done = capture.advance(() -> budget.getAndDecrement() > 0);
            assertTrue(runtimeCalls.get() - before <= 5);
            slices++;
            if (done) break;
            if (slices == 1) assertThrows(IllegalStateException.class, capture::result);
            assertTrue(slices < 96);
        }
        var result = capture.result();
        assertEquals(192, runtimeCalls.get());
        assertEquals(96, machineCalls.get());
        assertEquals(94, result.graph().recipesById().size());
        assertEquals(Set.of(ResourceLocation.parse("test:output_47"), ResourceLocation.parse("test:output_95")),
                result.blockedOutputIds());
        assertTrue(result.graph().recipesById().keySet().stream().noneMatch(id -> id.getPath().startsWith("disabled_")));
    }

    private static RecipeNode candidate(int index, MaterialRef output, boolean enabled) {
        return new RecipeNode(ResourceLocation.parse("test:" + (enabled ? "enabled_" : "disabled_") + index),
                output, 1, List.of(), Integer.toString(index % 48), ResourceLocation.parse("test:binding"));
    }
}
