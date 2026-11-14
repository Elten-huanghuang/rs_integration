package com.huanghuang.rsintegration.crafting.fluid;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkBottleFluidHandler;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.core.NonNullList;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.wrappers.FluidBucketWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

class FluidContainerCatalogTest extends BootstrapTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interleavedManagersKeepBothPublishedCatalogsResolvable(boolean reverseOrder) {
        FluidContainerCatalog.invalidate();
        ServerLevel firstLevel = mock(ServerLevel.class);
        ServerLevel secondLevel = mock(ServerLevel.class);
        RecipeManager firstManager = mock(RecipeManager.class);
        RecipeManager secondManager = mock(RecipeManager.class);
        when(firstLevel.getRecipeManager()).thenReturn(firstManager);
        when(secondLevel.getRecipeManager()).thenReturn(secondManager);
        when(firstLevel.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(secondLevel.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ItemStack firstSample = new ItemStack(Items.FLINT);
        firstSample.getOrCreateTag().putInt("capacity", 1375);
        firstSample.getOrCreateTag().putString("owner", "review_first");
        ItemStack secondSample = firstSample.copy();
        secondSample.getOrCreateTag().putString("owner", "review_second");
        Recipe<?> firstRecipe = new ShapelessRecipe(id("review_first"), "", CraftingBookCategory.MISC,
                firstSample, NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.DIRT)));
        Recipe<?> secondRecipe = new ShapelessRecipe(id("review_second"), "", CraftingBookCategory.MISC,
                secondSample, NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.DIRT)));
        when(firstManager.getRecipes()).thenReturn(List.of(firstRecipe));
        when(secondManager.getRecipes()).thenReturn(List.of(secondRecipe));
        try (var tokens = mockStatic(InkFluidSupport.class, CALLS_REAL_METHODS);
             var capabilities = mockStatic(FluidUtil.class, CALLS_REAL_METHODS)) {
            tokens.when(() -> InkFluidSupport.token(any(FluidStack.class))).thenAnswer(call -> token(call.getArgument(0)));
            capabilities.when(() -> FluidUtil.getFluidHandler(any(ItemStack.class))).thenAnswer(call -> {
                ItemStack stack = call.getArgument(0);
                IFluidHandlerItem handler = stack.is(Items.FLINT) || stack.is(Items.STICK)
                        ? new NbtContainer(stack) : bucketHandler(stack);
                return handler == null ? LazyOptional.empty() : LazyOptional.of(() -> handler);
            });
            var firstBuild = FluidContainerCatalog.incrementalIndex(firstLevel, new HashMap<>(), new HashSet<>(),
                    new HashMap<>(), () -> true);
            var secondBuild = FluidContainerCatalog.incrementalIndex(secondLevel, new HashMap<>(), new HashSet<>(),
                    new HashMap<>(), () -> true);
            assertTrue(firstBuild.advance(() -> false));
            assertTrue(secondBuild.advance(() -> false));
            var firstId = FluidContainerCatalog.discover(List.of(firstSample), List.of(new FluidStack(Fluids.WATER, 1)),
                    NbtContainer::new, FluidContainerCatalogTest::token).get(0).getId();
            var secondId = FluidContainerCatalog.discover(List.of(secondSample), List.of(new FluidStack(Fluids.WATER, 1)),
                    NbtContainer::new, FluidContainerCatalogTest::token).get(0).getId();
            assertNull(FluidContainerCatalog.byId(firstId));
            assertNull(FluidContainerCatalog.resolve(firstLevel, firstId));
            assertNull(FluidContainerCatalog.byId(secondId));
            ItemStack compatibilitySample = firstSample.copy();
            compatibilitySample.getOrCreateTag().putString("owner", "compatibility_only");
            var compatibility = FluidContainerCatalog.discover(List.of(compatibilitySample),
                    List.of(new FluidStack(Fluids.WATER, 1)), NbtContainer::new, FluidContainerCatalogTest::token);
            FluidContainerCatalog.publish(compatibility, new HashMap<>(), new HashSet<>(), new HashMap<>());
            var compatibilityId = compatibility.get(0).getId();
            assertSame(compatibility.get(0), FluidContainerCatalog.byId(compatibilityId));
            // 两种顺序都从同一份旧状态开始完成构建，再交错发布。
            assertTrue((reverseOrder ? secondBuild : firstBuild).publish());
            assertNotNull(FluidContainerCatalog.byId(reverseOrder ? secondId : firstId));
            assertNull(FluidContainerCatalog.byId(reverseOrder ? firstId : secondId));
            assertTrue((reverseOrder ? firstBuild : secondBuild).publish());
            FluidContainerRecipe uniqueFirst = FluidContainerCatalog.allRecipes(firstLevel).stream()
                    .filter(recipe -> recipe.emptyContainer().hasTag()
                            && "review_first".equals(recipe.emptyContainer().getTag().getString("owner")))
                    .findFirst().orElseThrow();
            assertNotNull(FluidContainerCatalog.byId(uniqueFirst.getId()));
            assertFalse(FluidContainerCatalog.allRecipes(firstLevel).isEmpty());
            assertNotNull(FluidContainerCatalog.byId(uniqueFirst.getId()),
                    "后发布的目录不能清掉另一 manager 已发布且仍被缓存的转换 ID");
            assertNotNull(FluidContainerCatalog.byId(secondId));
            assertSame(compatibility.get(0), FluidContainerCatalog.byId(compatibilityId));
            for (ServerLevel level : List.of(firstLevel, secondLevel)) {
                for (FluidContainerRecipe recipe : FluidContainerCatalog.allRecipes(level)) {
                    assertNotNull(FluidContainerCatalog.byId(recipe.getId()));
                    assertSame(recipe, FluidContainerCatalog.resolve(level, recipe.getId()));
                }
            }
            var firstPublished = FluidContainerCatalog.allRecipes(firstLevel);
            var secondPublished = FluidContainerCatalog.allRecipes(secondLevel);
            var cancelled = FluidContainerCatalog.incrementalIndex(firstLevel, new HashMap<>(), new HashSet<>(),
                    new HashMap<>(), () -> true);
            assertTrue(cancelled.advance(() -> false));
            cancelled.cancel();
            assertFalse(cancelled.publish());
            assertSame(firstPublished, FluidContainerCatalog.allRecipes(firstLevel));
            assertSame(secondPublished, FluidContainerCatalog.allRecipes(secondLevel));
            assertNotNull(FluidContainerCatalog.byId(firstId));
            assertNotNull(FluidContainerCatalog.byId(secondId));
            // 同一 manager 的新目录仅替换其旧子表，不能改变另一 manager 和同步定义。
            when(firstManager.getRecipes()).thenReturn(List.of());
            FluidContainerCatalog.index(firstLevel, new HashMap<>(), new HashSet<>(), new HashMap<>());
            assertNull(FluidContainerCatalog.byId(firstId));
            assertNull(FluidContainerCatalog.resolve(firstLevel, firstId));
            assertNotNull(FluidContainerCatalog.byId(secondId));
            assertSame(secondPublished, FluidContainerCatalog.allRecipes(secondLevel));
            assertSame(compatibility.get(0), FluidContainerCatalog.byId(compatibilityId));
            assertFalse(firstBuild.publish());
            when(firstManager.getRecipes()).thenReturn(List.of(firstRecipe));
            var stale = FluidContainerCatalog.incrementalIndex(secondLevel, new HashMap<>(), new HashSet<>(),
                    new HashMap<>(), () -> true);
            assertTrue(stale.advance(() -> false));
            FluidContainerCatalog.invalidate();
            assertNull(FluidContainerCatalog.byId(firstId));
            assertNull(FluidContainerCatalog.byId(secondId));
            assertNull(FluidContainerCatalog.byId(compatibilityId));
            assertFalse(stale.publish());
            assertFalse(firstBuild.publish());
            assertFalse(secondBuild.publish());
            // 同步兼容入口只发布自己的完整子表，不能恢复另一个已失效目录。
            FluidContainerCatalog.index(firstLevel, new HashMap<>(), new HashSet<>(), new HashMap<>());
            assertNotNull(FluidContainerCatalog.byId(firstId));
            assertNull(FluidContainerCatalog.byId(secondId));
            assertFalse(FluidContainerCatalog.allRecipes(secondLevel).isEmpty());
            assertNotNull(FluidContainerCatalog.byId(firstId));
            assertNotNull(FluidContainerCatalog.byId(secondId));
        } finally {
            FluidContainerCatalog.invalidate();
        }
    }

    @BeforeAll static void initializeModLogger() throws ClassNotFoundException {
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName("com.huanghuang.rsintegration.RSIntegrationMod");
        }
    }
    @Test void incrementalDiscoveryPreservesNbtInkAndBucketConversions() {
        ItemStack special = new ItemStack(Items.FLINT);
        special.getOrCreateTag().putInt("capacity", 250);
        special.getOrCreateTag().putString("owner", "special");
        List<ItemStack> samples = List.of(special, special.copy(), new ItemStack(Items.BUCKET),
                new ItemStack(Items.LAVA_BUCKET), waterBottle(2), new ItemStack(Items.GLASS_BOTTLE));
        List<FluidStack> fluids = List.of(new FluidStack(Fluids.WATER, 1), purityWater(2));
        var handlers = new Function<ItemStack, IFluidHandlerItem>() {
            @Override public IFluidHandlerItem apply(ItemStack stack) {
                if (stack.is(Items.FLINT) || stack.is(Items.STICK)) return new NbtContainer(stack);
                IFluidHandlerItem bottle = InkBottleFluidHandler.create(stack);
                return bottle != null ? bottle : bucketHandler(stack);
            }
        };
        var expected = FluidContainerCatalog.discover(samples, fluids, handlers, FluidContainerCatalogTest::token);
        var build = new FluidContainerCatalog.Discovery(samples, fluids, handlers, FluidContainerCatalogTest::token);
        int advances = 0;
        while (true) {
            AtomicInteger work = new AtomicInteger();
            boolean complete = build.advance(() -> work.getAndIncrement() >= 1);
            advances++;
            if (complete) break;
            assertThrows(IllegalStateException.class, build::result);
        }
        assertTrue(advances > samples.size());
        assertEquals(expected.stream().map(FluidContainerRecipe::getId).toList(),
                build.result().stream().map(FluidContainerRecipe::getId).toList());
        assertTrue(build.advance(() -> true));
        assertThrows(UnsupportedOperationException.class, () -> build.result().clear());
    }

    @Test void incrementalCatalogPublishesOnlyAfterCompletionAndRejectsStaleSessions() {
        FluidContainerCatalog.invalidate();
        ServerLevel level = mock(ServerLevel.class);
        RecipeManager manager = mock(RecipeManager.class);
        when(level.getRecipeManager()).thenReturn(manager);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ItemStack special = new ItemStack(Items.FLINT);
        special.getOrCreateTag().putInt("capacity", 1375);
        special.getOrCreateTag().putString("owner", "recipe_only");
        Recipe<?> specialRecipe = new ShapelessRecipe(id("special_container"), "", CraftingBookCategory.MISC,
                special, NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.DIRT)));
        when(manager.getRecipes()).thenReturn(List.of(specialRecipe));
        var tokenItem = InkFluidTestFixtures.tokenItem();
        try (var tokens = mockStatic(InkFluidSupport.class, CALLS_REAL_METHODS);
             var capabilities = mockStatic(FluidUtil.class, CALLS_REAL_METHODS)) {
            tokens.when(() -> InkFluidSupport.token(any(FluidStack.class))).thenAnswer(call ->
                    InkFluidSupport.token(tokenItem, call.getArgument(0)));
            capabilities.when(() -> FluidUtil.getFluidHandler(any(ItemStack.class))).thenAnswer(call -> {
                ItemStack stack = call.getArgument(0);
                IFluidHandlerItem handler = stack.is(Items.FLINT) || stack.is(Items.STICK)
                        ? new NbtContainer(stack) : bucketHandler(stack);
                return handler == null ? LazyOptional.empty() : LazyOptional.of(() -> handler);
            });
            AtomicBoolean current = new AtomicBoolean(true);
            var build = FluidContainerCatalog.incrementalIndex(level, new HashMap<>(), new HashSet<>(),
                    new HashMap<>(), current::get);
            assertFalse(build.advance(() -> true));
            assertTrue(FluidContainerCatalog.allRecipes(level).isEmpty());
            assertFalse(build.publish());
            int advances = 0;
            while (true) {
                AtomicInteger work = new AtomicInteger();
                boolean complete = build.advance(() -> work.getAndIncrement() >= 64);
                advances++;
                assertNull(FluidContainerCatalog.byId(waterFill().getId()));
                assertTrue(FluidContainerCatalog.allRecipes(level).isEmpty());
                if (complete) break;
                assertTrue(advances < 10_000);
            }
            assertTrue(advances > 1);
            assertTrue(build.publish());
            assertTrue(build.publish());
            var ready = FluidContainerCatalog.allRecipes(level);
            assertFalse(ready.isEmpty());
            assertTrue(ready.stream().anyMatch(recipe -> recipe.filling() && recipe.fluid().getAmount() == 1375
                    && "recipe_only".equals(recipe.emptyContainer().getTag().getString("owner"))));
            assertSame(ready, FluidContainerCatalog.allRecipes(level));
            FluidContainerCatalog.invalidate();
            assertFalse(build.publish());
            var cancelled = FluidContainerCatalog.incrementalIndex(level, new HashMap<>(), new HashSet<>(),
                    new HashMap<>(), current::get);
            cancelled.cancel();
            assertFalse(cancelled.advance(() -> false));
            assertFalse(cancelled.publish());
            var stale = FluidContainerCatalog.incrementalIndex(level, new HashMap<>(), new HashSet<>(),
                    new HashMap<>(), current::get);
            assertTrue(stale.advance(() -> false));
            current.set(false);
            assertFalse(stale.publish());
            current.set(true);
            CraftPlanningRevision.bump();
            assertFalse(stale.publish());
            var reloaded = FluidContainerCatalog.incrementalIndex(level, new HashMap<>(), new HashSet<>(),
                    new HashMap<>(), () -> true);
            assertTrue(reloaded.advance(() -> false));
            assertTrue(reloaded.publish());
            assertEquals(ready.stream().map(FluidContainerRecipe::getId).toList(),
                    FluidContainerCatalog.allRecipes(level).stream().map(FluidContainerRecipe::getId).toList());
            var replaced = FluidContainerCatalog.incrementalIndex(level, new HashMap<>(), new HashSet<>(),
                    new HashMap<>(), () -> true);
            assertTrue(replaced.advance(() -> false));
            when(level.getRecipeManager()).thenReturn(mock(RecipeManager.class));
            assertFalse(replaced.publish());
        } finally {
            FluidContainerCatalog.invalidate();
        }
    }
    static ItemStack token(FluidStack fluid) { return InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), fluid); }
    static List<FluidContainerRecipe> buckets() {
        return FluidContainerCatalog.discover(List.of(new ItemStack(Items.BUCKET), new ItemStack(Items.WATER_BUCKET)),
                List.of(new FluidStack(Fluids.WATER, 1), new FluidStack(Fluids.LAVA, 1)),
                FluidContainerCatalogTest::bucketHandler, FluidContainerCatalogTest::token);
    }
    private static IFluidHandlerItem bucketHandler(ItemStack stack) {
        return stack.is(Items.BUCKET) || stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET)
                ? new FluidBucketWrapper(stack) : null;
    }
    static FluidContainerRecipe waterFill() {
        return buckets().stream().filter(r -> r.filling() && r.fluid().getFluid() == Fluids.WATER).findFirst().orElseThrow();
    }
    static FluidContainerRecipe waterDrain() {
        return buckets().stream().filter(r -> !r.filling() && r.fluid().getFluid() == Fluids.WATER).findFirst().orElseThrow();
    }

    @Test void probesRealForgeBucketsWithoutMutatingSamples() {
        ItemStack bucket = new ItemStack(Items.BUCKET, 6);
        List<FluidContainerRecipe> recipes = FluidContainerCatalog.discover(List.of(bucket, bucket.copy()),
                List.of(new FluidStack(Fluids.WATER, 1)), FluidContainerCatalogTest::bucketHandler,
                FluidContainerCatalogTest::token);
        assertEquals(2, recipes.size());
        FluidContainerRecipe fill = recipes.stream().filter(FluidContainerRecipe::filling).findFirst().orElseThrow();
        assertEquals(1000, fill.specs().get(1).count());
        assertTrue(fill.output().is(Items.WATER_BUCKET));
        assertEquals(6, bucket.getCount());
        assertFalse(bucket.hasTag());
        assertEquals(fill.getId(), waterFill().getId());
    }

    @Test void sourceFilledBucketDiscoversItsEmptyContainerAndFluid() {
        var recipes = FluidContainerCatalog.discover(List.of(new ItemStack(Items.LAVA_BUCKET)), List.of(),
                FluidContainerCatalogTest::bucketHandler, FluidContainerCatalogTest::token);
        assertEquals(2, recipes.size());
        var drain = recipes.stream().filter(r -> !r.filling()).findFirst().orElseThrow();
        assertEquals(1000, drain.output().getCount());
        assertEquals(Fluids.LAVA, InkFluidSupport.fluid(drain.output()).getFluid());
        assertEquals(1, drain.secondaryOutputs().size());
        assertTrue(drain.secondaryOutputs().get(0).is(Items.BUCKET));
    }

    @Test void duplicateConversionOnlyCreatesOneRecipePerId() {
        AtomicInteger tokensCreated = new AtomicInteger();
        var recipes = FluidContainerCatalog.discover(List.of(new ItemStack(Items.WATER_BUCKET)), List.of(),
                FluidContainerCatalogTest::bucketHandler, fluid -> {
                    tokensCreated.incrementAndGet();
                    return token(fluid);
                });
        assertEquals(2, recipes.size());
        assertEquals(recipes.size(), tokensCreated.get());
    }

    @Test void bucketSubclassWithoutCapabilitiesDiscoversBothConversionsFromFilledSample() {
        var buckets = FluidContainerBucketTestFixtures.subclassBuckets("poisonwater");
        ItemStack filled = new ItemStack(buckets.filled(), 3);
        assertNull(FluidContainerBucketTestFixtures.handler(filled.copyWithCount(1)));
        var recipes = FluidContainerCatalog.discover(List.of(filled), List.of(),
                FluidContainerBucketTestFixtures::handler, FluidContainerCatalogTest::token);
        assertEquals(2, recipes.size());
        assertTrue(recipes.stream().allMatch(r -> r.filledContainer().is(buckets.filled())
                && r.emptyContainer().is(Items.BUCKET) && r.fluid().getAmount() == 1000));
        var drain = recipes.stream().filter(r -> !r.filling()).findFirst().orElseThrow();
        assertEquals(((BucketItem) buckets.filled()).getFluid(), InkFluidSupport.fluid(drain.output()).getFluid());
        assertTrue(drain.secondaryOutputs().get(0).is(Items.BUCKET));
        assertEquals(3, filled.getCount());
        assertFalse(filled.hasTag());
    }

    @Test void fluidCandidateCanFillIntoBucketSubclassWithoutCapabilities() {
        var buckets = FluidContainerBucketTestFixtures.subclassBuckets("candidate_poisonwater");
        var recipes = FluidContainerCatalog.discover(List.of(new ItemStack(Items.BUCKET)),
                List.of(new FluidStack(((BucketItem) buckets.filled()).getFluid(), 1)),
                FluidContainerBucketTestFixtures::handler, FluidContainerCatalogTest::token);
        assertEquals(2, recipes.size());
        var fill = recipes.stream().filter(FluidContainerRecipe::filling).findFirst().orElseThrow();
        assertTrue(fill.output().is(buckets.filled()));
        assertEquals(1000, fill.specs().get(1).count());
    }

    @Test void missingCapabilitiesDoNotTurnMobBucketsIntoPlainWaterRecipes() {
        var recipes = FluidContainerCatalog.discover(
                List.of(new ItemStack(Items.COD_BUCKET), new ItemStack(Items.AXOLOTL_BUCKET)),
                List.of(), stack -> null, FluidContainerCatalogTest::token);

        assertTrue(recipes.isEmpty());
        assertFalse(FluidContainerBucketSupport.needsFallback(new ItemStack(Items.COD_BUCKET)));
    }

    @Test void nonCanonicalBucketSubclassesRequireTheirRealFluidCapabilities() {
        Item special = FluidContainerBucketTestFixtures.nonCanonicalBucket("special_water_bucket");
        ItemStack sample = new ItemStack(special);

        assertTrue(FluidContainerCatalog.discover(List.of(sample), List.of(),
                stack -> null, FluidContainerCatalogTest::token).isEmpty());

        var recipes = FluidContainerCatalog.discover(List.of(sample), List.of(),
                stack -> stack.is(special) || stack.is(Items.BUCKET) || stack.is(Items.WATER_BUCKET)
                        ? new FluidBucketWrapper(stack) : null,
                FluidContainerCatalogTest::token);
        assertTrue(recipes.stream().anyMatch(recipe -> !recipe.filling()
                && recipe.filledContainer().is(special)));
    }

    @Test void bucketsWithoutCapabilitiesKeepTheirDeclaredEmptyContainers() {
        var buckets = FluidContainerBucketTestFixtures.buckets("missing_capability_wood");
        var recipes = FluidContainerCatalog.discover(List.of(new ItemStack(buckets.filled())), List.of(),
                stack -> null, FluidContainerCatalogTest::token);
        assertEquals(2, recipes.size());
        assertTrue(recipes.stream().allMatch(r -> r.filledContainer().is(buckets.filled())
                && r.emptyContainer().is(buckets.empty())));
    }

    @Test void thirdPartyBucketPairsKeepTheirOwnContainersInBothDirections() {
        var wood = FluidContainerBucketTestFixtures.buckets("wood");
        var bamboo = FluidContainerBucketTestFixtures.buckets("bamboo");
        ItemStack woodFilled = new ItemStack(wood.filled(), 3);
        IFluidHandlerItem raw = FluidContainerBucketTestFixtures.handler(woodFilled.copyWithCount(1));
        raw.drain(1000, IFluidHandler.FluidAction.EXECUTE);
        assertTrue(raw.getContainer().is(Items.BUCKET));

        var recipes = FluidContainerCatalog.discover(List.of(woodFilled, new ItemStack(bamboo.filled()),
                        new ItemStack(Items.WATER_BUCKET)), List.of(),
                FluidContainerBucketTestFixtures::handler, FluidContainerCatalogTest::token);
        assertEquals(6, recipes.size());
        for (var buckets : List.of(wood, bamboo)) {
            var pair = recipes.stream().filter(r -> r.filledContainer().is(buckets.filled())).toList();
            assertEquals(2, pair.size());
            assertTrue(pair.stream().allMatch(r -> r.emptyContainer().is(buckets.empty())));
            assertTrue(pair.stream().filter(r -> !r.filling())
                    .allMatch(r -> r.secondaryOutputs().get(0).is(buckets.empty())));
        }
        assertTrue(recipes.stream().filter(r -> r.filledContainer().is(Items.WATER_BUCKET))
                .allMatch(r -> r.emptyContainer().is(Items.BUCKET)));
        var result = PureRecipePlanner.resolve(graph(recipes),
                Map.of(material(new ItemStack(wood.empty())), 1,
                        material(token(new FluidStack(Fluids.WATER, 1))), 1000),
                List.of(demand(new ItemStack(bamboo.filled()), 1)), 20);
        assertFalse(result.feasible(), result.toString());
        assertEquals(3, woodFilled.getCount());
    }

    @Test void customBucketCapabilitiesKeepTheirDeclaredDrainBehavior() {
        var buckets = FluidContainerBucketTestFixtures.buckets("custom_capability");
        var recipes = FluidContainerCatalog.discover(List.of(new ItemStack(buckets.filled())), List.of(),
                stack -> stack.getItem() instanceof BucketItem ? new FluidBucketWrapper(stack) {} : null,
                FluidContainerCatalogTest::token);
        assertFalse(recipes.isEmpty());
        assertTrue(recipes.stream().filter(r -> !r.filling())
                .allMatch(r -> r.secondaryOutputs().get(0).is(Items.BUCKET)));
        assertFalse(recipes.stream().anyMatch(r -> r.emptyContainer().is(buckets.empty())));
    }

    @Test void ordinaryBucketCannotSilentlyStripFluidNbt() {
        FluidStack tagged = new FluidStack(Fluids.WATER, 1000);
        tagged.getOrCreateTag().putString("variant", "special");
        var recipes = FluidContainerCatalog.discover(List.of(new ItemStack(Items.BUCKET)), List.of(tagged),
                FluidContainerCatalogTest::bucketHandler, FluidContainerCatalogTest::token);
        assertTrue(recipes.isEmpty());
    }

    @Test void thirstPurityWaterDiscoversGlassBottleConversionsInBothDirections() {
        FluidStack purity = purityWater(2);
        purity.setAmount(1);
        var recipes = FluidContainerCatalog.discover(
                List.of(new ItemStack(Items.GLASS_BOTTLE), waterBottle(2)), List.of(purity),
                InkBottleFluidHandler::create, FluidContainerCatalogTest::token);

        assertEquals(2, recipes.size());
        var fill = recipes.stream().filter(FluidContainerRecipe::filling).findFirst().orElseThrow();
        var drain = recipes.stream().filter(recipe -> !recipe.filling()).findFirst().orElseThrow();
        assertTrue(fill.emptyContainer().is(Items.GLASS_BOTTLE));
        assertTrue(fill.output().is(Items.POTION));
        assertEquals(2, fill.output().getTag().getInt("Purity"));
        assertEquals(250, fill.fluid().getAmount());
        assertEquals(2, fill.fluid().getTag().getInt("Purity"));
        assertTrue(drain.filledContainer().is(Items.POTION));
        assertEquals(2, drain.filledContainer().getTag().getInt("Purity"));
        assertTrue(drain.secondaryOutputs().get(0).is(Items.GLASS_BOTTLE));
    }

    @Test void recursivePlanCanFillThirstPurityWaterIntoARequestedPlainWaterBottle() {
        FluidStack purity = purityWater(2);
        purity.setAmount(1);
        var recipes = FluidContainerCatalog.discover(
                List.of(new ItemStack(Items.GLASS_BOTTLE), waterBottle(2)), List.of(purity),
                InkBottleFluidHandler::create, FluidContainerCatalogTest::token);
        FluidContainerRecipe fill = recipes.stream()
                .filter(FluidContainerRecipe::filling).findFirst().orElseThrow();
        FluidStack stockFluid = purityWater(2);
        stockFluid.setAmount(1);

        var result = PureRecipePlanner.resolve(graph(recipes),
                Map.of(material(new ItemStack(Items.GLASS_BOTTLE)), 1,
                        material(token(stockFluid)), 250),
                List.of(demand(waterBottle(0), 1)), 20);

        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(fill.getId(), 1)), result.steps());
    }

    @Test void customCapacityAndFluidNbtAreKeptInBothDirections() {
        for (int capacity : List.of(250, 2000)) {
            ItemStack empty = new ItemStack(Items.FLINT);
            empty.getOrCreateTag().putInt("capacity", capacity);
            FluidStack tagged = new FluidStack(Fluids.WATER, 1);
            tagged.getOrCreateTag().putString("variant", "special");
            var recipes = FluidContainerCatalog.discover(List.of(empty), List.of(tagged),
                    NbtContainer::new, FluidContainerCatalogTest::token);
            assertEquals(2, recipes.size());
            FluidContainerRecipe fill = recipes.stream().filter(FluidContainerRecipe::filling).findFirst().orElseThrow();
            FluidContainerRecipe drain = recipes.stream().filter(r -> !r.filling()).findFirst().orElseThrow();
            assertEquals(capacity, fill.specs().get(1).count());
            assertEquals(capacity, fill.fluid().getAmount());
            assertEquals("special", fill.fluid().getTag().getString("variant"));
            assertEquals(capacity, drain.output().getCount());
            assertEquals("special", InkFluidSupport.fluid(drain.output()).getTag().getString("variant"));
            assertTrue(ItemStack.isSameItemSameTags(empty, drain.secondaryOutputs().get(0)));
            assertFalse(empty.getTag().contains("content"));
        }
    }

    @Test void stackIdentityIgnoresCountButKeepsNbtDistinct() {
        ItemStack small = new ItemStack(Items.FLINT, 2);
        small.getOrCreateTag().putInt("capacity", 250);
        ItemStack smallCopy = new ItemStack(Items.FLINT, 7);
        smallCopy.setTag(small.getTag().copy());
        ItemStack large = new ItemStack(Items.FLINT, 2);
        large.getOrCreateTag().putInt("capacity", 2000);

        var recipes = FluidContainerCatalog.discover(
                List.of(small, smallCopy, large),
                List.of(new FluidStack(Fluids.WATER, 1)),
                NbtContainer::new,
                FluidContainerCatalogTest::token);

        assertEquals(4, recipes.size());
        assertEquals(1, recipes.stream().filter(FluidContainerRecipe::filling)
                .filter(recipe -> recipe.emptyContainer().getTag().getInt("capacity") == 250)
                .count());
        assertEquals(1, recipes.stream().filter(FluidContainerRecipe::filling)
                .filter(recipe -> recipe.emptyContainer().getTag().getInt("capacity") == 2000)
                .count());
    }

    @Test void nonBucketValidationOnlyProbesTheRequiredRuntimeHandler() {
        FluidContainerRecipe recipe = new FluidContainerRecipe(id("missing_capability"), true,
                new ItemStack(Items.FLINT), new ItemStack(Items.STICK),
                new FluidStack(Fluids.WATER, 250), token(new FluidStack(Fluids.WATER, 250)));
        try (MockedStatic<FluidUtil> capabilities = mockStatic(FluidUtil.class)) {
            capabilities.when(() -> FluidUtil.getFluidHandler(any(ItemStack.class)))
                    .thenReturn(LazyOptional.empty());
            assertFalse(FluidContainerCatalog.isValid(recipe));
            capabilities.verify(() -> FluidUtil.getFluidHandler(any(ItemStack.class)), times(1));
        }
    }

    @Test void multiTankContainersAreNotReducedToOneFluid() {
        ItemStack empty = new ItemStack(Items.FLINT);
        empty.getOrCreateTag().putInt("capacity", 250);
        assertTrue(FluidContainerCatalog.discover(List.of(empty), List.of(new FluidStack(Fluids.WATER, 1)),
                stack -> new NbtContainer(stack) { @Override public int getTanks() { return 2; } },
                FluidContainerCatalogTest::token).isEmpty());
    }

    @Test void waterAndBucketBecomeARealRecipeNode() {
        FluidContainerRecipe fill = waterFill();
        ImmutableRecipeGraph graph = graph(buckets());
        var result = PureRecipePlanner.resolve(graph,
                Map.of(material(new ItemStack(Items.BUCKET)), 1, material(token(new FluidStack(Fluids.WATER, 1))), 1000),
                List.of(demand(new ItemStack(Items.WATER_BUCKET), 1)), 20);
        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(fill.getId(), 1)), result.steps());
        assertEquals(0L, result.remaining().getOrDefault(material(new ItemStack(Items.BUCKET)), 0L));
        assertEquals(0L, result.remaining().getOrDefault(
                material(token(new FluidStack(Fluids.WATER, 1))), 0L));
        assertTrue(ModType.FLUID_CONTAINER.isVirtual());
        assertEquals(ModType.PureProjectionAudit.SUPPORTED, ModType.FLUID_CONTAINER.pureProjectionAudit());
    }

    @Test void recursivelyProducesFluidAndMissingBucketBeforeFilling() {
        FluidContainerRecipe fill = waterFill();
        var water = material(token(new FluidStack(Fluids.WATER, 1)));
        var bucket = material(new ItemStack(Items.BUCKET));
        var produceWater = new ImmutableRecipeGraph.RecipeNode(id("produce_water"), water, 500,
                List.of(demand(new ItemStack(Items.ICE), 1)), "generic", id("test_machine"));
        var makeBucket = new ImmutableRecipeGraph.RecipeNode(id("make_bucket"), bucket, 1,
                List.of(demand(new ItemStack(Items.IRON_INGOT), 3)), "generic", id("crafting"));
        Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected = new HashMap<>(graph(buckets()).recipesByOutput());
        List<ImmutableRecipeGraph.RecipeNode> waterRecipes = new ArrayList<>(projected.getOrDefault(water, List.of()));
        waterRecipes.add(produceWater);
        projected.put(water, waterRecipes);
        projected.put(bucket, List.of(makeBucket));
        var result = PureRecipePlanner.resolve(new ImmutableRecipeGraph(projected),
                Map.of(material(new ItemStack(Items.ICE)), 2, material(new ItemStack(Items.IRON_INGOT)), 3),
                List.of(demand(new ItemStack(Items.WATER_BUCKET), 1)), 25);
        assertTrue(result.feasible(), result.toString());
        assertEquals(3, result.steps().size());
        assertTrue(result.steps().contains(new PureRecipePlanner.PlannedStep(produceWater.recipeId(), 2)));
        assertTrue(result.steps().contains(new PureRecipePlanner.PlannedStep(makeBucket.recipeId(), 1)));
        assertEquals(fill.getId(), result.steps().get(2).recipeId());
    }

    @Test void existingFilledBucketNeedsNoConversion() {
        var result = PureRecipePlanner.resolve(graph(buckets()), Map.of(material(new ItemStack(Items.WATER_BUCKET)), 1),
                List.of(demand(new ItemStack(Items.WATER_BUCKET), 1)), 20);
        assertTrue(result.feasible());
        assertTrue(result.steps().isEmpty());
    }

    @Test void downstreamRecipeRecursivelyProducesFluidAndFillsItsBucket() {
        var water = material(token(new FluidStack(Fluids.WATER, 1)));
        var clay = material(new ItemStack(Items.CLAY_BALL));
        var produceWater = new ImmutableRecipeGraph.RecipeNode(id("produce_water_for_clay"), water, 500,
                List.of(demand(new ItemStack(Items.ICE), 1)), "generic", id("test_machine"));
        var useBucket = new ImmutableRecipeGraph.RecipeNode(id("use_water_bucket"), clay, 4,
                List.of(demand(new ItemStack(Items.WATER_BUCKET), 1), demand(new ItemStack(Items.DIRT), 1)),
                "generic", id("crafting"));
        Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected =
                new HashMap<>(graph(buckets()).recipesByOutput());
        List<ImmutableRecipeGraph.RecipeNode> waterRecipes = new ArrayList<>(projected.get(water));
        waterRecipes.add(produceWater);
        projected.put(water, waterRecipes);
        projected.put(clay, List.of(useBucket));
        var result = PureRecipePlanner.resolve(new ImmutableRecipeGraph(projected),
                Map.of(material(new ItemStack(Items.ICE)), 2, material(new ItemStack(Items.BUCKET)), 1,
                        material(new ItemStack(Items.DIRT)), 1),
                List.of(demand(new ItemStack(Items.CLAY_BALL), 4)), 25);
        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(produceWater.recipeId(), 2),
                new PureRecipePlanner.PlannedStep(waterFill().getId(), 1),
                new PureRecipePlanner.PlannedStep(useBucket.recipeId(), 1)), result.steps());
    }

    @Test void twoDemandsCannotShareOneBucketOrTheSameFluid() {
        for (Map<ImmutableRecipeGraph.MaterialRef, Integer> stock : List.of(
                Map.of(material(new ItemStack(Items.BUCKET)), 1, material(token(new FluidStack(Fluids.WATER, 1))), 2000),
                Map.of(material(new ItemStack(Items.BUCKET)), 2, material(token(new FluidStack(Fluids.WATER, 1))), 1000))) {
            var result = PureRecipePlanner.resolve(graph(buckets()), stock,
                    List.of(demand(new ItemStack(Items.WATER_BUCKET), 1), demand(new ItemStack(Items.WATER_BUCKET), 1)), 20);
            assertFalse(result.feasible(), result.toString());
        }
    }

    @Test void reverseConversionsCannotCreateResourcesWithoutStock() {
        var result = PureRecipePlanner.resolve(graph(buckets()), Map.of(),
                List.of(demand(new ItemStack(Items.WATER_BUCKET), 1)), 20);
        assertFalse(result.feasible());
    }

    @Test void filledBucketCanSupplyLiquidDemand() {
        var result = PureRecipePlanner.resolve(graph(buckets()), Map.of(material(new ItemStack(Items.WATER_BUCKET)), 1),
                List.of(demand(token(new FluidStack(Fluids.WATER, 1)), 750)), 20);
        assertTrue(result.feasible(), result.toString());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(waterDrain().getId(), 1)), result.steps());
        assertEquals(250, result.remaining().get(material(token(new FluidStack(Fluids.WATER, 1)))));
    }

    static ImmutableRecipeGraph graph(List<FluidContainerRecipe> recipes) {
        Map<Item, List<RecipeIndex.Entry>> index = new HashMap<>();
        Map<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>> projected = new HashMap<>();
        FluidContainerCatalog.publish(recipes, index, new HashSet<>(), projected);
        for (var recipe : recipes) assertTrue(index.get(recipe.output().getItem()).stream()
                .anyMatch(entry -> entry.recipe().getId().equals(recipe.getId())));
        return new ImmutableRecipeGraph(projected);
    }
    static ImmutableRecipeGraph.MaterialRef material(ItemStack stack) { return ImmutableRecipeGraphProjector.material(stack, true); }
    static ImmutableRecipeGraph.IngredientRef demand(ItemStack stack, int count) {
        return new ImmutableRecipeGraph.IngredientRef(List.of(material(stack)), count, ImmutableRecipeGraph.NbtMatchMode.EXACT);
    }

    private static FluidStack purityWater(int purity) {
        FluidStack fluid = new FluidStack(Fluids.WATER, 250);
        fluid.getOrCreateTag().putInt("Purity", purity);
        return fluid;
    }

    private static ItemStack waterBottle(int purity) {
        ItemStack bottle = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
        if (purity != 0) bottle.getOrCreateTag().putInt("Purity", purity);
        return bottle;
    }

    private static ResourceLocation id(String path) { return new ResourceLocation("test", path); }

    private static class NbtContainer implements IFluidHandlerItem {
        private ItemStack stack;
        NbtContainer(ItemStack stack) { this.stack = stack; }
        @Override public ItemStack getContainer() { return stack; }
        @Override public int getTanks() { return 1; }
        @Override public FluidStack getFluidInTank(int tank) {
            return stack.hasTag() && stack.getTag().contains("content")
                    ? FluidStack.loadFluidStackFromNBT(stack.getTag().getCompound("content")) : FluidStack.EMPTY;
        }
        @Override public int getTankCapacity(int tank) { return stack.getOrCreateTag().getInt("capacity"); }
        @Override public boolean isFluidValid(int tank, FluidStack resource) { return true; }
        @Override public int fill(FluidStack resource, FluidAction action) {
            if (!getFluidInTank(0).isEmpty()) return 0;
            int amount = Math.min(resource.getAmount(), getTankCapacity(0));
            if (action.execute() && amount > 0) {
                CompoundTag tag = stack.getTag().copy();
                FluidStack filled = resource.copy();
                filled.setAmount(amount);
                tag.put("content", filled.writeToNBT(new CompoundTag()));
                stack = new ItemStack(Items.STICK);
                stack.setTag(tag);
            }
            return amount;
        }
        @Override public FluidStack drain(FluidStack resource, FluidAction action) {
            return resource.isFluidEqual(getFluidInTank(0)) ? drain(resource.getAmount(), action) : FluidStack.EMPTY;
        }
        @Override public FluidStack drain(int amount, FluidAction action) {
            FluidStack fluid = getFluidInTank(0);
            if (fluid.isEmpty() || amount < fluid.getAmount()) return FluidStack.EMPTY;
            if (action.execute()) {
                CompoundTag tag = stack.getTag().copy();
                tag.remove("content");
                stack = new ItemStack(Items.FLINT);
                stack.setTag(tag);
            }
            return fluid;
        }
    }
}
