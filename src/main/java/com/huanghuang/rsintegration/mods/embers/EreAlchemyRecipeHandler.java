package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.recipe.AbstractRecipeHandler;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class EreAlchemyRecipeHandler extends AbstractRecipeHandler {

    static {
        registerRecipePrefixes(EreAlchemyRecipeHandler.class, "com.rekindled.embers.");
    }

    @Override
    public ModType modType() { return ModType.byId(ModIds.ID_EMBERS_ALCHEMY); }

    @Override
    public int claimPriority() {
        return CLAIM_PRIORITY_MOD_FAMILY;
    }

    @Override
    public boolean supportsIntermediateProjection(Recipe<?> recipe) {
        // The tablet still needs the flat executor for locking and pedestal
        // placement, but its item/tablet inputs are stable graph dependencies.
        return true;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        // AlchemyRecipe has a public final 'output' ItemStack field.
        // Some other Embers recipe classes (e.g. stamping) may have an
        // 'output' field of type Either<ItemStack, Ingredient> — guard it.
        return Reflect.getField(recipe, "output")
                .filter(o -> o instanceof ItemStack)
                .map(o -> (ItemStack) o)
                .filter(s -> !s.isEmpty())
                .map(ItemStack::copy)
                .orElse(ItemStack.EMPTY);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<IngredientSpec> specs = new ArrayList<>();

        // The alchemy tablet is a real consumed/reserved machine input. The
        // execution delegate extracts it into the tablet inventory, so it must
        // also be present in the recipe tree and material bill. Raw aspect
        // entries are alternatives; getPlanningIngredients resolves the exact
        // catalysts using the player's world and the alchemy mode.
        Reflect.findField(recipe.getClass(), "tablet").ifPresent(f -> {
            if (!Ingredient.class.isAssignableFrom(f.getType())) return;
            f.setAccessible(true);
            try {
                Ingredient tablet = (Ingredient) f.get(recipe);
                if (tablet != null && !tablet.isEmpty()) {
                    specs.add(new IngredientSpec(tablet, 1));
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        });

        // The exact aspect for each pedestal is seed-dependent and is resolved
        // at plan time. Keep the shared recipe index limited to stable inputs.
        Reflect.findField(recipe.getClass(), "inputs").ifPresent(f -> {
            if (!List.class.isAssignableFrom(f.getType())) return;
            f.setAccessible(true);
            try {
                List<Ingredient> inputs = (List<Ingredient>) f.get(recipe);
                if (inputs != null) {
                    for (Ingredient ing : inputs) {
                        if (ing != null && !ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
                    }
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        });

        return specs.isEmpty() ? null : specs;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<IngredientSpec> getPlanningIngredients(
            Recipe<?> recipe, @Nullable ServerPlayer player,
            @Nullable List<IngredientSpec> ingredients) {
        if (ingredients == null) return List.of();
        if (player == null || ingredients.isEmpty()
                || ingredients.stream().anyMatch(spec -> spec.role() == DemandRole.CATALYST)) {
            return ingredients;
        }
        ServerLevel level = player.serverLevel();
        KnownCodeSavedData savedData = KnownCodeSavedData.get(level);
        savedData.setWorldSeed(level.getSeed());
        List<Ingredient> selected = new ArrayList<>();
        if (RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY_CALC.get()) {
            selected = (List<Ingredient>) Reflect.invoke(recipe, "getCode", level.getSeed())
                    .filter(List.class::isInstance).orElse(List.of());
        } else {
            // 未猜出的密码不能参与规划；首轮猜测的象征由推理代理单独预留。
            int[] code = savedData.getCode(recipe.getId().toString());
            List<Ingredient> aspects = (List<Ingredient>) Reflect.getField(recipe, "aspects")
                    .filter(List.class::isInstance).orElse(List.of());
            List<?> inputs = (List<?>) Reflect.getField(recipe, "inputs")
                    .filter(List.class::isInstance).orElse(List.of());
            if (code == null || code.length != inputs.size()) return ingredients;
            for (int index : code) {
                if (index < 0 || index >= aspects.size()) return ingredients;
                selected.add(aspects.get(index));
            }
        }
        List<IngredientSpec> planned = new ArrayList<>(ingredients);
        for (Ingredient aspect : selected) {
            planned.add(new IngredientSpec(aspect, 1, DemandRole.CATALYST));
        }
        return List.copyOf(planned);
    }
}
