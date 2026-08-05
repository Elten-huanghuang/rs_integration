package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MissingMaterialBookmarkListTest extends BootstrapTest {

    @Test
    void collectsOnlyShortRawMaterialsInBillOrder() {
        Map<IngredientKey, PlanResponse.Availability> materials = new LinkedHashMap<>();
        materials.put(IngredientKey.of(new ItemStack(Items.DIAMOND)),
                new PlanResponse.Availability(4, 2));
        materials.put(IngredientKey.of(new ItemStack(Items.IRON_INGOT)),
                new PlanResponse.Availability(2, 2));
        materials.put(IngredientKey.of(new ItemStack(Items.EMERALD)),
                new PlanResponse.Availability(3, 0));
        PlanResponse plan = new PlanResponse(false, "", ItemStack.EMPTY,
                List.of(), materials, List.of(), "test:recipe");

        List<ItemStack> bookmarks = MissingMaterialBookmarkList.from(plan);

        assertEquals(List.of(Items.DIAMOND, Items.EMERALD),
                bookmarks.stream().map(ItemStack::getItem).toList());
        assertEquals(List.of(1, 1), bookmarks.stream().map(ItemStack::getCount).toList());
    }
}
