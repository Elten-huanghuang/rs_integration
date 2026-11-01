package com.huanghuang.rsintegration.feature.goety;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket;
import com.huanghuang.rsintegration.crafting.plan.RecipeChoice;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeModel;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import com.huanghuang.rsintegration.feature.goety.integration.BrewMaterialPlan;
import com.huanghuang.rsintegration.feature.goety.integration.MissingMaterialOrders.MaterialNeed;
import com.huanghuang.rsintegration.feature.goety.network.BrewNetwork.PlanAction;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrewMaterialPlanTest extends BootstrapTest {
    @BeforeAll
    static void loadConfig() {
        var config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        RSIntegrationConfig.CRAFTING_TYPED_PREVIEW_TIMEOUT_MS.set(500);
        var context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            RSIntegrationMod.MOD_BUS.getClass();
        }
    }

    @AfterAll
    static void restoreTimeout() {
        RSIntegrationConfig.CRAFTING_TYPED_PREVIEW_TIMEOUT_MS.set(
                RSIntegrationConfig.CRAFTING_TYPED_PREVIEW_TIMEOUT_MS.getDefault());
    }

    @Test
    void missingRawMaterialKeepsBothRecursiveLevelsAndSurvivesTransport() {
        var plan = plan(Map.of(), PlanningPreferences.empty());
        assertFalse(plan.success());
        assertTrue(plan.executionBlocked());
        assertEquals(2, plan.graph().nodes().size());
        assertEquals(2, plan.graph().roots().size());
        assertFalse(plan.graph().unresolved().isEmpty());
        var raw = plan.materials().entrySet().stream()
                .filter(entry -> entry.getKey().item() == Items.OAK_LOG).findFirst().orElseThrow();
        assertTrue(raw.getValue().missingCount() > 0);

        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new PlanResponsePacket(plan, 73L).encode(buf);
            var decoded = PlanResponsePacket.decode(buf);
            assertEquals(73L, decoded.requestId());
            var tree = PlanTreeModel.from(decoded.plan());
            assertTrue(contains(tree.root, Items.STICK));
            assertTrue(contains(tree.root, Items.OAK_PLANKS));
            assertTrue(contains(tree.root, Items.OAK_LOG));
            assertTrue(contains(tree.root, Items.REDSTONE));
        } finally {
            buf.release();
        }
    }

    @Test
    void availableBaseMaterialsMakeTheWholeBatchExecutable() {
        var plan = plan(Map.of(new StackKey(Items.OAK_LOG, null), 1L,
                new StackKey(Items.REDSTONE, null), 1L), PlanningPreferences.empty());
        assertTrue(plan.success(), plan.missing().toString());
        assertFalse(plan.executionBlocked());
        assertEquals(2, plan.graph().nodes().size());
        assertTrue(plan.materials().values().stream().allMatch(value -> value.missingCount() == 0));
    }

    @Test
    void stockOnlyPreferenceCannotBecomeAnExecutableRecursiveOrder() {
        var preferences = new PlanningPreferences(Map.of(
                "minecraft:oak_planks", RecipeChoice.NoRecipe.INSTANCE), Map.of(), Set.of());
        var plan = plan(Map.of(new StackKey(Items.OAK_LOG, null), 1L,
                new StackKey(Items.REDSTONE, null), 1L), preferences);
        assertFalse(plan.success());
        assertTrue(plan.executionBlocked());
        assertTrue(plan.graph().nodes().stream().noneMatch(node ->
                node.primaryOutput().is(Items.OAK_PLANKS)));
    }

    @Test
    void planRequestsCarryTheMenuRevisionAndBranchPreferences() {
        var preferences = new PlanningPreferences(Map.of(
                "minecraft:oak_planks", RecipeChoice.NoRecipe.INSTANCE), Map.of(), Set.of());
        var request = new PlanAction(9, 17, true, 73L,
                Map.of("minecraft:stick", "test:sticks"), Map.of(), preferences);
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            request.write(buf);
            var decoded = PlanAction.read(buf);
            assertEquals(request, decoded);
        } finally {
            buf.release();
        }
        assertNotEquals(BrewMaterialPlan.targetId(9, 17), BrewMaterialPlan.targetId(9, 18));
        assertTrue(BrewMaterialPlan.isTarget(BrewMaterialPlan.targetId(9, 17).toString()));
        assertFalse(BrewMaterialPlan.isTarget("minecraft:crafting"));
        assertThrows(IllegalArgumentException.class, () ->
                new PlanAction(9, 17, true, -1L, Map.of(), Map.of(), preferences));
    }

    private static PlanResponse plan(Map<StackKey, Long> available, PlanningPreferences preferences) {
        var planks = recipe("planks", Items.OAK_PLANKS, 4, Items.OAK_LOG);
        var sticks = recipe("sticks", Items.STICK, 4, Items.OAK_PLANKS, Items.OAK_PLANKS);
        var level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        try (MockedStatic<RecipeIndex> index = mockStatic(RecipeIndex.class)) {
            index.when(() -> RecipeIndex.reusableCatalystRoutes(level)).thenReturn(Map.of());
            index.when(() -> RecipeIndex.get(level)).thenReturn(Map.of(
                    Items.OAK_PLANKS, List.of(entry(planks)),
                    Items.STICK, List.of(entry(sticks))));
            return BrewMaterialPlan.create(BrewMaterialPlan.targetId(9, 17),
                    List.of(new MaterialNeed(List.of(new ItemStack(Items.STICK)), 2),
                            new MaterialNeed(List.of(new ItemStack(Items.REDSTONE)), 1)),
                    new ItemStack(Items.POTION), available, level, null, null,
                    Map.of(), Map.of(), preferences);
        }
    }

    private static ShapelessRecipe recipe(String path, Item output, int count, Item... inputs) {
        var ingredients = NonNullList.<Ingredient>create();
        for (var input : inputs) ingredients.add(Ingredient.of(input));
        return new ShapelessRecipe(ResourceLocation.fromNamespaceAndPath("test", path), "",
                CraftingBookCategory.MISC, new ItemStack(output, count), ingredients);
    }

    private static RecipeIndex.Entry entry(ShapelessRecipe recipe) {
        return new RecipeIndex.Entry(recipe, ModType.GENERIC,
                ResourceLocation.fromNamespaceAndPath("minecraft", "crafting"));
    }

    private static boolean contains(PlanTreeNode node, Item item) {
        return node.displayStack.is(item) || node.children.stream().anyMatch(child -> contains(child, item));
    }
}
