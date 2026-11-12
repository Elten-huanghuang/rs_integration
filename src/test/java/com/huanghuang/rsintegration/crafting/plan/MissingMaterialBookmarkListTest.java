package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissingMaterialBookmarkListTest extends BootstrapTest {
    private static final ResourceLocation BEZOAR_ID =
            new ResourceLocation("rs_integration_test", "bezoar");
    private static final ResourceLocation CAPSULE_ID =
            new ResourceLocation("rs_integration_test", "detoxification_capsule");
    private static final ResourceLocation CUSTOM_LABEL_ID =
            new ResourceLocation("rs_integration_test", "nested/material");

    @BeforeAll
    static void registerCustomDescriptionItems() throws ReflectiveOperationException {
        ForgeRegistry<Item> registry = (ForgeRegistry<Item>) ForgeRegistries.ITEMS;
        boolean locked = registry.isLocked();
        registry.unfreeze();
        Method unfreeze = BuiltInRegistries.ITEM.getClass().getMethod("unfreeze");
        unfreeze.setAccessible(true);
        unfreeze.invoke(BuiltInRegistries.ITEM);
        try {
            registerItem(registry, BEZOAR_ID, "enchantment.confluence.bezoar");
            registerItem(registry, CAPSULE_ID, "enchantment.confluence.detoxification_capsule");
            registerItem(registry, CUSTOM_LABEL_ID, "display.another_namespace.material.label");
            registerItem(registry, new ResourceLocation("rs_integration_test", "shared_name_first"),
                    "enchantment.rs_integration_test.shared");
            registerItem(registry, new ResourceLocation("rs_integration_test", "shared_name_second"),
                    "enchantment.rs_integration_test.shared");
        } finally {
            BuiltInRegistries.ITEM.freeze();
            if (locked) registry.freeze();
        }
    }

    private static void registerItem(ForgeRegistry<Item> registry, ResourceLocation id,
                                     String descriptionId) {
        if (registry.containsKey(id)) return;
        registry.register(id, new Item(new Item.Properties()) {
            @Override
            public String getDescriptionId() {
                return descriptionId;
            }
        });
    }

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
    void arbitraryDescriptionKeyPreservesItemIdentityAndFormattedHint() {
        assertEquals(List.of(CUSTOM_LABEL_ID + " \u00a77NBT requirement"),
                PlanResponseClientPacketHandler.localizeItemNames(List.of(
                        "display.another_namespace.material.label \u00a77NBT requirement")));
    }

    @Test
    void localizedMissingNameMatchesTheStructuredShortage() {
        PlanResponse plan = planWithMissing(
                Map.of(IngredientKey.of(new ItemStack(Items.APPLE)),
                        new PlanResponse.Availability(1, 0)),
                List.of(new ItemStack(Items.APPLE).getHoverName().getString()));

        var entry = MissingMaterialBookmarkList.textEntries(plan).get(0);

        assertTrue(entry.bookmarkable());
        assertEquals(Items.APPLE, entry.bookmark().getItem());
    }

    @Test
    void unrelatedDiagnosticCannotBeReplacedByTheOnlyStructuredShortage() {
        PlanResponse plan = planWithMissing(
                Map.of(IngredientKey.of(new ItemStack(Items.APPLE)),
                        new PlanResponse.Availability(4, 1)),
                List.of("未知的缺料物品"));

        var entries = MissingMaterialBookmarkList.textEntries(plan);

        assertEquals(2, entries.size());
        assertEquals("未知的缺料物品", entries.get(0).label());
        assertFalse(entries.get(0).bookmarkable());
        assertEquals(Items.APPLE, entries.get(1).bookmark().getItem());
        assertEquals(3L, entries.get(1).missingCount());
    }

    @Test
    void customDescriptionKeysKeepDistinctItemsAndTheirOwnShortageCounts() {
        Item bezoar = ForgeRegistries.ITEMS.getValue(BEZOAR_ID);
        Item capsule = ForgeRegistries.ITEMS.getValue(CAPSULE_ID);
        List<String> localized = PlanResponseClientPacketHandler.localizeItemNames(List.of(
                "enchantment.confluence.bezoar",
                "enchantment.confluence.detoxification_capsule"));
        assertEquals(List.of(BEZOAR_ID.toString(), CAPSULE_ID.toString()), localized);
        PlanResponse plan = planWithMissing(
                Map.of(IngredientKey.of(new ItemStack(capsule)),
                        new PlanResponse.Availability(4, 1)), localized);

        var entries = MissingMaterialBookmarkList.textEntries(plan);

        assertEquals(2, entries.size());
        assertEquals(List.of(bezoar, capsule),
                entries.stream().map(entry -> entry.bookmark().getItem()).toList());
        assertEquals(List.of(1L, 3L), entries.stream().map(
                MissingMaterialBookmarkList.TextEntry::missingCount).toList());
    }

    @Test
    void replenishedCustomDescriptionItemDoesNotRemainMissing() {
        Item capsule = ForgeRegistries.ITEMS.getValue(CAPSULE_ID);
        PlanResponse plan = planWithMissing(
                Map.of(IngredientKey.of(new ItemStack(capsule)),
                        new PlanResponse.Availability(1, 1)),
                PlanResponseClientPacketHandler.localizeItemNames(
                        List.of("enchantment.confluence.detoxification_capsule")));

        assertTrue(MissingMaterialBookmarkList.textEntries(plan).isEmpty());
    }

    @Test
    void ambiguousDescriptionKeyCannotBeBoundToAnArbitraryItem() {
        List<String> localized = PlanResponseClientPacketHandler.localizeItemNames(
                List.of("enchantment.rs_integration_test.shared"));

        assertEquals(List.of("enchantment.rs_integration_test.shared"), localized);
        assertFalse(MissingMaterialBookmarkList.textEntries(
                planWithMissing(Map.of(), localized)).get(0).bookmarkable());
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
    void replenishedMaterialDropsStaleMissingDiagnosticButKeepsOtherDiagnostics() {
        PlanResponse plan = planWithMissing(Map.of(
                IngredientKey.of(new ItemStack(Items.APPLE)), new PlanResponse.Availability(10, 12, 0)),
                List.of("minecraft:apple", "dynamic recipe unavailable"));
        assertEquals(List.of("dynamic recipe unavailable"), MissingMaterialBookmarkList.textEntries(plan)
                .stream().map(MissingMaterialBookmarkList.TextEntry::label).toList());
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
        assertEquals(List.of(2L, 3L),
                entries.stream().map(MissingMaterialBookmarkList.TextEntry::missingCount).toList());
    }

    private static PlanResponse planWithMissing(
            Map<IngredientKey, PlanResponse.Availability> materials, List<String> missing) {
        return new PlanResponse(false, "", ItemStack.EMPTY,
                List.of(), materials, missing, "test:recipe");
    }
}
