package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.RSIPlanExtensions.Context;
import com.huanghuang.rsintegration.api.RSIPlanExtensions.Decoration;
import com.huanghuang.rsintegration.api.RSIPlanExtensions.Provider;
import com.huanghuang.rsintegration.api.RSIPlanExtensions.Source;
import com.huanghuang.rsintegration.crafting.MaterialSourceSelection;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static com.huanghuang.rsintegration.crafting.plan.PreviewInventoryModelTest.initial;
import static com.huanghuang.rsintegration.crafting.plan.PreviewInventoryModelTest.plan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExtensionStateTest extends BootstrapTest {
    @Test
    void inventoryChangesKeepPlanIdButRejectOldAndOutOfOrderPublications() {
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        var plan = plan(List.of(initial(iron, 3)), List.of(), Map.of());
        var state = new PlanExtensionState();
        List<Context> contexts = new ArrayList<>();
        List<Consumer<Decoration>> publishers = new ArrayList<>();
        List<Runnable> queued = new ArrayList<>();
        Map<ResourceLocation, Provider> providers = Map.of(ResourceLocation.tryParse("test:display"),
                (context, publish) -> {
                    contexts.add(context);
                    publishers.add(publish);
                });
        var selection = new MaterialSourceSelection(true, false, false);

        state.update(plan, selection, false, queued::add, providers);
        assertEquals(Set.of(Source.INVENTORY), contexts.get(0).enabledSources());
        assertEquals(IntegrationResourceKey.item(iron), contexts.get(0).initialMaterials().get(0).key());
        publishers.get(0).accept(decoration("original"));
        queued.remove(0).run();
        assertEquals(List.of("original"), state.summaries());

        state.update(plan, selection, true, queued::add, providers);
        assertEquals(contexts.get(0).planId(), contexts.get(1).planId());
        assertEquals(1L, contexts.get(1).inventoryRevision());
        publishers.get(0).accept(decoration("old inventory"));
        publishers.get(1).accept(decoration("old publication"));
        publishers.get(1).accept(decoration("new publication"));
        queued.remove(2).run();
        queued.forEach(Runnable::run);
        queued.clear();
        assertEquals(List.of("new publication"), state.summaries());

        state.newPlan();
        state.update(plan, selection, false, queued::add, providers);
        assertNotEquals(contexts.get(0).planId(), contexts.get(2).planId());
        publishers.get(1).accept(decoration("previous plan"));
        queued.remove(0).run();
        assertTrue(state.summaries().isEmpty());
    }

    private static Decoration decoration(String text) {
        return new Decoration(List.of(text), Map.of());
    }
}
