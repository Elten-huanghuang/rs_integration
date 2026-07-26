package com.huanghuang.rsintegration.mods.lychee;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import snownee.lychee.core.def.BlockPredicateHelper;
import snownee.lychee.core.post.DropItem;
import snownee.lychee.core.post.PostAction;
import snownee.lychee.item_inside.ItemInsideRecipe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict adapter for the pack's deterministic virtual item-inside conversions. */
public final class LycheeVirtualRecipeHandler implements ModRecipeHandler {

    private record Substrate(String blockId, int catalystMask, boolean sourceLevelRequired) {}

    private static final Substrate POWDER_SNOW = new Substrate(
            "minecraft:powder_snow", LycheeVirtualCatalysts.POWDER_SNOW_BUCKET, false);
    private static final Substrate GREEK_FIRE = new Substrate(
            "locusazzurro_icaruswings:greek_fire", LycheeVirtualCatalysts.GREEK_FIRE_BUCKET, true);
    private static final Substrate DWARVEN_OIL = new Substrate(
            "embers:dwarven_oil_block", LycheeVirtualCatalysts.DWARVEN_OIL_BUCKET, true);
    private static final Substrate DEEP_AETHER_POISON = new Substrate(
            "deep_aether:poison", LycheeVirtualCatalysts.DEEP_AETHER_POISON_BUCKET, true);

    private static final Map<String, Substrate> SUPPORTED_RECIPES = createSupportedRecipes();

    @Nonnull
    @Override
    public ModType modType() {
        return ModType.byId(LycheeRSModule.TYPE_ID);
    }

    @Override
    public boolean canHandle(@Nonnull Recipe<?> recipe) {
        return isSupported(recipe);
    }

    @Override
    public boolean cacheByRecipeClass() {
        return false;
    }

    @Override
    public boolean isAvailableForPlanning(@Nonnull Recipe<?> recipe,
                                          @Nullable ServerPlayer player) {
        int required = requiredCatalystMask(recipe.getId());
        return isSupported(recipe) && required != 0
                && LycheeVirtualCatalysts.hasCatalyst(player, required);
    }

    @Nonnull
    @Override
    public ItemStack getResultItem(@Nonnull Recipe<?> recipe, @Nonnull RegistryAccess access) {
        DropItem action = supportedDropAction(recipe);
        return action == null ? ItemStack.EMPTY : action.stack.copy();
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        if (!isSupported(recipe)) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) specs.add(new IngredientSpec(ingredient, 1));
        }
        return specs.isEmpty() ? null : List.copyOf(specs);
    }

    public static boolean isSupported(Object value) {
        return unsupportedReason(value) == null;
    }

    /** Returns {@code null} when supported, otherwise a stable diagnostic reason. */
    @Nullable
    public static String unsupportedReason(Object value) {
        if (!(value instanceof ItemInsideRecipe recipe)) {
            return "class=" + (value == null ? "null" : value.getClass().getName());
        }
        if (!isSupportedId(recipe.getId())) return "recipe_id=" + recipe.getId();
        if (!isAllowedByConfig(recipe.getId())) return "not_in_allowlist=" + recipe.getId();
        if (recipe.getTime() != 0) return "time=" + recipe.getTime();
        if (!recipe.getConditions().isEmpty()) {
            return "recipe_conditions=" + recipe.getConditions().size();
        }
        Substrate substrate = substrateFor(recipe.getId());
        if (substrate == null || !isSupportedSubstrate(recipe, substrate)) {
            return "block_predicate=" + BlockPredicateHelper.toJson(recipe.getBlock());
        }
        if (recipe.getIngredients().isEmpty()) return "ingredients=empty";
        if (recipe.getIngredients().stream().allMatch(Ingredient::isEmpty)) {
            return "ingredients=all_empty";
        }
        List<PostAction> actions = recipe.getPostActions().toList();
        if (actions.size() != 1) return "post_actions=" + actions.size();
        if (!(actions.get(0) instanceof DropItem drop)) {
            return "post_action_class=" + actions.get(0).getClass().getName();
        }
        if (!drop.getConditions().isEmpty()) {
            return "post_conditions=" + drop.getConditions().size();
        }
        if (drop.stack.isEmpty()) return "drop=empty";
        return null;
    }

    static boolean isSupportedId(ResourceLocation id) {
        return substrateFor(id) != null;
    }

    public static int requiredCatalystMask(@Nullable ResourceLocation id) {
        Substrate substrate = substrateFor(id);
        return substrate == null ? 0 : substrate.catalystMask();
    }

    private static boolean isAllowedByConfig(ResourceLocation id) {
        if (id == null || RSIntegrationConfig.LYCHEE_RECIPE_ALLOWLIST == null) return false;
        return RSIntegrationConfig.LYCHEE_RECIPE_ALLOWLIST.get().contains(id.toString());
    }

    private static boolean isSupportedSubstrate(ItemInsideRecipe recipe, Substrate substrate) {
        return isSupportedSubstrateJson(BlockPredicateHelper.toJson(recipe.getBlock()),
                substrate.blockId(), substrate.sourceLevelRequired());
    }

    static boolean isPlainPowderSnowJson(JsonElement serialized) {
        return isSupportedSubstrateJson(serialized, POWDER_SNOW.blockId(), false);
    }

    static boolean isSupportedSubstrateJson(JsonElement serialized, String blockId,
                                              boolean sourceLevelRequired) {
        if (serialized == null || !serialized.isJsonObject()) return false;
        JsonObject predicate = serialized.getAsJsonObject();
        JsonElement blocksElement = predicate.get("blocks");
        if (blocksElement == null || !blocksElement.isJsonArray()) return false;
        JsonArray blocks = blocksElement.getAsJsonArray();
        if (blocks.size() != 1 || !blocks.get(0).isJsonPrimitive()
                || !blockId.equals(blocks.get(0).getAsString())) {
            return false;
        }

        for (String key : List.of("tag", "nbt")) {
            JsonElement value = predicate.get(key);
            if (value != null && !value.isJsonNull()) return false;
        }
        JsonElement state = predicate.get("state");
        if (sourceLevelRequired) {
            if (state == null || !state.isJsonObject()) return false;
            JsonObject expectedState = new JsonObject();
            expectedState.addProperty("level", "0");
            if (!expectedState.equals(state.getAsJsonObject())) return false;
        } else if (state != null && !state.isJsonNull()) {
            return false;
        }
        return predicate.entrySet().stream()
                .allMatch(entry -> List.of("blocks", "tag", "state", "nbt").contains(entry.getKey()));
    }

    @Nullable
    private static Substrate substrateFor(@Nullable ResourceLocation id) {
        return id == null ? null : SUPPORTED_RECIPES.get(id.toString());
    }

    private static Map<String, Substrate> createSupportedRecipes() {
        Map<String, Substrate> recipes = new LinkedHashMap<>();
        addRecipes(recipes, POWDER_SNOW,
                "avaritia.diamond_lattice.1", "avaritia.diamond_lattice.2",
                "avaritia.diamond_lattice.3", "avaritia.diamond_lattice.4",
                "avaritia.diamond_lattice.6", "avaritia.diamond_lattice.7",
                "avaritia.diamond_lattice.8", "avaritia.diamond_lattice.9",
                "avaritia.diamond_lattice.10", "avaritia.diamond_lattice.11",
                "avaritia.diamond_lattice.12");
        addRecipes(recipes, GREEK_FIRE,
                "eidolon.lead_ingot.1", "eidolon.lead_ingot.2", "eidolon.lead_ingot.3",
                "eidolon.silver_ingot.1", "eidolon.silver_ingot.2", "eidolon.silver_ingot.3",
                "minecraft.copper_block", "minecraft.exposed_copper",
                "minecraft.oxidized_copper", "minecraft.weathered_copper",
                "minecraft.sugar.1", "minecraft.sugar.2",
                "nameless_trinkets.dubious_dust",
                "refinedstorage.advanced_processor", "refinedstorage.basic_processor",
                "refinedstorage.improved_processor", "refinedstorage.processor_binding.1",
                "refinedstorage.processor_binding.2", "refinedstorage.silicon");
        addRecipes(recipes, DWARVEN_OIL,
                "avaritia.eternal_singularity", "embers.lead_ingot.special",
                "embers.silver_ingot.special", "yuusha.epic_material",
                "yuusha.legendary_material", "yuusha.rare_material",
                "yuusha.ultimate_material", "yuusha.uncommon_material");
        addRecipes(recipes, DEEP_AETHER_POISON,
                "deep_aether.sterling_aercloud", "aether_redux.sentrite",
                "hmag.evil_crystal_fragment");
        return Map.copyOf(recipes);
    }

    private static void addRecipes(Map<String, Substrate> target, Substrate substrate,
                                   String... recipePaths) {
        for (String path : recipePaths) target.put("crafttweaker:" + path, substrate);
    }

    @Nullable
    private static DropItem supportedDropAction(Object value) {
        if (!(value instanceof ItemInsideRecipe recipe)) return null;
        List<PostAction> actions = recipe.getPostActions().toList();
        if (actions.size() != 1 || !(actions.get(0) instanceof DropItem drop)) return null;
        if (!drop.getConditions().isEmpty() || drop.stack.isEmpty()) return null;
        return drop;
    }
}
