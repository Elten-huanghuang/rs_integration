package com.huanghuang.rsintegration.recipe;

import net.minecraft.world.item.crafting.Recipe;

import java.util.HashMap;
import java.util.Map;

/**
 * Base class for per-mod recipe handlers. Eliminates the duplicated
 * {@code canHandle} / static prefix pattern across ~20 handlers.
 *
 * <p>Subclasses call {@link #registerRecipePrefixes(Class, String...)}
 * in a {@code static} block and only need to implement
 * {@link #modType()}, {@link #getResultItem}, and {@link #getIngredients}.</p>
 */
public abstract class AbstractRecipeHandler implements ModRecipeHandler {

    private static final Map<Class<? extends AbstractRecipeHandler>, String[]> PREFIX_CACHE = new HashMap<>();

    protected static void registerRecipePrefixes(Class<? extends AbstractRecipeHandler> handlerClass,
                                                  String... prefixes) {
        PREFIX_CACHE.put(handlerClass, prefixes);
    }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        String[] prefixes = PREFIX_CACHE.get(this.getClass());
        if (prefixes == null) return false;
        String name = recipe.getClass().getName();
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
