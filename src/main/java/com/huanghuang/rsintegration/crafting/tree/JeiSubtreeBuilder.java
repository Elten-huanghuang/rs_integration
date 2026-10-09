package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialLocks;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Recovers "any of these tag" input sets for the tree's carousel render.
 * <p>
 * The tree itself is server-authoritative: every node is a resolved step with a concrete
 * item. But when a parent recipe accepts a tag on some input slot, the server sends only one
 * concrete member — this walks the client-side vanilla recipe to recover the full Ingredient
 * so the leaf can cycle through all members visually. Recipes with no vanilla
 * ingredient list use the same handler extraction as server-side planning.
 */
public final class JeiSubtreeBuilder {

    private JeiSubtreeBuilder() {}

    /**
     * Populate {@link PlanTreeNode#ingredient} on children whose input slot in the parent recipe
     * accepts a tag (multiple items). Enables the carousel render.
     */
    public static void enrichCarousels(PlanTreeNode root) {
        enrichCarousels(root, Map.of());
    }

    public static void enrichCarousels(PlanTreeNode root, Map<String, ItemStack> materialLocks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        enrichCarousels(root, materialLocks,
                id -> mc.level.getRecipeManager().byKey(id).orElse(null));
    }

    static void enrichCarousels(PlanTreeNode root, Map<String, ItemStack> materialLocks,
                                Function<ResourceLocation, Recipe<?>> recipes) {
        enrichRecursive(root, recipes, materialLocks == null ? Map.of() : materialLocks);
    }

    private static void enrichRecursive(PlanTreeNode node,
                                        Function<ResourceLocation, Recipe<?>> recipes,
                                        Map<String, ItemStack> materialLocks) {
        if (node.step != null && !node.children.isEmpty()) {
            Recipe<?> recipe = recipes.apply(node.step.recipeId());
            if (recipe != null) {
                List<Ingredient> ingredients = selectableIngredients(recipe);
                boolean[] used = new boolean[ingredients.size()];
                for (PlanTreeNode child : node.children) {
                    for (int i = 0; i < ingredients.size(); i++) {
                        if (used[i]) continue;
                        Ingredient ing = ingredients.get(i);
                        if (ing.isEmpty() || ing.getItems().length <= 1) continue;
                        if (ing.test(child.displayStack)) {
                            child.ingredient = ing;
                            child.materialLockKey = MaterialLocks.key(node.step.recipeId(), ing);
                            child.materialOptions = Arrays.stream(ing.getItems())
                                    .filter(stack -> stack != null && !stack.isEmpty())
                                    .map(stack -> stack.copyWithCount(1)).toList();
                            ItemStack locked = materialLocks.get(child.materialLockKey);
                            child.lockedMaterial = locked == null ? null : locked.copyWithCount(1);
                            used[i] = true;
                            break;
                        }
                    }
                }
            }
        }
        for (PlanTreeNode child : node.children) {
            enrichRecursive(child, recipes, materialLocks);
        }
    }

    /** Malum 等配方的原版材料列表为空时，改用服务端规划采用的处理器材料。 */
    static List<Ingredient> selectableIngredients(Recipe<?> recipe) {
        List<Ingredient> declared = recipe.getIngredients();
        if (declared != null && declared.stream().anyMatch(
                ingredient -> ingredient != null && !ingredient.isEmpty())) return declared;
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
        if (specs == null) return declared == null ? List.of() : declared;
        return specs.stream().filter(spec -> spec != null && !spec.isEmpty())
                .map(IngredientSpec::ingredient).toList();
    }
}
