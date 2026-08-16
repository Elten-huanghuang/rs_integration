package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.goety.GoetyDynamicRitualRecipe;
import com.huanghuang.rsintegration.mods.goety.GoetyRitualPolicy;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class GoetyRecipeHandler extends AbstractRecipeHandler {

    private final String modTypeId;
    private final String supportedRecipeClass;

    private GoetyRecipeHandler(String modTypeId, String supportedRecipeClass) {
        this.modTypeId = modTypeId;
        this.supportedRecipeClass = supportedRecipeClass;
    }

    public static GoetyRecipeHandler ritual() {
        return new GoetyRecipeHandler("goety", RITUAL_CLASS);
    }

    public static GoetyRecipeHandler brazier() {
        return new GoetyRecipeHandler(
                com.huanghuang.rsintegration.mods.goety.GoetyRSModule.BRAZIER_TYPE_ID,
                BRAZIER_CLASS);
    }

    public static boolean requiresManualConfirmation(Recipe<?> recipe) {
        if (recipe == null || !RITUAL_CLASS.equals(recipe.getClass().getName())) return false;
        return Reflect.invoke(recipe, "getRitual")
                .map(ritual -> GoetyRitualPolicy.classify(recipe, ritual)
                        == GoetyRitualPolicy.Execution.MANUAL_CONFIRMATION)
                .orElse(true);
    }

    @Override
    public ModType modType() { return ModType.byId(modTypeId); }

    @Override
    public boolean cacheByRecipeClass() {
        // RitualRecipe automation eligibility depends on the individual recipe:
        // sacrifice, conversion and teleport rituals share the same Java class
        // as ordinary craft rituals. A class-level negative cache entry would
        // therefore hide every later craft ritual from the recursive index.
        return !RITUAL_CLASS.equals(supportedRecipeClass);
    }

    @Override
    public boolean preferHandlerIngredients() {
        // Goety ritual inputs include the activation item in addition to the
        // ordinary ingredient list. Generic extraction cannot see that item.
        return true;
    }

    private static final String RITUAL_CLASS = "com.Polarice3.Goety.common.crafting.RitualRecipe";
    private static final String BRAZIER_CLASS = "com.Polarice3.Goety.common.crafting.BrazierRecipe";

    public static boolean isRitualRecipe(Recipe<?> recipe) {
        return recipe != null && RITUAL_CLASS.equals(recipe.getClass().getName());
    }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        String cn = recipe.getClass().getName();

        // Only RitualRecipe and BrazierRecipe are automatable by RS.
        // PulverizeRecipe, CursedInfuserRecipes, SoulAbsorberRecipes,
        // BrewingRecipe, ModCookingRecipe, TaglockRecipe, etc. require
        // machines or mechanics that RS cannot control.
        if (!cn.equals(supportedRecipeClass)) return false;

        // Teleport rituals remain unsupported. Summon, conversion, and
        // sacrifice rituals are exposed as manual-confirmation actions.
        if (cn.equals(RITUAL_CLASS)) {
            var ritualOpt = Reflect.invoke(recipe, "getRitual");
            if (ritualOpt.isEmpty()
                    || GoetyRitualPolicy.classify(recipe, ritualOpt.get())
                    == GoetyRitualPolicy.Execution.UNSUPPORTED) {
                return false;
            }

        }

        return true;
    }

    private boolean isManualRitual(Recipe<?> recipe) {
        return RITUAL_CLASS.equals(supportedRecipeClass)
                && requiresManualConfirmation(recipe);
    }

    @Override
    public boolean hasDeterministicPrimaryOutput(Recipe<?> recipe) {
        // Manual rituals do not publish an item that RSI can guarantee without
        // the player's final world interaction.
        return !isManualRitual(recipe);
    }

    @Override
    public boolean hasRuntimeDependentPrimaryNbt(Recipe<?> recipe) {
        if (!RITUAL_CLASS.equals(supportedRecipeClass)) return false;
        return Reflect.invoke(recipe, "getRitual")
                .map(GoetyRecipeHandler::isCraftItemRitual)
                .orElse(false);
    }

    static boolean isCraftItemRitual(Object ritual) {
        return ritual != null
                && ritual.getClass().getName().endsWith("CraftItemRitual");
    }

    @Override
    public boolean indexPrimaryOutput(Recipe<?> recipe) {
        // Manual rituals remain directly previewable by ID, but cannot serve as
        // recursive producers for another automated plan.
        return !isManualRitual(recipe);
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        // Enchant rituals (goety:enchant/*) declare a PLAIN minecraft:enchanted_book
        // as their `result` — the actual enchantment lives in a separate
        // `enchantment` field and is applied at ritual-execution time
        // (EnchantItemRitual: EnchantedBookItem.createForEnchantment(
        //  new EnchantmentInstance(recipe.getEnchantment(), 1))). So the plain
        // result below would render a blank book in the plan tree. Reconstruct
        // the enchanted book here to mirror the ritual's output exactly.
        ItemStack book = GoetyDynamicRitualRecipe.buildOutput(recipe, 1);
        if (!book.isEmpty()) return book;

        // Try standard getResultItem first (may work on some subclasses)
        ItemStack result = recipe.getResultItem(access);
        if (!result.isEmpty()) return result;

        // Try to find output through the ritual object
        try {
            var ritualObj = Class.forName("com.Polarice3.Goety.common.crafting.RitualRecipe")
                    .getMethod("getRitual").invoke(recipe);
            if (ritualObj != null) {
                // Ritual has getOutput() → ItemStack
                for (var m : ritualObj.getClass().getMethods()) {
                    if ((m.getName().equals("getOutput") || m.getName().equals("getResult"))
                            && ItemStack.class.isAssignableFrom(m.getReturnType())
                            && m.getParameterCount() == 0) {
                        ItemStack s = (ItemStack) m.invoke(ritualObj);
                        if (!s.isEmpty()) return s;
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Goety] Ritual output probe failed", e); }

        // Scan fields for an output ItemStack
        Class<?> scan = recipe.getClass();
        while (scan != null && scan != Object.class) {
            for (var f : scan.getDeclaredFields()) {
                if (!ItemStack.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                try {
                    ItemStack s = (ItemStack) f.get(recipe);
                    if (s != null && !s.isEmpty()) return s.copy();
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Goety] Field probe {} failed", f.getName(), e); }
            }
            scan = scan.getSuperclass();
        }

        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        // Use vanilla getIngredients() — RitualRecipe extends Recipe so this always works.
        // getIngredientsList() may not exist in all Goety versions.
        var ingredients = recipe.getIngredients();
        List<IngredientSpec> result = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) {
                result.add(new IngredientSpec(ing, 1));
            }
        }

        // Include the activation item (scroll/wand) so it appears in the
        // crafting plan tree and is accounted for during material reservation.
        if (RITUAL_CLASS.equals(supportedRecipeClass)) {
            try {
                var act = Reflect.invoke(recipe, "getActivationItem");
                if (act.isPresent() && act.get() instanceof Ingredient aing && !aing.isEmpty()) {
                    result.add(new IngredientSpec(aing, 1));
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Goety] getActivationItem probe failed", e); }
        }

        return result.isEmpty() ? null : result;
    }
}
