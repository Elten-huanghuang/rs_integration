package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional KubeJS crafting support. KubeJS is deliberately not a compile-time
 * dependency: the adapter discovers the stable KubeJSCraftingRecipe contract
 * through reflection and quietly falls back on ordinary vanilla semantics when
 * KubeJS is absent or changes its internals.
 */
final class KubeJsCraftingSemantics {

    private static final String KJS_INTERFACE =
            "dev.latvian.mods.kubejs.recipe.special.KubeJSCraftingRecipe";
    private static final Map<Class<?>, Method> ACTION_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> STAGE_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> KJS_TYPES = new ConcurrentHashMap<>();
    private static final Map<CraftingRecipe, List<IngredientSpec>> SPEC_CACHE = new ConcurrentHashMap<>();
    private static final Method ABSENT;

    static {
        try {
            ABSENT = KubeJsCraftingSemantics.class.getDeclaredMethod("absentMarker");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private KubeJsCraftingSemantics() {}

    @SuppressWarnings("unused")
    private static void absentMarker() {}

    static boolean isKubeJsRecipe(CraftingRecipe recipe) {
        if (recipe == null) return false;
        Class<?> type = recipe.getClass();
        return KJS_TYPES.computeIfAbsent(type, KubeJsCraftingSemantics::isKubeJsType);
    }

    private static boolean isKubeJsType(Class<?> type) {
        try {
            Class<?> kjs = Class.forName(KJS_INTERFACE, false, type.getClassLoader());
            return kjs.isAssignableFrom(type);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Extracts ordinary crafting inputs while applying KubeJS ingredient actions. */
    @Nullable
    static List<IngredientSpec> extractSpecs(CraftingRecipe recipe) {
        if (!isKubeJsRecipe(recipe)) return null;
        return SPEC_CACHE.computeIfAbsent(recipe, key -> List.copyOf(
                applyIngredientActions(key, ingredientActions(key))));
    }

    static void clearRecipeCache() {
        SPEC_CACHE.clear();
    }

    static List<IngredientSpec> applyIngredientActions(CraftingRecipe recipe, List<?> actions) {
        List<Ingredient> ingredients = recipe.getIngredients();
        List<IngredientSpec> result = new ArrayList<>(ingredients.size());
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (ingredient == null || ingredient.isEmpty()) {
                result.add(IngredientSpec.EMPTY);
                continue;
            }
            int gridIndex = CraftPacketUtils.craftingGridSlot(recipe, i);
            List<CandidateRole> candidates = new ArrayList<>();
            for (ItemStack candidate : ingredient.getItems()) {
                if (candidate == null || candidate.isEmpty()) continue;
                candidates.add(classifyCandidate(recipe, ingredients, actions, gridIndex,
                        candidate.copyWithCount(1)));
            }
            if (candidates.isEmpty()) {
                result.add(new IngredientSpec(ingredient, 1,
                        CraftPacketUtils.craftingDemandRole(ingredient)));
                continue;
            }

            // A KubeJS filter may cover only part of a tag. Prefer a reusable
            // candidate, then a damaged/replaced candidate, before falling back
            // to a consumed candidate. This prevents a kept focus/tool from being
            // multiplied just because the same tag also contains a consumable.
            DemandRole selectedRole = preferredRole(candidates);
            List<ItemStack> selected = new ArrayList<>();
            for (CandidateRole candidate : candidates) {
                if (candidate.role() == selectedRole) {
                    selected.add(candidate.stack());
                }
            }
            Ingredient selectedIngredient = selected.isEmpty()
                    ? ingredient : Ingredient.of(selected.stream());
            result.add(new IngredientSpec(selectedIngredient, 1, selectedRole));
        }
        return result;
    }

    private static DemandRole preferredRole(List<CandidateRole> candidates) {
        boolean catalyst = candidates.stream().anyMatch(c -> c.role() == DemandRole.CATALYST);
        if (catalyst) return DemandRole.CATALYST;
        boolean transformed = candidates.stream().anyMatch(c -> c.role() == DemandRole.TRANSFORMED);
        if (transformed) return DemandRole.TRANSFORMED;
        boolean returning = candidates.stream().anyMatch(c -> c.role() == DemandRole.CONTAINER_RETURNING);
        return returning ? DemandRole.CONTAINER_RETURNING : DemandRole.CONSUMED;
    }

    private static CandidateRole classifyCandidate(CraftingRecipe recipe, List<Ingredient> ingredients,
                                                   List<?> actions, int gridIndex, ItemStack candidate) {
        for (Object action : actions) {
            if (!matchesAction(action, gridIndex, candidate)) continue;
            String type = actionType(action);
            return switch (type) {
                case "keep" -> new CandidateRole(candidate, DemandRole.CATALYST);
                case "replace" -> new CandidateRole(candidate, DemandRole.CONTAINER_RETURNING);
                case "consume" -> new CandidateRole(candidate, DemandRole.CONSUMED);
                // Conservatively reserve one tool per execution. Both the legacy
                // batched path and the inline graph path then return exactly one
                // damaged stack for each reserved input without duplicating it.
                case "damage" -> new CandidateRole(candidate, DemandRole.TRANSFORMED);
                case "custom" -> classifyCustom(recipe, ingredients, gridIndex, candidate);
                default -> classifyNative(candidate);
            };
        }
        return classifyNative(candidate);
    }

    private static CandidateRole classifyNative(ItemStack candidate) {
        ItemStack remainder;
        try {
            remainder = candidate.getCraftingRemainingItem();
        } catch (RuntimeException ignored) {
            remainder = ItemStack.EMPTY;
        }
        if (remainder.isEmpty()) return new CandidateRole(candidate, DemandRole.CONSUMED);
        if (ItemStack.isSameItemSameTags(candidate, remainder)) {
            return new CandidateRole(candidate, DemandRole.CATALYST);
        }
        return new CandidateRole(candidate, DemandRole.CONTAINER_RETURNING);
    }

    private static CandidateRole classifyCustom(CraftingRecipe recipe, List<Ingredient> ingredients,
                                                int gridIndex, ItemStack candidate) {
        try {
            AbstractContainerMenu menu = new AbstractContainerMenu(null, -1) {
                @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
                @Override public boolean stillValid(Player player) { return false; }
            };
            TransientCraftingContainer container = new TransientCraftingContainer(menu, 3, 3);
            for (int i = 0; i < ingredients.size() && i < 9; i++) {
                ItemStack[] options = ingredients.get(i).getItems();
                if (options.length > 0 && !options[0].isEmpty()) {
                    container.setItem(CraftPacketUtils.craftingGridSlot(recipe, i), options[0].copyWithCount(1));
                }
            }
            container.setItem(gridIndex, candidate.copyWithCount(1));
            NonNullList<ItemStack> remainders = recipe.getRemainingItems(container);
            ItemStack remainder = gridIndex < remainders.size()
                    ? remainders.get(gridIndex) : ItemStack.EMPTY;
            if (remainder == null || remainder.isEmpty()) {
                return new CandidateRole(candidate, DemandRole.CONSUMED);
            }
            if (ItemStack.isSameItemSameTags(candidate, remainder)) {
                return new CandidateRole(candidate, DemandRole.CATALYST);
            }
            return new CandidateRole(candidate, DemandRole.CONTAINER_RETURNING);
        } catch (Throwable error) {
            RSIntegrationMod.LOGGER.debug("[RSI-KubeJS] custom ingredient action could not be classified", error);
            return new CandidateRole(candidate, DemandRole.TRANSFORMED);
        }
    }

    private static List<?> ingredientActions(CraftingRecipe recipe) {
        Method method = ACTION_METHODS.computeIfAbsent(recipe.getClass(), KubeJsCraftingSemantics::findActionMethod);
        if (method == ABSENT) return List.of();
        try {
            Object value = method.invoke(recipe);
            return value instanceof List<?> list ? list : List.of();
        } catch (ReflectiveOperationException e) {
            return List.of();
        }
    }

    private static Method findActionMethod(Class<?> type) {
        try {
            Method method = type.getMethod("kjs$getIngredientActions");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException ignored) {
            return ABSENT;
        }
    }

    private static String actionType(Object action) {
        try {
            Object value = action.getClass().getMethod("getType").invoke(action);
            return value == null ? "" : value.toString().toLowerCase(Locale.ROOT);
        } catch (ReflectiveOperationException ignored) {
            String simpleName = action.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            return simpleName.endsWith("action")
                    ? simpleName.substring(0, simpleName.length() - "action".length())
                    : "";
        }
    }

    private static boolean matchesAction(Object action, int gridIndex, ItemStack candidate) {
        try {
            Method method = action.getClass().getMethod("checkFilter", int.class, ItemStack.class);
            return Boolean.TRUE.equals(method.invoke(action, gridIndex, candidate));
        } catch (ReflectiveOperationException ignored) {
            // KubeJS 1.20 exposes these fields publicly. Reading them is a
            // compatibility fallback for builds where checkFilter was renamed.
            try {
                Field indexField = action.getClass().getField("filterIndex");
                int expectedIndex = indexField.getInt(action);
                if (expectedIndex >= 0 && expectedIndex != gridIndex) return false;

                Field ingredientField = action.getClass().getField("filterIngredient");
                Object filter = ingredientField.get(action);
                return !(filter instanceof Ingredient ingredient) || ingredient.test(candidate);
            } catch (ReflectiveOperationException unavailable) {
                // Applying an unreadable filter to every slot would turn all
                // recipe inputs into catalysts. Ignoring it is the safe fallback.
                return false;
            }
        }
    }

    static boolean hasRequiredStage(CraftingRecipe recipe, @Nullable ServerPlayer player) {
        if (!isKubeJsRecipe(recipe)) return true;
        Method method = STAGE_METHODS.computeIfAbsent(recipe.getClass(), KubeJsCraftingSemantics::findStageMethod);
        if (method == ABSENT) return true;
        try {
            Object stage = method.invoke(recipe);
            if (stage == null || stage.toString().isBlank()) return true;
            if (player == null) return false;
            Method stagesMethod = player.getClass().getMethod("kjs$getStages");
            Object stages = stagesMethod.invoke(player);
            Method has = stages.getClass().getMethod("has", String.class);
            return Boolean.TRUE.equals(has.invoke(stages, stage.toString()));
        } catch (ReflectiveOperationException ignored) {
            // Old KubeJS builds without the stage bridge are treated as unlocked.
            return true;
        }
    }

    private static Method findStageMethod(Class<?> type) {
        try {
            Method method = type.getMethod("kjs$getStage");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException ignored) {
            return ABSENT;
        }
    }

    private record CandidateRole(ItemStack stack, DemandRole role) {}
}
