package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class CrockPotRecipeHandler extends AbstractRecipeHandler {

    private static final String RECIPE_CLASS = "com.sihenzhang.crockpot.recipe.cooking.CrockPotCookingRecipe";
    public static final int CAT_COUNT = 10;

    // The Crock Pot block entity has a fixed 4 input slots (RangedWrapper(0,4)),
    // plus 1 fuel + 1 output = 6 total. This holds for every pot block including
    // the portable one — they all share CrockPotBlockEntity. The pot will not
    // start cooking until ALL input slots are occupied, so the filler must pad up
    // to this count, NOT to the recipe's potLevel (which is only the minimum
    // pot-TIER gate: matches() checks wrapper.potLevel >= recipe.potLevel).
    public static final int INPUT_SLOT_COUNT = 4;

    static {
        registerRecipePrefixes(CrockPotRecipeHandler.class, RECIPE_CLASS);
    }

    @Override
    public ModType modType() {
        return ModType.byId("crockpot");
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        // For recipes with category constraints, don't pad with the config
        // filler — the plan shows only real ingredients (with food-value
        // requirements as warnings) and execution fills remaining slots via
        // food-value-aware selection at runtime.
        return getSpecificIngredients(recipe, !hasCategoryConstraints(recipe));
    }

    /**
     * Return ingredient specs for display or extraction.
     *
     * @param padWithFiller if true, pad remaining slots with the config filler
     *                      for correct plan display; if false, return only the
     *                      actual recipe requirements (used by batch delegate
     *                      Phase 1 extraction before food-value slot filling).
     */
    @Nullable
    public static List<IngredientSpec> getSpecificIngredients(Recipe<?> recipe, boolean padWithFiller) {
        return getSpecificIngredients(recipe, padWithFiller, INPUT_SLOT_COUNT);
    }

    /**
     * Return ingredient specs for display or extraction, padding to a specific
     * input-slot count.
     *
     * @param padWithFiller pad remaining slots with the config filler.
     * @param targetSlots   the number of input slots to fill (the pot needs ALL
     *                      of them occupied to cook). The batch delegate passes
     *                      the block's real slot count; context-free callers pass
     *                      {@link #INPUT_SLOT_COUNT}.
     */
    @Nullable
    public static List<IngredientSpec> getSpecificIngredients(Recipe<?> recipe, boolean padWithFiller,
                                                              int targetSlots) {
        try {
            Method getRequirements = recipe.getClass().getMethod("getRequirements");
            List<?> requirements = (List<?>) getRequirements.invoke(recipe);
            List<IngredientSpec> specs = new ArrayList<>();
            int slotCount = 0;

            for (Object req : requirements) {
                slotCount += collectIngredients(req, specs);
            }

            if (padWithFiller) {
                int remaining = targetSlots - slotCount;
                if (remaining > 0) {
                    Ingredient filler = resolveFillerIngredient();
                    if (filler != null) {
                        specs.add(new IngredientSpec(filler, remaining));
                    }
                }
            }

            return specs.isEmpty() ? null : specs;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] Recipe reflection failed", e);
            return null;
        }
    }

    /**
     * @return number of slots this requirement occupies
     */
    private static int collectIngredients(Object requirement, List<IngredientSpec> specs) {
        String className = requirement.getClass().getName();
        try {
            if (className.endsWith("RequirementMustContainIngredient")) {
                Method getIngredient = requirement.getClass().getMethod("getIngredient");
                Method getQuantity = requirement.getClass().getMethod("getQuantity");
                Ingredient ing = (Ingredient) getIngredient.invoke(requirement);
                int qty = (int) getQuantity.invoke(requirement);
                if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, qty));
                return qty;
            } else if (className.endsWith("RequirementMustContainIngredientLessThan")) {
                // This is an upper-bound constraint ("less than N"), not a
                // requirement to put items in.  Putting 0 is always safe.
                return 0;
            } else if (className.endsWith("RequirementCombinationAnd")) {
                Method getFirst = requirement.getClass().getMethod("getFirst");
                Method getSecond = requirement.getClass().getMethod("getSecond");
                return collectIngredients(getFirst.invoke(requirement), specs)
                        + collectIngredients(getSecond.invoke(requirement), specs);
            } else if (className.endsWith("RequirementCombinationOr")) {
                Method getFirst = requirement.getClass().getMethod("getFirst");
                Method getSecond = requirement.getClass().getMethod("getSecond");
                int a = collectIngredients(getFirst.invoke(requirement), specs);
                int b = collectIngredients(getSecond.invoke(requirement), specs);
                return Math.max(a, b);
            }
            return 0;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] Recipe reflection failed", e);
            return 0;
        }
    }

    public static int getPotLevel(Recipe<?> recipe) {
        try {
            Method m = recipe.getClass().getMethod("getPotLevel");
            int level = (int) m.invoke(recipe);
            return level > 0 ? level : 4;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] Recipe reflection failed", e);
            return 4;
        }
    }

    @Nullable
    public static Ingredient resolveFillerIngredient() {
        String id = RSIntegrationConfig.CROCKPOT_FILLER_ITEM.get();
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        if (item == null || item == Items.AIR) return null;
        return Ingredient.of(new ItemStack(item));
    }

    public static int countSlotRequirements(Recipe<?> recipe) {
        try {
            Method getRequirements = recipe.getClass().getMethod("getRequirements");
            List<?> requirements = (List<?>) getRequirements.invoke(recipe);
            int count = 0;
            for (Object req : requirements) {
                count += countSlots(req);
            }
            return count;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] Recipe reflection failed", e);
            return 0;
        }
    }

    private static int countSlots(Object requirement) {
        String className = requirement.getClass().getName();
        try {
            if (className.endsWith("RequirementMustContainIngredient")) {
                Method getQuantity = requirement.getClass().getMethod("getQuantity");
                return (int) getQuantity.invoke(requirement);
            } else if (className.endsWith("RequirementMustContainIngredientLessThan")) {
                return 0; // upper-bound constraint, not a slot consumer
            } else if (className.endsWith("RequirementCombinationAnd")) {
                Method getFirst = requirement.getClass().getMethod("getFirst");
                Method getSecond = requirement.getClass().getMethod("getSecond");
                return countSlots(getFirst.invoke(requirement))
                        + countSlots(getSecond.invoke(requirement));
            } else if (className.endsWith("RequirementCombinationOr")) {
                Method getFirst = requirement.getClass().getMethod("getFirst");
                Method getSecond = requirement.getClass().getMethod("getSecond");
                return Math.max(countSlots(getFirst.invoke(requirement)),
                        countSlots(getSecond.invoke(requirement)));
            }
            return 0;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] Recipe reflection failed", e);
            return 0;
        }
    }

    // Lazy-loaded recipe class for guard check
    private static volatile Class<?> crockPotRecipeClass;
    private static volatile boolean recipeClassProbed;

    private static boolean isCrockPotRecipe(Recipe<?> recipe) {
        if (!recipeClassProbed) {
            recipeClassProbed = true;
            try {
                crockPotRecipeClass = Class.forName(RECIPE_CLASS);
            } catch (Exception ignored) {
                // CrockPot mod not loaded
            }
        }
        return crockPotRecipeClass != null && crockPotRecipeClass.isInstance(recipe);
    }

    public static boolean hasCategoryConstraints(Recipe<?> recipe) {
        // Guard: only CrockPotCookingRecipe has getRequirements().
        // This is called from the generic plan-building path for ALL mod
        // recipes, which would otherwise throw NoSuchMethodException on
        // every non-CrockPot recipe (Malum, Aether, etc.).
        if (!isCrockPotRecipe(recipe)) return false;

        try {
            Method getRequirements = recipe.getClass().getMethod("getRequirements");
            List<?> requirements = (List<?>) getRequirements.invoke(recipe);
            List<String> reqNames = new ArrayList<>();
            for (Object req : requirements) {
                String name = req.getClass().getName();
                reqNames.add(name.substring(name.lastIndexOf('.') + 1));
                if (hasCategoryConstraint(req)) return true;
            }
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] hasCategoryConstraints: recipe={} requirements={} result=false",
                    recipe.getId(), reqNames);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-CrockPot] hasCategoryConstraints failed for {}: {}",
                    recipe.getId(), e.toString());
        }
        return false;
    }

    private static boolean hasCategoryConstraint(Object req) {
        String name = req.getClass().getName();
        if (name.contains("RequirementCategory")) return true;
        if (name.endsWith("RequirementCombinationAnd")
                || name.endsWith("RequirementCombinationOr")) {
            try {
                Method getFirst = req.getClass().getMethod("getFirst");
                Method getSecond = req.getClass().getMethod("getSecond");
                if (hasCategoryConstraint(getFirst.invoke(req))) return true;
                if (hasCategoryConstraint(getSecond.invoke(req))) return true;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] Recipe reflection failed", e);
            }
        }
        return false;
    }

    /**
     * Parse category constraints from a CrockPotCookingRecipe.
     *
     * @return float[2][10] — [0]=mins, [1]=maxs.
     * mins[c] = minimum required value (0 = no min).
     * maxs[c] = maximum allowed value (Float.MAX_VALUE = no max).
     */
    public static float[][] parseCategoryConstraints(Recipe<?> recipe) {
        float[] mins = new float[CAT_COUNT];
        float[] maxs = new float[CAT_COUNT];
        Arrays.fill(maxs, Float.MAX_VALUE);

        try {
            Method getRequirements = recipe.getClass().getMethod("getRequirements");
            List<?> requirements = (List<?>) getRequirements.invoke(recipe);
            for (Object req : requirements) {
                collectCategoryConstraints(req, mins, maxs);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] Recipe reflection failed", e);
        }

        return new float[][]{mins, maxs};
    }

    private static void collectCategoryConstraints(Object req, float[] mins, float[] maxs) {
        try {
            String name = req.getClass().getSimpleName();
            if (name.equals("RequirementCategoryMin")) {
                int cat = ((Enum<?>) req.getClass().getMethod("getCategory").invoke(req)).ordinal();
                float min = (float) req.getClass().getMethod("getMin").invoke(req);
                mins[cat] = Math.max(mins[cat], min);
            } else if (name.equals("RequirementCategoryMinExclusive")) {
                int cat = ((Enum<?>) req.getClass().getMethod("getCategory").invoke(req)).ordinal();
                float min = (float) req.getClass().getMethod("getMin").invoke(req);
                mins[cat] = Math.max(mins[cat], min + 0.01f);
            } else if (name.equals("RequirementCategoryMax")) {
                int cat = ((Enum<?>) req.getClass().getMethod("getCategory").invoke(req)).ordinal();
                float max = (float) req.getClass().getMethod("getMax").invoke(req);
                maxs[cat] = Math.min(maxs[cat], max);
            } else if (name.equals("RequirementCategoryMaxExclusive")) {
                int cat = ((Enum<?>) req.getClass().getMethod("getCategory").invoke(req)).ordinal();
                float max = (float) req.getClass().getMethod("getMax").invoke(req);
                maxs[cat] = Math.min(maxs[cat], max - 0.01f);
            } else if (name.equals("RequirementCombinationAnd")
                    || name.equals("RequirementCombinationOr")) {
                Method getFirst = req.getClass().getMethod("getFirst");
                Method getSecond = req.getClass().getMethod("getSecond");
                collectCategoryConstraints(getFirst.invoke(req), mins, maxs);
                collectCategoryConstraints(getSecond.invoke(req), mins, maxs);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] Recipe reflection failed", e);
        }
    }

    // ── DNF (disjunctive-normal-form) requirement expansion ──────────
    //
    // A Crock Pot recipe is satisfied when its requirement tree evaluates true.
    // COMBINATION_OR means "satisfy either branch"; the old flatten-both-branches
    // logic (parseCategoryConstraints / collectIngredients) wrongly turned OR into
    // AND, so recipes like turkey_dinner (VEGGIE-or-FRUIT) or hot_cocoa (4 cocoa OR
    // 3 cocoa + dairy/sweetener) demanded every branch at once and never matched.
    //
    // Expanding the tree into DNF yields a list of alternative Terms; the recipe
    // matches if ANY single term is satisfiable. Plan building and execution both
    // walk the terms in the same order against the same network snapshot and use
    // the first satisfiable one, so preview and craft always agree.

    /**
     * One DNF alternative: fixed ingredients that must be present plus per-category min/max bounds.
     */
    public record Term(List<IngredientSpec> fixed, float[] mins, float[] maxs) {
    }

    // Guard against pathological trees (deeply nested ORs) blowing up the cartesian
    // product. Real recipes produce a handful of terms; 64 is a generous ceiling.
    private static final int MAX_TERMS = 64;

    /**
     * Expand a recipe's requirement tree into DNF alternative terms. The top-level
     * {@code requirements} list is an implicit AND of its elements. Always returns
     * at least one term (an empty term = "no constraints", filled by neutral filler).
     */
    public static List<Term> expandRequirements(Recipe<?> recipe) {
        try {
            Method getRequirements = recipe.getClass().getMethod("getRequirements");
            List<?> requirements = (List<?>) getRequirements.invoke(recipe);
            List<Term> acc = new ArrayList<>();
            acc.add(emptyTerm());
            for (Object req : requirements) {
                acc = crossAnd(acc, expandReq(req));
                if (acc.size() >= MAX_TERMS) break; // safety cap — keep the terms built so far
            }
            return acc;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] expandRequirements failed", e);
            return List.of(emptyTerm());
        }
    }

    private static List<Term> expandReq(Object req) {
        String name = req.getClass().getSimpleName();
        try {
            switch (name) {
                case "RequirementMustContainIngredient" -> {
                    Ingredient ing = (Ingredient) req.getClass().getMethod("getIngredient").invoke(req);
                    int qty = (int) req.getClass().getMethod("getQuantity").invoke(req);
                    Term t = emptyTerm();
                    if (!ing.isEmpty() && qty > 0) t.fixed().add(new IngredientSpec(ing, qty));
                    return List.of(t);
                }
                case "RequirementMustContainIngredientLessThan" -> {
                    // Upper bound ("less than N"); satisfied by placing 0. Ignored as a leaf.
                    return List.of(emptyTerm());
                }
                case "RequirementCategoryMin" -> {
                    Term t = emptyTerm();
                    int cat = ((Enum<?>) req.getClass().getMethod("getCategory").invoke(req)).ordinal();
                    t.mins()[cat] = (float) req.getClass().getMethod("getMin").invoke(req);
                    return List.of(t);
                }
                case "RequirementCategoryMinExclusive" -> {
                    Term t = emptyTerm();
                    int cat = ((Enum<?>) req.getClass().getMethod("getCategory").invoke(req)).ordinal();
                    t.mins()[cat] = (float) req.getClass().getMethod("getMin").invoke(req) + 0.01f;
                    return List.of(t);
                }
                case "RequirementCategoryMax" -> {
                    Term t = emptyTerm();
                    int cat = ((Enum<?>) req.getClass().getMethod("getCategory").invoke(req)).ordinal();
                    t.maxs()[cat] = (float) req.getClass().getMethod("getMax").invoke(req);
                    return List.of(t);
                }
                case "RequirementCategoryMaxExclusive" -> {
                    Term t = emptyTerm();
                    int cat = ((Enum<?>) req.getClass().getMethod("getCategory").invoke(req)).ordinal();
                    t.maxs()[cat] = (float) req.getClass().getMethod("getMax").invoke(req) - 0.01f;
                    return List.of(t);
                }
                case "RequirementCombinationAnd" -> {
                    Object first = req.getClass().getMethod("getFirst").invoke(req);
                    Object second = req.getClass().getMethod("getSecond").invoke(req);
                    return crossAnd(expandReq(first), expandReq(second));
                }
                case "RequirementCombinationOr" -> {
                    Object first = req.getClass().getMethod("getFirst").invoke(req);
                    Object second = req.getClass().getMethod("getSecond").invoke(req);
                    List<Term> out = new ArrayList<>(expandReq(first));
                    out.addAll(expandReq(second));
                    return out;
                }
                default -> {
                    return List.of(emptyTerm());
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrockPot] expandReq failed for {}", name, e);
            return List.of(emptyTerm());
        }
    }

    /**
     * AND two term lists = cartesian product, merging each pair.
     */
    private static List<Term> crossAnd(List<Term> a, List<Term> b) {
        List<Term> out = new ArrayList<>(Math.min(MAX_TERMS, a.size() * b.size()));
        for (Term ta : a) {
            for (Term tb : b) {
                out.add(mergeTerm(ta, tb));
                if (out.size() >= MAX_TERMS) return out;
            }
        }
        return out;
    }

    /**
     * Merge two AND-ed terms: concat fixed ingredients, take the tighter bound per category.
     */
    private static Term mergeTerm(Term a, Term b) {
        List<IngredientSpec> fixed = new ArrayList<>(a.fixed());
        fixed.addAll(b.fixed());
        float[] mins = new float[CAT_COUNT];
        float[] maxs = new float[CAT_COUNT];
        for (int c = 0; c < CAT_COUNT; c++) {
            mins[c] = Math.max(a.mins()[c], b.mins()[c]);
            maxs[c] = Math.min(a.maxs()[c], b.maxs()[c]);
        }
        return new Term(fixed, mins, maxs);
    }

    private static Term emptyTerm() {
        float[] maxs = new float[CAT_COUNT];
        Arrays.fill(maxs, Float.MAX_VALUE);
        return new Term(new ArrayList<>(), new float[CAT_COUNT], maxs);
    }
}
