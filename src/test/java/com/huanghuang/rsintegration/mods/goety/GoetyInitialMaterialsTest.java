package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.graph.AllocationId;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource.InitialPool;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class GoetyInitialMaterialsTest extends BootstrapTest {
    @Test
    void usesLiveCastStateForAnUnlockedRitualInput() {
        ItemStack old = wand(22772, "Frost");
        ItemStack live = wand(1, "Frost");
        CraftPlanGraph planned = plan(old, Ingredient.of(Items.STICK));

        CraftPlanGraph execution = GoetyInitialMaterials.rebind(planned, false,
                types -> Map.of(StackKey.of(live, true), 1L));

        MaterialAllocation allocation = execution.allocations().get(0);
        assertEquals(MaterialKey.of(live), allocation.material());
        assertEquals(new InitialPool(MaterialKey.of(live)), allocation.source());
        assertEquals(MaterialKey.of(old), planned.allocations().get(0).material());
        CraftPlanValidator.validate(execution);
    }

    @Test
    void keepsStrictNbtAndPrefersAvailableExactVariant() {
        ItemStack old = wand(22772, "Frost");
        ItemStack live = wand(1, "Frost");
        CraftPlanGraph planned = plan(old, Ingredient.of(Items.STICK));
        assertSame(planned, GoetyInitialMaterials.rebind(planned, false,
                types -> Map.of(StackKey.of(Items.BLAZE_ROD.getDefaultInstance(), true), 1L)));
        assertSame(planned, GoetyInitialMaterials.rebind(planned, false,
                types -> Map.of(StackKey.of(old, true), 1L, StackKey.of(live, true), 1L)));

        CraftPlanGraph locked = plan(old, StrictNBTIngredient.of(old));
        assertSame(locked, GoetyInitialMaterials.rebind(locked, false,
                types -> Map.of(StackKey.of(live, true), 1L)));
    }

    @Test
    void batchCanUseMultipleLiveVariantsWithoutChangingPlannedQuantity() {
        ItemStack old = wand(22772, "Frost");
        ItemStack first = wand(1, "Frost");
        ItemStack second = wand(2, "Frost");
        CraftPlanGraph planned = plan(old, Ingredient.of(Items.STICK), 3);
        Map<StackKey, Long> live = Map.of(StackKey.of(old, true), 1L,
                StackKey.of(first, true), 1L, StackKey.of(second, true), 1L);

        CraftPlanGraph execution = GoetyInitialMaterials.rebind(planned, false, types -> live);

        assertEquals(3, execution.allocations().size());
        for (MaterialAllocation part : execution.allocations()) {
            assertEquals(1L, part.quantity());
        }
        assertEquals(live.keySet(), execution.allocations().stream()
                .map(part -> new StackKey(part.material().item(), part.material().tag()))
                .collect(Collectors.toSet()));
        CraftPlanValidator.validate(execution);
    }

    @Test
    void refreshesTerminalRootsOnlyForGoety() {
        ItemStack old = wand(22772, "Frost");
        ItemStack live = wand(1, "Frost");
        MaterialKey material = MaterialKey.of(old);
        var root = new RootDemand(Ingredient.of(Items.STICK), 1, 0, old,
                List.of(new RootAllocation(new InitialPool(material), material, 1)));
        var planned = new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION, List.of(),
                List.of(), List.of(root), List.of(), List.of());

        assertSame(planned, GoetyInitialMaterials.rebind(planned, false, types -> {
            throw new AssertionError("非 Goety 的终端需求不读取库存");
        }));
        var execution = GoetyInitialMaterials.rebind(planned, true, types -> {
            assertEquals(Set.of(Items.STICK), types);
            return Map.of(StackKey.of(live, true), 1L);
        });
        assertEquals(MaterialKey.of(live), execution.rootDemands().get(0).allocations().get(0).material());
        CraftPlanValidator.validate(execution);
    }

    private static ItemStack wand(int castTime, String focus) {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.getOrCreateTag().putInt("RuntimeState", castTime);
        stack.getOrCreateTagElement("cap").putString("Focus", focus);
        return stack;
    }

    private static CraftPlanGraph plan(ItemStack wand, Ingredient ingredient) {
        return plan(wand, ingredient, 1);
    }

    private static CraftPlanGraph plan(ItemStack wand, Ingredient ingredient, int amount) {
        NodeId node = new NodeId(0);
        InputPortId port = new InputPortId(node, 0);
        MaterialKey material = MaterialKey.of(wand);
        MaterialAllocation allocation = new MaterialAllocation(new AllocationId(0), port,
                new InitialPool(material), material, amount);
        CraftNode ritual = new CraftNode(node, new ResourceLocation("goety", "frost_staff"),
                "goety", null, amount, List.of(), List.of(), false, null, null,
                List.of(new InputDemand(port, ingredient, amount, DemandRole.CONSUMED, wand)), List.of());
        return new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION, List.of(ritual),
                List.of(allocation), List.of(), List.of(), List.of(node));
    }
}
