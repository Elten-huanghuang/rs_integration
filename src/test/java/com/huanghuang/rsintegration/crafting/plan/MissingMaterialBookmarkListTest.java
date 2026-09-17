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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void resourceIdMissingTextBecomesClickableLocalizedItem() {
        PlanResponse plan = planWithMissing(
                Map.of(IngredientKey.of(new ItemStack(Items.APPLE)),
                        new PlanResponse.Availability(4, 1)),
                List.of("minecraft:apple"));

        var entry = MissingMaterialBookmarkList.textEntries(plan).get(0);

        assertTrue(entry.bookmarkable());
        assertEquals(Items.APPLE, entry.bookmark().getItem());
        assertEquals(3, entry.missingCount());
        assertEquals(new ItemStack(Items.APPLE).getHoverName().getString(), entry.label());
    }

    @Test
    void diagnosticOnlyItemIsIncludedInTheBookmarkAllList() {
        PlanResponse plan = planWithMissing(Map.of(), List.of("minecraft:apple"));

        var entries = MissingMaterialBookmarkList.textEntries(plan);

        assertTrue(entries.get(0).bookmarkable());
        assertEquals(Items.APPLE, entries.get(0).bookmark().getItem());
        assertEquals(List.of(Items.APPLE), MissingMaterialBookmarkList.bookmarkableItems(plan)
                .stream().map(ItemStack::getItem).toList());
    }

    @Test
    void resourceIdWithFormattedNbtHintRemainsBookmarkable() {
        PlanResponse plan = planWithMissing(Map.of(),
                List.of("minecraft:apple \u00a77NBT requirement"));

        var entry = MissingMaterialBookmarkList.textEntries(plan).get(0);

        assertTrue(entry.bookmarkable());
        assertEquals(Items.APPLE, entry.bookmark().getItem());
    }

    @Test
    void clientLocalizationPreservesMachineReadableItemIdentity() {
        assertEquals(List.of("minecraft:apple", "minecraft:stone"),
                PlanResponseClientPacketHandler.localizeItemNames(List.of(
                        "item.minecraft.apple", "block.minecraft.stone")));
    }

    @Test
    void singleLocalizedMissingNameUsesTheOnlyStructuredShortage() {
        PlanResponse plan = planWithMissing(
                Map.of(IngredientKey.of(new ItemStack(Items.APPLE)),
                        new PlanResponse.Availability(1, 0)),
                List.of("苹果"));

        var entry = MissingMaterialBookmarkList.textEntries(plan).get(0);

        assertTrue(entry.bookmarkable());
        assertEquals(Items.APPLE, entry.bookmark().getItem());
    }

    @Test
    void ambiguousDiagnosticTextRemainsPlain() {
        Map<IngredientKey, PlanResponse.Availability> materials = new LinkedHashMap<>();
        materials.put(IngredientKey.of(new ItemStack(Items.APPLE)),
                new PlanResponse.Availability(1, 0));
        materials.put(IngredientKey.of(new ItemStack(Items.DIAMOND)),
                new PlanResponse.Availability(1, 0));
        PlanResponse plan = planWithMissing(materials, List.of("dynamic recipe unavailable"));

        var entry = MissingMaterialBookmarkList.textEntries(plan).get(0);

        assertFalse(entry.bookmarkable());
        assertEquals("dynamic recipe unavailable", entry.label());
    }

    @Test
    void structuredShortagesAreShownWhenDiagnosticsAreEmpty() {
        Map<IngredientKey, PlanResponse.Availability> materials = new LinkedHashMap<>();
        materials.put(IngredientKey.of(new ItemStack(Items.DIAMOND)),
                new PlanResponse.Availability(4, 2));
        materials.put(IngredientKey.of(new ItemStack(Items.EMERALD)),
                new PlanResponse.Availability(3, 0));
        PlanResponse plan = planWithMissing(materials, List.of());

        var entries = MissingMaterialBookmarkList.textEntries(plan);

        assertEquals(List.of(Items.DIAMOND, Items.EMERALD),
                entries.stream().map(entry -> entry.bookmark().getItem()).toList());
        assertEquals(List.of(2, 3),
                entries.stream().map(MissingMaterialBookmarkList.TextEntry::missingCount).toList());
    }

    private static PlanResponse planWithMissing(
            Map<IngredientKey, PlanResponse.Availability> materials, List<String> missing) {
        return new PlanResponse(false, "", ItemStack.EMPTY,
                List.of(), materials, missing, "test:recipe");
    }
}
