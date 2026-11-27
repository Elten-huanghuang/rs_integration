package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.Map;
import java.util.function.Function;

/** 回收是特定配方的一条消耗边，不是罐头或剑刃本身的属性。档位只影响搜索顺序。 */
public enum RecoveryRoutePolicy {
    NORMAL, CONTAINER_RECOVERY, EQUIPMENT_RECOVERY;

    private record Rule(String handler, ResourceLocation type, ResourceLocation input, RecoveryRoutePolicy tier) {}
    private static final ResourceLocation IRON = ResourceLocation.parse("minecraft:iron_ingot");
    private static final Map<ResourceLocation, Rule> RULES = Map.of(
            ResourceLocation.parse("legendary_monsters:iron_ingot_knights_sword_half"),
            new Rule("generic", ResourceLocation.parse("minecraft:crafting"),
                    ResourceLocation.parse("legendary_monsters:knights_sword_blade_half"), EQUIPMENT_RECOVERY),
            ResourceLocation.parse("youkaishomecoming:iron_ingot_from_can_smelting"),
            new Rule("vanilla_furnace", ResourceLocation.parse("minecraft:smelting"),
                    ResourceLocation.parse("youkaishomecoming:can"), CONTAINER_RECOVERY));

    public static RecoveryRoutePolicy classify(RecipeNode node) {
        Rule rule = RULES.get(node.recipeId());
        if (rule == null || !rule.handler.equals(node.modTypeId()) || !rule.type.equals(node.recipeTypeId())
                || !plain(node.output(), IRON) || node.outputCount() != 1 || node.guaranteedOutputs().size() != 1
                || node.dynamicIdentityTargetable() || node.hasFluidOutputs() || !node.magicCosts().isEmpty()
                || node.inputs().size() != 1) return NORMAL;
        var input = node.inputs().get(0);
        return input.role() == DemandRole.CONSUMED && input.count() == 1 && input.alternatives().size() == 1
                && plain(input.alternatives().get(0), rule.input) ? rule.tier : NORMAL;
    }

    private static boolean plain(MaterialRef material, ResourceLocation id) {
        return material.isItem() && id.equals(material.itemId()) && material.nbt().isEmpty()
                && !material.runtimeNbt() && !material.constrainedNbt();
    }

    static RecoveryRoutePolicy classify(RecipeIndex.Entry entry, ItemStack output, ResolutionContext ctx) {
        return classify(entry, output, ctx, BuiltInRegistries.ITEM::getKey);
    }

    static RecoveryRoutePolicy classify(RecipeIndex.Entry entry, ItemStack output, ResolutionContext ctx,
                                        Function<Item, ResourceLocation> itemId) {
        Rule rule = RULES.get(entry.recipe().getId());
        if (rule == null || !rule.handler.equals(entry.modType().id()) || !rule.type.equals(entry.recipeTypeId())
                || output.hasTag() || output.getCount() != 1 || !IRON.equals(itemId.apply(output.getItem()))) return NORMAL;
        var outputs = ctx.declaredRecipeOutputs(entry.recipe());
        if (outputs.size() != 1) return NORMAL;
        var declaration = outputs.get(0);
        if (!declaration.isPrimary() || declaration.dynamicIdentity() || declaration.guaranteedMinimum() != 1
                || declaration.maximumAmount() != 1 || declaration.material() == null || declaration.material().tag() != null
                || declaration.material().item() != output.getItem()) return NORMAL;
        var specs = ctx.declaredRecursiveInputs(entry);
        if (specs == null || specs.size() != 1) return NORMAL;
        var input = specs.get(0);
        if (input.count() != 1 || input.role() != DemandRole.CONSUMED || input.ingredient().getClass() != Ingredient.class)
            return NORMAL;
        var variants = input.ingredient().getItems();
        return variants.length == 1 && !variants[0].hasTag()
                && rule.input.equals(itemId.apply(variants[0].getItem())) ? rule.tier : NORMAL;
    }
}
