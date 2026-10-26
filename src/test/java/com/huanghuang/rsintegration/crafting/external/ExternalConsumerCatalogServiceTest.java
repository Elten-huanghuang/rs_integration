package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.ResourceMatchMode;
import com.huanghuang.rsintegration.api.consumer.ConsumerRecipeCandidate;
import com.huanghuang.rsintegration.api.consumer.ConsumerRecipeQuery;
import com.huanghuang.rsintegration.api.recipe.RecipeExecutionKind;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashSet;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalConsumerCatalogServiceTest extends BootstrapTest {
    @Test
    void resourceMatchingHonorsAllIdentityModes() {
        IntegrationResourceKey requested = resource(tagged("food", 2));
        IntegrationResourceKey extraTag = resource(taggedWithExtra("food", 2));
        IntegrationResourceKey wrongValue = resource(tagged("food", 3));

        assertTrue(ExternalConsumerCatalogService.matchesResource(
                requested, extraTag, ResourceMatchMode.ANY_IDENTITY));
        assertFalse(ExternalConsumerCatalogService.matchesResource(
                requested, extraTag, ResourceMatchMode.EXACT_IDENTITY));
        assertTrue(ExternalConsumerCatalogService.matchesResource(
                requested, extraTag, ResourceMatchMode.PARTIAL_IDENTITY));
        assertFalse(ExternalConsumerCatalogService.matchesResource(
                requested, wrongValue, ResourceMatchMode.PARTIAL_IDENTITY));
    }

    @Test
    void outputQueryKeepsOneCandidateWithEveryDeclaredOutput() {
        IntegrationResourceKey food = resource(tagged("food", 1));
        IntegrationResourceKey side = resource(tagged("side", 1));
        ConsumerRecipeCandidate candidate = new ConsumerRecipeCandidate(
                resourceId("recipes/food"),
                resourceId("processes/kitchen"),
                RecipeExecutionKind.STANDARD_IO,
                List.of(food, side)
        );

        List<ConsumerRecipeCandidate> matches =
                ExternalConsumerCatalogService.queryCandidates(
                        List.of(candidate),
                        new ConsumerRecipeQuery.ByOutputs(
                                List.of(food, side),
                                ResourceMatchMode.EXACT_IDENTITY
                        )
                );

        assertEquals(List.of(candidate), matches);
        assertEquals(2, matches.get(0).declaredOutputs().size());
    }

    @Test
    void catalogQueriesUseStableRecipeOrdering() {
        ConsumerRecipeCandidate second = candidate("recipes/b");
        ConsumerRecipeCandidate first = candidate("recipes/a");

        List<ConsumerRecipeCandidate> matches =
                ExternalConsumerCatalogService.queryCandidates(
                        List.of(second, first),
                        new ConsumerRecipeQuery.All()
                );

        assertEquals(
                List.of(first, second),
                matches
        );
    }

    @Test
    void catalogSupportsStablePagingForSixHundredSeventyEightRecipes() {
        List<ConsumerRecipeCandidate> input = new ArrayList<>();
        for (int index = 0; index < 678; index++) {
            input.add(candidate(String.format("recipes/food_%04d", index)));
        }
        List<ConsumerRecipeCandidate> ordered =
                ExternalConsumerCatalogService.queryCandidates(
                        input, new ConsumerRecipeQuery.All());

        List<ConsumerRecipeCandidate> paged = new ArrayList<>();
        int offset = 0;
        while (offset < ordered.size()) {
            int end = Math.min(ordered.size(), offset + 64);
            paged.addAll(ordered.subList(offset, end));
            offset = end;
        }

        assertEquals(678, paged.size());
        assertEquals(ordered, paged);
        assertEquals(678, new HashSet<>(paged).size());
    }

    private static ConsumerRecipeCandidate candidate(String recipePath) {
        return new ConsumerRecipeCandidate(
                resourceId(recipePath),
                resourceId("processes/kitchen"),
                RecipeExecutionKind.STANDARD_IO,
                List.of(resource(tagged("food", 1)))
        );
    }

    private static IntegrationResourceKey resource(CompoundTag identity) {
        return new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM,
                BuiltInRegistries.ITEM.getKey(Items.APPLE),
                identity
        );
    }

    private static CompoundTag tagged(String category, int tier) {
        CompoundTag tag = new CompoundTag();
        tag.putString("category", category);
        tag.putInt("tier", tier);
        return tag;
    }

    private static CompoundTag taggedWithExtra(String category, int tier) {
        CompoundTag tag = tagged(category, tier);
        tag.putString("extra", "runtime");
        return tag;
    }

    private static ResourceLocation resourceId(String path) {
        return new ResourceLocation("test", path);
    }
}
