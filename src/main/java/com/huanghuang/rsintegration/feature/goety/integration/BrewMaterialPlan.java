package com.huanghuang.rsintegration.feature.goety.integration;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialLocks;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanMaterialBill;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将整锅材料作为多个根需求规划，缺基础原料时也保留完整递归树。 */
public final class BrewMaterialPlan {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            "rs_integration", "witch_cauldron_brew");

    private BrewMaterialPlan() {}

    public static ResourceLocation targetId(int menuId, int revision) {
        return ResourceLocation.fromNamespaceAndPath(ID.getNamespace(),
                ID.getPath() + "/" + menuId + "/" + revision);
    }

    public static boolean isTarget(String id) {
        return id != null && id.startsWith(ID + "/");
    }

    public static PlanResponse create(
            ResourceLocation targetId, List<MissingMaterialOrders.MaterialNeed> needs,
            ItemStack output, Map<StackKey, Long> available, Level level,
            ServerPlayer player, CraftStorageEndpoint endpoint,
            Map<String, String> forced, Map<String, ItemStack> locks,
            PlanningPreferences preferences) {
        var specs = needs.stream().map(need -> new IngredientSpec(
                Ingredient.of(need.alternatives().stream()), need.amount())).toList();
        var effectiveLocks = new LinkedHashMap<>(preferences.ingredientChoices());
        effectiveLocks.putAll(locks);
        var scaled = MaterialLocks.narrowSpecs(ID, specs, effectiveLocks).stream()
                .map(spec -> ScaledIngredientSpec.of(spec, spec.count())).toList();
        var overrides = new LinkedHashMap<ResourceLocation, ResourceLocation>();
        forced.forEach((key, value) -> {
            var item = ResourceLocation.tryParse(key);
            var recipe = ResourceLocation.tryParse(value);
            if (item != null && recipe != null) overrides.put(item, recipe);
        });
        var missing = new ArrayList<String>();
        var graph = CraftingResolver.resolveGraphForScaledSpecsWithTypes(
                scaled, available, level, player, endpoint, missing, overrides, true,
                RSIntegrationConfig.CRAFTING_TYPED_PREVIEW_TIMEOUT_MS.get(),
                null, effectiveLocks, preferences);
        var view = PlanGraphView.from(graph);
        var steps = view.nodes().stream().map(node -> node.asPlanStep()).toList();
        Map<Item, Long> itemAvailable = new LinkedHashMap<>();
        available.forEach((key, count) ->
                itemAvailable.merge(key.toStack().getItem(), count, SaturatingLongMath::add));
        var bill = PlanMaterialBill.summarize(Map.of(), Map.of(), itemAvailable,
                available, output, steps, 1, view, !missing.isEmpty());
        return new PlanResponse(bill.feasible(), output.getHoverName().getString(), output,
                steps, bill.materials(), missing.stream().distinct().toList(), targetId.toString(),
                null, null, 0, 0, 0, List.of(), 1,
                null, null, null, 0L, false, false, false, null, Set.of(),
                bill.leftovers(), null, view, !bill.feasible());
    }
}
