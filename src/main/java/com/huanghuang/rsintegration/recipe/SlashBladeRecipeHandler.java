package com.huanghuang.rsintegration.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.Util;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class SlashBladeRecipeHandler extends AbstractRecipeHandler {

    static {
        registerRecipePrefixes(SlashBladeRecipeHandler.class,
                "mods.flammpfeil.slashblade.recipe.SlashBladeShapedRecipe");
    }

    @Override
    public ModType modType() { return ModType.byId("slashblade"); }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        if (recipe instanceof CraftingRecipe cr) {
            return cr.getResultItem(access);
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        if (!(recipe instanceof CraftingRecipe cr)) return null;
        List<Ingredient> ingredients = cr.getIngredients();
        if (ingredients.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }
        return specs.isEmpty() ? null : specs;
    }

    // ── NBT requirement hint for crafting plan missing-materials display ──

    private static final String SB_INGREDIENT_CLASS =
            "mods.flammpfeil.slashblade.recipe.SlashBladeIngredient";

    /** Check if this ingredient is a SlashBladeIngredient (reflection-free guard). */
    public static boolean isSlashBladeIngredient(Ingredient ingredient) {
        return ingredient.getClass().getName().equals(SB_INGREDIENT_CLASS);
    }

    /**
     * Extract human-readable NBT requirements from a SlashBladeIngredient.
     * Returns null if this isn't a SlashBladeIngredient or has no requirements.
     */
    @Nullable
    public static String describeNbtRequirements(Ingredient ingredient) {
        if (!isSlashBladeIngredient(ingredient)) return null;

        JsonElement json = ingredient.toJson();
        if (!json.isJsonObject()) return null;
        JsonObject obj = json.getAsJsonObject();
        if (!obj.has("request")) return null;
        JsonObject req = obj.getAsJsonObject("request");

        List<String> parts = new ArrayList<>();

        // name (ResourceLocation) — "slashblade:none" means any blade name is accepted
        if (req.has("name")) {
            String name = req.get("name").getAsString();
            if (!name.endsWith(":none")) {
                parts.add("刀名: " + name);
            }
        }

        // proud_soul
        if (req.has("proud_soul")) {
            int v = req.get("proud_soul").getAsInt();
            if (v > 0) parts.add("荣耀之魂≥" + v);
        }

        // kill
        if (req.has("kill")) {
            int v = req.get("kill").getAsInt();
            if (v > 0) parts.add("杀敌数≥" + v);
        }

        // refine
        if (req.has("refine")) {
            int v = req.get("refine").getAsInt();
            if (v > 0) parts.add("精炼≥" + v);
        }

        // enchantments — array of { id, level }
        if (req.has("enchantments")) {
            JsonArray arr = req.getAsJsonArray("enchantments");
            for (JsonElement e : arr) {
                if (e.isJsonObject()) {
                    JsonObject ench = e.getAsJsonObject();
                    String id = ench.has("id") ? ench.get("id").getAsString() : null;
                    if (id != null) {
                        int lvl = ench.has("lvl") ? ench.get("lvl").getAsInt() : 1;
                        parts.add("附魔: " + id + " Lv" + lvl);
                    }
                }
            }
        }

        // sword_type — array of strings
        if (req.has("sword_type")) {
            JsonArray arr = req.getAsJsonArray("sword_type");
            List<String> types = new ArrayList<>();
            for (JsonElement e : arr) {
                String s = e.getAsString();
                if (!s.isEmpty()) types.add(s);
            }
            if (!types.isEmpty()) {
                parts.add("刀类型: " + String.join("/", types));
            }
        }

        if (parts.isEmpty()) return null;
        return " §7(" + String.join(", ", parts) + ")§r";
    }

    /**
     * Direct NBT-to-JSON requirement check for plan-phase matching.
     * Bypasses the {@code toStack() → ingredient.test() → capability} path
     * because TagParser round-trips can break Forge capability init on recreated stacks.
     *
     * @return true if the key's NBT satisfies the ingredient's request thresholds
     */
    public static boolean matchesStackKey(Ingredient ingredient, StackKey key) {
        if (!isSlashBladeIngredient(ingredient)) return false;
        String tag = key.tag();
        if (tag == null || tag.isEmpty()) return false;

        try {
            CompoundTag fullTag = TagParser.parseTag(tag);
            if (!fullTag.contains("bladeState")) return false;
            CompoundTag blade = fullTag.getCompound("bladeState");

            JsonElement json = ingredient.toJson();
            if (!json.isJsonObject()) return false;
            JsonObject obj = json.getAsJsonObject();
            if (!obj.has("request")) return false;
            JsonObject req = obj.getAsJsonObject("request");

            if (req.has("name")) {
                String nameStr = req.get("name").getAsString();
                if (!nameStr.endsWith(":none")) {
                    String actual = blade.getString("translationKey");
                    // RequestDefinition.getTranslationKey() = Util.makeDescriptionId("item", name)
                    String required = Util.makeDescriptionId("item", new ResourceLocation(nameStr));
                    if (!required.equals(actual)) return false;
                }
            }

            if (req.has("proud_soul") && req.get("proud_soul").getAsInt() > 0) {
                if (blade.getInt("proudSoul") < req.get("proud_soul").getAsInt()) return false;
            }

            if (req.has("kill") && req.get("kill").getAsInt() > 0) {
                if (blade.getInt("killCount") < req.get("kill").getAsInt()) return false;
            }

            if (req.has("refine") && req.get("refine").getAsInt() > 0) {
                if (blade.getInt("RepairCounter") < req.get("refine").getAsInt()) return false;
            }

            // enchantments — vanilla ItemStack enchantment list (not bladeState)
            if (req.has("enchantments")) {
                JsonArray reqEnchs = req.getAsJsonArray("enchantments");
                net.minecraft.nbt.ListTag stackEnchs = fullTag.getList("Enchantments",
                        net.minecraft.nbt.Tag.TAG_COMPOUND);
                for (JsonElement e : reqEnchs) {
                    if (!e.isJsonObject()) continue;
                    JsonObject ench = e.getAsJsonObject();
                    String enchId = ench.has("id") ? ench.get("id").getAsString() : null;
                    int requiredLvl = ench.has("lvl") ? ench.get("lvl").getAsInt() : 1;
                    if (enchId == null) continue;
                    int actualLvl = 0;
                    for (int i = 0; i < stackEnchs.size(); i++) {
                        CompoundTag enchTag = stackEnchs.getCompound(i);
                        if (enchId.equals(enchTag.getString("id"))) {
                            actualLvl = enchTag.getShort("lvl");
                            break;
                        }
                    }
                    if (actualLvl < requiredLvl) return false;
                }
            }

            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-SlashBlade] NBT fallback match failed", e);
            return false;
        }
    }
}
