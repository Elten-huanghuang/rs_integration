package com.huanghuang.rsintegration.mixin.malum;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.sammy.malum.common.recipe.AbstractSpiritListMalumRecipe;
import com.sammy.malum.common.recipe.SpiritFocusingRecipe;
import com.sammy.malum.common.recipe.SpiritInfusionRecipe;
import com.sammy.malum.core.systems.recipe.SpiritWithCount;
import com.sammy.malum.core.systems.spirit.MalumSpiritType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class MalumSpiritMatchingTest extends BootstrapTest {
    @Test
    void singleSpiritRecipesDoNotShadowMultiSpiritCrucibleRecipes() throws Exception {
        Map<String, Item> testSpirits = Map.of(
                "arcane", Items.AMETHYST_SHARD, "infernal", Items.BLAZE_POWDER, "earthen", Items.CLAY_BALL);
        JsonObject redstone = crucibleRecipe("redstone");
        JsonObject glowstone = crucibleRecipe("glowstone_dust");
        JsonObject gunpowder = crucibleRecipe("gunpowder");
        JsonObject blazingQuartz = crucibleRecipe("blazing_quartz");
        JsonObject quartz = crucibleRecipe("quartz");
        for (JsonObject recipe : List.of(redstone, glowstone, gunpowder, quartz)) {
            assertEquals(blazingQuartz.get("input"), recipe.get("input"));
        }

        var redstoneRecipe = recipe(SpiritFocusingRecipe.class, redstone, testSpirits);
        var glowstoneRecipe = recipe(SpiritFocusingRecipe.class, glowstone, testSpirits);
        var gunpowderRecipe = recipe(SpiritFocusingRecipe.class, gunpowder, testSpirits);
        var blazingQuartzRecipe = recipe(SpiritFocusingRecipe.class, blazingQuartz, testSpirits);
        var quartzRecipe = recipe(SpiritFocusingRecipe.class, quartz, testSpirits);
        var candidates = List.of(redstoneRecipe, glowstoneRecipe, gunpowderRecipe, blazingQuartzRecipe, quartzRecipe);

        assertSame(blazingQuartzRecipe, firstMatch(candidates,
                List.of(new ItemStack(Items.BLAZE_POWDER, 2), new ItemStack(Items.AMETHYST_SHARD, 2))));
        assertSame(quartzRecipe, firstMatch(candidates,
                List.of(new ItemStack(Items.CLAY_BALL, 2), new ItemStack(Items.AMETHYST_SHARD, 2))));
        assertSame(redstoneRecipe, firstMatch(candidates, List.of(new ItemStack(Items.AMETHYST_SHARD))));
    }

    @Test
    void altarStillIgnoresExtraSpiritTypesButRequiresEnoughOfEachIngredient() throws Exception {
        var altar = withSpirits(SpiritInfusionRecipe.class, List.of(requirement(Items.AMETHYST_SHARD, 2)));
        ItemStack extra = new ItemStack(Items.BLAZE_POWDER);
        assertTrue(matches(altar, List.of(extra, new ItemStack(Items.AMETHYST_SHARD, 2))));
        assertFalse(matches(altar, List.of(extra, new ItemStack(Items.AMETHYST_SHARD))));
        assertFalse(matches(altar, List.of(extra, new ItemStack(Items.CLAY_BALL, 2))));
    }

    private static SpiritFocusingRecipe firstMatch(List<SpiritFocusingRecipe> candidates, List<ItemStack> spirits) {
        return candidates.stream().filter(candidate -> matches(candidate, spirits)).findFirst().orElseThrow();
    }

    private static boolean matches(AbstractSpiritListMalumRecipe recipe, List<ItemStack> spirits) {
        return recipe.doSpiritsMatch(spirits)
                || AbstractSpiritListMalumRecipeMixin.rsi$acceptsExtraAltarSpirits(recipe, spirits);
    }

    private static JsonObject crucibleRecipe(String name) throws Exception {
        try (var input = SpiritFocusingRecipe.class.getResourceAsStream(
                "/data/malum/recipes/spirit_crucible/" + name + ".json")) {
            assertNotNull(input, name);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static SpiritFocusingRecipe recipe(Class<SpiritFocusingRecipe> type, JsonObject data,
                                               Map<String, Item> testSpirits) throws ReflectiveOperationException {
        List<SpiritWithCount> requirements = new ArrayList<>();
        for (var value : data.getAsJsonArray("spirits")) {
            var spirit = value.getAsJsonObject();
            Item item = testSpirits.get(spirit.get("type").getAsString());
            assertNotNull(item);
            requirements.add(requirement(item, spirit.has("count") ? spirit.get("count").getAsInt() : 1));
        }
        return withSpirits(type, requirements);
    }

    private static SpiritWithCount requirement(Item item, int count) {
        return new SpiritWithCount((MalumSpiritType) null, count) {
            @Override
            public Item getItem() {
                return item;
            }
        };
    }

    private static <T extends AbstractSpiritListMalumRecipe> T withSpirits(Class<T> type, List<SpiritWithCount> spirits)
            throws ReflectiveOperationException {
        T recipe = mock(type, CALLS_REAL_METHODS);
        // 保留原生匹配逻辑，只替代需要 Forge 注册表才能建立的配方实例。
        var field = AbstractSpiritListMalumRecipe.class.getDeclaredField("spirits");
        field.setAccessible(true);
        field.set(recipe, spirits);
        return recipe;
    }
}
