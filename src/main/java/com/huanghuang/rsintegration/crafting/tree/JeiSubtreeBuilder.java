package com.huanghuang.rsintegration.crafting.tree;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;

/**
 * Recovers "any of these tag" input sets for the tree's carousel render.
 * <p>
 * The tree itself is server-authoritative: every node is a resolved step with a concrete
 * item. But when a parent recipe accepts a tag on some input slot, the server sends only one
 * concrete member — this walks the client-side vanilla recipe to recover the full Ingredient
 * so the leaf can cycle through all members visually.
 */
public final class JeiSubtreeBuilder {

    private JeiSubtreeBuilder() {}

    /**
     * Populate {@link PlanTreeNode#ingredient} on children whose input slot in the parent recipe
     * accepts a tag (multiple items). Enables the carousel render.
     */
    public static void enrichCarousels(PlanTreeNode root) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        enrichRecursive(root, mc);
    }

    private static void enrichRecursive(PlanTreeNode node, Minecraft mc) {
        if (node.step != null && !node.children.isEmpty()) {
            Recipe<?> recipe = mc.level.getRecipeManager().byKey(node.step.recipeId()).orElse(null);
            if (recipe != null) {
                List<Ingredient> ingredients = recipe.getIngredients();
                boolean[] used = new boolean[ingredients.size()];
                for (PlanTreeNode child : node.children) {
                    // Only raw (leaf) inputs carousel — an intermediate with its own step
                    // represents a specific crafted item, not an "any of these tag" choice.
                    if (child.step != null) continue;
                    for (int i = 0; i < ingredients.size(); i++) {
                        if (used[i]) continue;
                        Ingredient ing = ingredients.get(i);
                        if (ing.isEmpty() || ing.getItems().length <= 1) continue;
                        if (ing.test(child.displayStack)) {
                            child.ingredient = ing;
                            used[i] = true;
                            break;
                        }
                    }
                }
            }
        }
        for (PlanTreeNode child : node.children) {
            enrichRecursive(child, mc);
        }
    }
}
