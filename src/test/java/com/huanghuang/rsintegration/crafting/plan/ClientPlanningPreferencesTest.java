package com.huanghuang.rsintegration.crafting.plan;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class ClientPlanningPreferencesTest extends BootstrapTest {
    private static final ResourceLocation GOLD_BLOCK_RECIPE =
            new ResourceLocation("minecraft", "gold_ingot_from_gold_block");

    @TempDir
    Path directory;

    @BeforeAll
    static void loadServerConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "minecraft:gold_ingot_from_smelting_gold_ore",
            "minecraft:gold_ingot_from_blasting_gold_ore",
            "minecraft:gold_ingot_from_nuggets",
            "minecraft:gold_ingot_from_gold_block"
    })
    void persistedDefaultCannotReplaceExplicitRecipe(String clickedRecipe) {
        Path path = directory.resolve("crafting_plan.json");
        assertTrue(CraftingPlanPreferences.saveRecipeChoice(
                path, "minecraft:gold_ingot", GOLD_BLOCK_RECIPE.toString()));
        ResourceLocation clickedId = new ResourceLocation(clickedRecipe);
        GenericCraftPacket packet = spy(new GenericCraftPacket(
                clickedId, true, Map.of(),
                new ResourceLocation("minecraft", "overworld"), BlockPos.ZERO,
                1, false, null, null));

        assertSame(packet, ClientPlanningPreferences.apply(packet, path));

        assertEquals(clickedId, packet.recipeId());
        verify(packet, never()).withRecipeId(any());
        ArgumentCaptor<PlanningPreferences> preferences =
                ArgumentCaptor.forClass(PlanningPreferences.class);
        verify(packet).withPlanningPreferences(preferences.capture());
        assertEquals(new RecipeChoice.Normal(GOLD_BLOCK_RECIPE),
                preferences.getValue().recipeChoices().get("minecraft:gold_ingot"));

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.encode(buffer);
            assertEquals(clickedId, GenericCraftPacket.decode(buffer).recipeId());
        } finally {
            buffer.release();
        }
    }

    @Test
    void recursivePreferencesArePreservedWithoutChangingTheSavedFile() throws Exception {
        Path path = directory.resolve("crafting_plan.json");
        ResourceLocation excluded = new ResourceLocation("minecraft", "stick");
        ItemStack ingredient = new ItemStack(Items.OAK_LOG);
        ingredient.getOrCreateTag().putString("variant", "preferred");
        CraftingPlanPreferences.Snapshot snapshot = new CraftingPlanPreferences.Snapshot(
                OutputDestination.PLAYER_INVENTORY,
                Map.of("minecraft:gold_ingot", new RecipeChoice.Normal(GOLD_BLOCK_RECIPE),
                        "minecraft:oak_planks", RecipeChoice.NoRecipe.INSTANCE),
                Map.of("minecraft:oak_planks#slot", ingredient),
                Set.of(excluded.toString()));
        assertTrue(CraftingPlanPreferences.save(path, snapshot));
        String savedPreferences = Files.readString(path);
        GenericCraftPacket packet = spy(new GenericCraftPacket(
                new ResourceLocation("minecraft", "gold_ingot_from_smelting_gold_ore"), true));

        ClientPlanningPreferences.apply(packet, path);

        ArgumentCaptor<PlanningPreferences> preferences =
                ArgumentCaptor.forClass(PlanningPreferences.class);
        verify(packet).withPlanningPreferences(preferences.capture());
        PlanningPreferences applied = preferences.getValue();
        assertEquals(snapshot.recipeChoices(), applied.recipeChoices());
        assertEquals(Set.of(excluded), applied.excludedRecipes());
        assertTrue(ItemStack.isSameItemSameTags(
                ingredient, applied.ingredientChoices().get("minecraft:oak_planks#slot")));
        assertEquals(snapshot.recipeChoices(), CraftingPlanPreferences.load(path).recipeChoices());
        assertEquals(snapshot.outputDestination(),
                CraftingPlanPreferences.loadOutputDestination(path));
        assertEquals(savedPreferences, Files.readString(path));
    }

    @Test
    void missingPreferencesKeepExplicitRecipeWithEmptyPlanningPreferences() {
        ResourceLocation clickedId =
                new ResourceLocation("minecraft", "gold_ingot_from_smelting_gold_ore");
        GenericCraftPacket packet = spy(new GenericCraftPacket(clickedId, true));

        ClientPlanningPreferences.apply(packet, directory.resolve("missing.json"));

        assertEquals(clickedId, packet.recipeId());
        ArgumentCaptor<PlanningPreferences> preferences =
                ArgumentCaptor.forClass(PlanningPreferences.class);
        verify(packet).withPlanningPreferences(preferences.capture());
        assertTrue(preferences.getValue().isEmpty());
    }
}
